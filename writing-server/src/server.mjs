import http from 'node:http';
import crypto from 'node:crypto';
import { createUpstream } from './upstream.mjs';
import { createVault } from './vault.mjs';
import { runCompletion } from './pipeline.mjs';

const DEFAULT_CONFIG = {
  host: '127.0.0.1',
  port: 8791,
  contextTokens: 1048576,
  upstreamBaseUrl: 'https://api.venice.ai/api/v1',
  upstreamModel: 'deepseek-v4-1-flash-1017',
};
const MAX_BODY = 32 * 1024 * 1024;

function safeToken(expected, provided) {
  const a = Buffer.from(String(expected || ''));
  const b = Buffer.from(String(provided || ''));
  return a.length > 0 && a.length === b.length && crypto.timingSafeEqual(a, b);
}

function sendJson(res, status, body) {
  const text = JSON.stringify(body);
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store' });
  res.end(text);
}

async function readBody(req) {
  let size = 0;
  const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > MAX_BODY) throw Object.assign(new Error('请求体超过 32MB 限制'), { status: 413, code: 'request_too_large' });
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString('utf8')); }
  catch { throw Object.assign(new Error('请求 JSON 无效'), { status: 400, code: 'invalid_json' }); }
}

function modelList(config) {
  const context = Number(config.contextTokens) > 0 ? Number(config.contextTokens) : DEFAULT_CONFIG.contextTokens;
  return {
    object: 'list',
    data: [
      { id: 'pocket-direct', object: 'model', name: 'Pocket Tavern 直连', owned_by: 'pocket-tavern', type: 'text', context_length: context, supports_tools: true, capabilities: { supportsTools: true } },
      { id: 'pocket-writer', object: 'model', name: config.autoRevise ? 'Pocket Tavern 写作修订（试验）' : 'Pocket Tavern 审校建议（保留原稿）', owned_by: 'pocket-tavern', type: 'text', context_length: context, supports_tools: true, capabilities: { supportsTools: true } },
    ],
  };
}

function sse(res, value) { res.write(`data: ${JSON.stringify(value)}\n\n`); }
function comment(res, text) { res.write(`: ${String(text).replace(/[\r\n]/g, ' ')}\n\n`); }

function errorPayload(error) {
  return { error: { message: error?.message || '服务端错误', type: error?.code || 'server_error', code: error?.code || 'server_error' } };
}

function partialPayload(partial) {
  if (!partial) return undefined;
  return {
    id: partial.id,
    content: partial.content || '',
    reasoning: partial.reasoning || '',
    tool_calls: partial.tool_calls || [],
    finish_reason: partial.finish_reason || '',
    usage: partial.usage || undefined,
  };
}

function writePartialStream(res, partial) {
  if (!partial) return;
  const value = partialPayload(partial);
  if (value.reasoning) sse(res, { id: value.id, object: 'chat.completion.chunk', choices: [{ index: 0, delta: { reasoning_content: value.reasoning }, finish_reason: null }] });
  if (value.content) sse(res, { id: value.id, object: 'chat.completion.chunk', choices: [{ index: 0, delta: { content: value.content }, finish_reason: null }] });
  if (value.tool_calls?.length) sse(res, { id: value.id, object: 'chat.completion.chunk', choices: [{ index: 0, delta: { tool_calls: value.tool_calls }, finish_reason: null }] });
}

function publicRun(run) {
  const allowed = ['run_id', 'chat_hash', 'model', 'purpose', 'status', 'created_at', 'updated_at', 'stages', 'error', 'warning'];
  return Object.fromEntries(allowed.filter((key) => run[key] !== undefined).map((key) => [key, run[key]]));
}

function resultJson(result) {
  const message = { role: 'assistant', content: result.content || null };
  if (result.reasoning) message.reasoning_content = result.reasoning;
  if (result.tool_calls?.length) message.tool_calls = result.tool_calls;
  return {
    id: result.id,
    object: 'chat.completion',
    choices: [{ index: 0, message, finish_reason: result.finish_reason || 'stop' }],
    usage: result.usage || undefined,
    pocket_tavern_warning: result.warning || undefined,
    pocket_tavern_notice: result.service_notice || undefined,
  };
}

function writeFinalStream(res, result) {
  const choiceBase = { index: 0, delta: { role: 'assistant' }, finish_reason: null };
  sse(res, { id: result.id, object: 'chat.completion.chunk', choices: [choiceBase] });
  if (result.reasoning) sse(res, { id: result.id, object: 'chat.completion.chunk', choices: [{ index: 0, delta: { reasoning_content: result.reasoning }, finish_reason: null }] });
  // service_notice stays in pocket_tavern_notice (as in resultJson): a content
  // delta would be saved as story text and sent back as context next turn.
  if (result.content) sse(res, { id: result.id, object: 'chat.completion.chunk', choices: [{ index: 0, delta: { content: result.content }, finish_reason: null }] });
  if (result.tool_calls?.length) sse(res, { id: result.id, object: 'chat.completion.chunk', choices: [{ index: 0, delta: { tool_calls: result.tool_calls }, finish_reason: null }] });
  sse(res, { id: result.id, object: 'chat.completion.chunk', choices: [{ index: 0, delta: {}, finish_reason: result.finish_reason || (result.tool_calls?.length ? 'tool_calls' : 'stop') }], usage: result.usage || undefined, pocket_tavern_warning: result.warning || undefined, pocket_tavern_notice: result.service_notice || undefined });
  res.write('data: [DONE]\n\n');
  res.end();
}

export function createServer({ config = {}, vault = null, upstream = null, skills = null } = {}) {
  const resolved = { ...DEFAULT_CONFIG, ...config };
  const store = vault || createVault({ dataDir: resolved.dataDir || './writing-server-data' });
  const remote = upstream || (resolved.upstreamKey ? createUpstream({ baseUrl: resolved.upstreamBaseUrl, key: resolved.upstreamKey }) : null);
  const ready = Boolean(resolved.token && remote);
  const busy = new Set();
  let server;

  async function handler(req, res) {
    const parsed = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    if (parsed.pathname === '/healthz' && req.method === 'GET') return sendJson(res, 200, { ok: true, ready });
    const authorization = String(req.headers.authorization || '');
    if (!resolved.token || !authorization.startsWith('Bearer ') || !safeToken(resolved.token, authorization.slice(7))) return sendJson(res, 401, { error: { message: '未授权', type: 'auth_error' } });
    if (parsed.pathname === '/v1/models' && req.method === 'GET') return sendJson(res, 200, modelList(resolved));
    if (parsed.pathname === '/v1/runs' && req.method === 'GET') {
      const chatId = parsed.searchParams.get('chat_id') || '';
      if (!chatId) return sendJson(res, 400, { error: { message: '缺少 chat_id' } });
      const runs = await store.listRuns(chatId);
      if (parsed.searchParams.get('include_content') !== 'true') {
        return sendJson(res, 200, runs.map(publicRun));
      }
      return sendJson(res, 200, runs);
    }
    const runMatch = parsed.pathname.match(/^\/v1\/runs\/([A-Za-z0-9._-]+)$/);
    if (runMatch && req.method === 'GET') {
      const chatId = parsed.searchParams.get('chat_id') || '';
      if (!chatId) return sendJson(res, 400, { error: { message: '缺少 chat_id' } });
      const run = await store.getRun(chatId, runMatch[1]);
      if (!run) return sendJson(res, 404, { error: { message: '运行记录不存在' } });
      if (parsed.searchParams.get('include_content') !== 'true') {
        return sendJson(res, 200, publicRun(run));
      }
      return sendJson(res, 200, run);
    }
    if (parsed.pathname !== '/v1/chat/completions' || req.method !== 'POST') return sendJson(res, 404, { error: { message: '路径不存在' } });
    if (!ready) return sendJson(res, 503, { error: { message: '写作服务未配置上游 API Key', type: 'not_ready' } });
    let body;
    try { body = await readBody(req); } catch (error) { return sendJson(res, error.status || 400, errorPayload(error)); }
    if (!body || typeof body !== 'object' || Array.isArray(body) || !Array.isArray(body.messages) || !String(body.model || '').trim()) return sendJson(res, 400, { error: { message: '请求缺少 model 或 messages' } });
    if (body.messages.length > 10000 || body.messages.some((message) => !message || typeof message !== 'object' || Array.isArray(message))) return sendJson(res, 400, { error: { message: 'messages 格式或数量无效' } });
    if (!['pocket-direct', 'pocket-writer'].includes(body.model)) return sendJson(res, 400, { error: { message: '未知网关模型' } });
    if (body.metadata !== undefined && (!body.metadata || typeof body.metadata !== 'object' || Array.isArray(body.metadata))) return sendJson(res, 400, { error: { message: 'metadata 必须是对象' } });
    const metadata = body.metadata || {};
    const chatId = String(metadata.pocket_tavern_chat_id || '').trim();
    if (metadata.pocket_tavern_chat_id !== undefined && (typeof metadata.pocket_tavern_chat_id !== 'string' || chatId.length > 200)) return sendJson(res, 400, { error: { message: '会话 ID 格式无效' } });
    if (metadata.pocket_tavern_purpose !== undefined && (typeof metadata.pocket_tavern_purpose !== 'string' || !['chat', 'summary', 'compact', 'rebuild'].includes(metadata.pocket_tavern_purpose))) return sendJson(res, 400, { error: { message: '请求用途无效' } });
    if (body.model === 'pocket-writer' && !chatId) return sendJson(res, 400, { error: { message: 'pocket-writer 缺少会话 ID' } });
    const lockId = chatId || `direct-${crypto.createHash('sha256').update(JSON.stringify(body.messages)).digest('hex')}`;
    if (busy.has(lockId)) return sendJson(res, 409, { error: { message: '同一会话已有运行中的请求', type: 'busy' } });
    busy.add(lockId);
    const abort = new AbortController();
    const onClose = () => { if (!res.writableEnded) abort.abort(); };
    req.once('aborted', onClose);
    res.once('close', onClose);
    const stream = body.stream !== false;
    if (stream) res.writeHead(200, { 'content-type': 'text/event-stream; charset=utf-8', 'cache-control': 'no-cache, no-store', connection: 'keep-alive' });
    let heartbeat = null;
    try {
      const result = await runCompletion({ request: body, config: resolved, vault: store, upstream: remote, signal: abort.signal, skills,
        onStage: async (name, status) => {
          if (!stream) return;
          comment(res, `stage=${name}; status=${status}`);
          if (status === 'started') {
            if (heartbeat) clearInterval(heartbeat);
            heartbeat = setInterval(() => { if (!res.writableEnded) comment(res, `stage=${name}; heartbeat`); }, 15_000);
          } else if (heartbeat) {
            clearInterval(heartbeat);
            heartbeat = null;
          }
        } });
      if (stream) writeFinalStream(res, result); else sendJson(res, 200, resultJson(result));
    } catch (error) {
      const partial = !abort.signal.aborted ? partialPayload(error.partial) : undefined;
      if (!res.headersSent) sendJson(res, error.status || 502, { ...errorPayload(error), partial });
      else if (stream && !res.writableEnded) {
        if (partial) writePartialStream(res, error.partial);
        sse(res, { ...errorPayload(error), partial });
        res.end();
      }
    } finally {
      if (heartbeat) clearInterval(heartbeat);
      busy.delete(lockId);
      req.off('aborted', onClose);
      res.off('close', onClose);
    }
  }

  server = http.createServer((req, res) => { handler(req, res).catch((error) => { if (!res.headersSent) sendJson(res, 500, errorPayload(error)); else res.destroy(error); }); });
  return server;
}

export { DEFAULT_CONFIG, MAX_BODY, modelList, safeToken };
