import http from 'node:http';
import crypto from 'node:crypto';
import { createReadStream, readFileSync } from 'node:fs';
import { createUpstream } from './upstream.mjs';
import { createVault } from './vault.mjs';
import { runCompletion } from './pipeline.mjs';
import { BackupError, createBackupStore } from './backups.mjs';
import { clientAddress, createAuthGuard } from './guard.mjs';
import { capability, contextOf, createCatalog, displayName } from './catalog.mjs';
import { STICKER_LIMITS, STICKER_UPLOAD_TYPE, StickerError, createStickerStore } from './stickers.mjs';
import { DshError, createDshRuntime } from './dsh.mjs';
import { ReachError, createReachRuntime } from './reach.mjs';
import { MODEL_ID, SETTING_OPTIONS, applySettings, loadReviewSkill, saveSettings, settingsOf, validateSettings } from './config.mjs';

const DEFAULT_CONFIG = {
  host: '127.0.0.1',
  port: 8791,
  contextTokens: 1048576,
  upstreamBaseUrl: 'https://api.venice.ai/api/v1',
  upstreamModel: 'deepseek-v4-1-flash-1017',
};
const MAX_BODY = 32 * 1024 * 1024;
const SERVER_VERSION = JSON.parse(readFileSync(new URL('../package.json', import.meta.url), 'utf8')).version;
// A phone that stops reading must not make the server buffer a whole generation.
const MAX_STREAM_BACKLOG = 8 * 1024 * 1024;
const SUPERSEDE_WAIT_MS = 10_000;
const UPLOAD_IDLE_MS = 60_000;
const BACKUP_PATH = /^\/v1\/backups\/(\d{8}-\d{6}-[0-9a-f]{6})$/;

function safeToken(expected, provided) {
  const a = Buffer.from(String(expected || ''));
  const b = Buffer.from(String(provided || ''));
  return a.length > 0 && a.length === b.length && crypto.timingSafeEqual(a, b);
}

function sendJson(res, status, body, headers = {}) {
  const text = JSON.stringify(body);
  res.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'cache-control': 'no-store', ...headers });
  res.end(text);
}

/** A raw body, refused once it passes [limit] bytes. */
async function readRaw(req, limit) {
  let size = 0;
  const chunks = [];
  for await (const chunk of req) {
    size += chunk.length;
    if (size > limit) throw Object.assign(new Error(`请求体超过 ${Math.round(limit / 1024 / 1024)}MB 限制`), { status: 413, code: 'request_too_large' });
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
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

function upstreamHost(config) {
  try { return new URL(config.upstreamBaseUrl).hostname; } catch { return ''; }
}

/**
 * The two aliases for the PC's default model, then every upstream text model as the upstream
 * describes it (Venice's model_spec carries context, capabilities and prices). Each entry names
 * its upstream so the phone can say whose prices apply; nothing here carries a key.
 */
function modelList(config, catalog = []) {
  const host = upstreamHost(config);
  const fallbackContext = Number(config.contextTokens) > 0 ? Number(config.contextTokens) : DEFAULT_CONFIG.contextTokens;
  const fallback = catalog.find((entry) => entry.id === config.upstreamModel);
  const defaultName = displayName(fallback, config.upstreamModel);
  const tools = capability(fallback, 'supportsFunctionCalling') ?? true;
  const alias = (id, name) => ({
    id, object: 'model', name, owned_by: 'pocket-tavern', type: 'text',
    context_length: contextOf(fallback) ?? fallbackContext,
    supports_tools: tools, supports_vision: capability(fallback, 'supportsVision') ?? false,
    supports_reasoning: capability(fallback, 'supportsReasoning') ?? false, capabilities: { supportsTools: tools },
    ...(fallback?.model_spec?.pricing ? { pricing: fallback.model_spec.pricing } : {}),
    upstream: { host, model: config.upstreamModel || '' },
  });
  return {
    object: 'list',
    data: [
      alias('pocket-direct', `默认模型 · ${defaultName}`),
      alias('pocket-writer', `${config.autoRevise ? '默认模型 + 自动修订（试验）' : '默认模型 + 审校建议'} · ${defaultName}`),
      ...catalog.map((entry) => ({ ...entry, owned_by: 'pocket-tavern', upstream: { host, model: entry.id } })),
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

function chunk(id, delta) {
  return { id, object: 'chat.completion.chunk', choices: [{ index: 0, delta, finish_reason: null }] };
}

/**
 * One SSE answer. Reasoning and text go out as they grow and are never repeated, so the
 * final frame only carries what is new (later-stage reasoning, tool calls, usage, notices).
 * service_notice stays in pocket_tavern_notice (as in resultJson): a content delta would be
 * saved as story text and sent back as context next turn.
 */
function answerStream(res, id) {
  const sent = { role: false, reasoning: '', content: '' };
  const open = () => !res.destroyed && !res.writableEnded;
  const role = () => { if (!sent.role) { sent.role = true; sse(res, chunk(id, { role: 'assistant' })); } };
  function push(result) {
    if (!result || !open()) return;
    const reasoning = String(result.reasoning || '');
    const content = String(result.content || '');
    // Only text that extends what was sent can go out; a rewritten snapshot cannot be taken back.
    if (reasoning.length > sent.reasoning.length && reasoning.startsWith(sent.reasoning)) {
      role(); sse(res, chunk(id, { reasoning_content: reasoning.slice(sent.reasoning.length) })); sent.reasoning = reasoning;
    }
    if (content.length > sent.content.length && content.startsWith(sent.content)) {
      role(); sse(res, chunk(id, { content: content.slice(sent.content.length) })); sent.content = content;
    }
  }
  function finish(result) {
    if (!open()) return;
    role();
    push(result);
    if (result.tool_calls?.length) sse(res, chunk(id, { tool_calls: result.tool_calls }));
    sse(res, { id, object: 'chat.completion.chunk', choices: [{ index: 0, delta: {}, finish_reason: result.finish_reason || (result.tool_calls?.length ? 'tool_calls' : 'stop') }], usage: result.usage || undefined, pocket_tavern_warning: result.warning || undefined, pocket_tavern_notice: result.service_notice || undefined });
    res.write('data: [DONE]\n\n');
    res.end();
  }
  function fail(error, partial) {
    if (!open()) return;
    if (partial) {
      push(partial);
      if (partial.tool_calls?.length) sse(res, chunk(id, { tool_calls: partial.tool_calls }));
    }
    sse(res, { ...errorPayload(error), partial: partialPayload(partial) });
    res.end();
  }
  return { push, finish, fail };
}

export function createServer({ config = {}, vault = null, upstream = null, skills = null, backups = null, guard = null, catalog = null, stickers = null, dsh = null, reach = null } = {}) {
  const resolved = { ...DEFAULT_CONFIG, ...config };
  const dataDir = resolved.dataDir || './writing-server-data';
  const store = vault || createVault({ dataDir });
  const backupStore = backups || createBackupStore({ dataDir, keep: resolved.backupKeep ?? 20 });
  const authGuard = guard || createAuthGuard();
  const remote = upstream || (resolved.upstreamKey ? createUpstream({ baseUrl: resolved.upstreamBaseUrl, key: resolved.upstreamKey, idleTimeoutMs: resolved.upstreamIdleTimeoutMs }) : null);
  const models = catalog || createCatalog({ upstream: remote });
  const stickerStore = stickers || createStickerStore({ dataDir });
  const dshRuntime = dsh || createDshRuntime({ dataDir, python: resolved.pythonPath || '' });
  const reachRuntime = reach || createReachRuntime();
  const ready = Boolean(resolved.token && remote);
  const startedAt = new Date();
  // Generations in flight, one per chat and purpose. A repeat of the same request replaces the old one.
  const active = new Map();
  // Settings saves run one after another so two phones cannot interleave config.json writes.
  let settingsWrites = Promise.resolve();

  async function status(source) {
    models.warm();
    const fallback = models.find(resolved.upstreamModel);
    return {
      ok: true, ready, service: 'pocket-tavern-writing-server', version: SERVER_VERSION,
      started_at: startedAt.toISOString(), uptime_seconds: Math.round((Date.now() - startedAt.getTime()) / 1000),
      listen: { hosts: resolved.listenHosts || [resolved.host], port: resolved.port },
      client_address: source,
      upstream: { configured: Boolean(remote), host: upstreamHost(resolved), model: resolved.upstreamModel, idle_timeout_seconds: Math.round((resolved.upstreamIdleTimeoutMs ?? 900_000) / 1000) },
      default_model: { id: resolved.upstreamModel, name: displayName(fallback, resolved.upstreamModel) },
      // null until the upstream catalog has been read once.
      models_available: models.loaded ? models.count : null,
      models_error: models.error || undefined,
      review_skill: resolved.reviewSkillSource?.id || 'none',
      auto_revise: resolved.autoRevise === true,
      archive: true,
      active_requests: active.size,
      backups: await backupStore.summary(),
      stickers: await stickerStore.summary(),
      dsh: await dshRuntime.status(),
      reach: await reachRuntime.status(),
      features: ['live_stream', 'supersede', 'backups', 'auth_throttle', 'model_choice', 'settings', 'stickers', 'dsh_runtime', 'web_reach'],
    };
  }

  function settingsPayload() {
    return {
      settings: settingsOf(resolved), options: SETTING_OPTIONS,
      fixed: { upstream_host: upstreamHost(resolved), port: resolved.port, lan_mode: resolved.lanMode === true },
    };
  }

  async function handleSettings(req, res) {
    if (req.method === 'GET') return sendJson(res, 200, settingsPayload());
    if (req.method !== 'PATCH') return sendJson(res, 405, { error: { message: '只支持 GET 和 PATCH' } });
    try {
      const changes = validateSettings((await readBody(req))?.settings);
      if (changes.upstreamModel && changes.upstreamModel !== resolved.upstreamModel) {
        const { models: available } = await models.list();
        if (models.loaded && !available.some((entry) => entry.id === changes.upstreamModel)) {
          return sendJson(res, 400, { error: { message: `上游没有这个模型：${changes.upstreamModel}`, code: 'unknown_model' } });
        }
      }
      const reviewSkill = 'reviewSkill' in changes ? await loadReviewSkill(changes.reviewSkill) : null;
      const write = settingsWrites.then(() => saveSettings(dataDir, changes));
      settingsWrites = write.catch(() => {});
      await write;
      applySettings(resolved, changes, reviewSkill);
      backupStore.setKeep?.(resolved.backupKeep);
      console.log(`Settings changed from the phone: ${Object.keys(changes).join(', ')}`);
      return sendJson(res, 200, settingsPayload());
    } catch (error) {
      return sendJson(res, error.status || 500, errorPayload(error));
    }
  }

  /** Sticker packs live on this PC; the phone lists them, uploads packs it has read and fetches images. */
  async function handleStickers(req, res, parsed) {
    const route = parsed.pathname.slice('/v1/stickers'.length);
    try {
      if (route === '' && req.method === 'GET') return sendJson(res, 200, { object: 'list', packs: await stickerStore.list(), limits: STICKER_LIMITS });
      if (route === '/packs' && req.method === 'POST') {
        const type = String(req.headers['content-type'] || '').split(';')[0].trim().toLowerCase();
        if (type !== STICKER_UPLOAD_TYPE) return sendJson(res, 415, { error: { message: `表情包需以 ${STICKER_UPLOAD_TYPE} 上传`, code: 'unsupported_media_type' } });
        const pack = await stickerStore.add(await readRaw(req, STICKER_LIMITS.uploadBytes));
        console.log(`Sticker pack added from the phone: ${pack.stickers.length} images`);
        return sendJson(res, 201, pack);
      }
      const packMatch = route.match(/^\/packs\/([0-9a-f]{12})$/);
      if (packMatch && req.method === 'PATCH') {
        const body = await readBody(req);
        if (typeof body?.enabled !== 'boolean') return sendJson(res, 400, { error: { message: '缺少 enabled' } });
        const pack = await stickerStore.setEnabled(packMatch[1], body.enabled);
        return pack ? sendJson(res, 200, pack) : sendJson(res, 404, { error: { message: '表情包不存在' } });
      }
      if (packMatch && req.method === 'DELETE') {
        return (await stickerStore.remove(packMatch[1])) ? sendJson(res, 200, { deleted: packMatch[1] }) : sendJson(res, 404, { error: { message: '表情包不存在' } });
      }
      const fileMatch = route.match(/^\/files\/([0-9a-f]{12})\/(\d{3}\.(?:png|jpg|gif|webp))$/);
      if (fileMatch && req.method === 'GET') {
        const image = await stickerStore.file(fileMatch[1], fileMatch[2]);
        if (!image) return sendJson(res, 404, { error: { message: '表情图片不存在' } });
        // A pack's images never change after upload, so the phone may keep its copy.
        res.writeHead(200, { 'content-type': image.type, 'content-length': String(image.size), 'cache-control': 'private, max-age=31536000, immutable' });
        createReadStream(image.path).on('error', (error) => res.destroy(error)).pipe(res);
        return undefined;
      }
      return sendJson(res, 404, { error: { message: '路径不存在' } });
    } catch (error) {
      const known = error instanceof StickerError || error.status;
      if (!known) console.error('Sticker request failed:', error?.code || error?.message);
      return sendJson(res, error.status || 500, { error: { message: known ? error.message : '表情包操作失败', code: error.code || 'stickers_failed' } });
    }
  }

  async function handleBackups(req, res, parsed) {
    if (parsed.pathname === '/v1/backups' && req.method === 'GET') return sendJson(res, 200, { object: 'list', data: await backupStore.list() });
    if (parsed.pathname === '/v1/backups' && req.method === 'POST') {
      const type = String(req.headers['content-type'] || '').split(';')[0].trim().toLowerCase();
      if (!['application/zip', 'application/octet-stream'].includes(type)) return sendJson(res, 415, { error: { message: '备份必须以 application/zip 上传', code: 'unsupported_media_type' } });
      const length = req.headers['content-length'] === undefined ? null : Number(req.headers['content-length']);
      if (length !== null && (!Number.isSafeInteger(length) || length < 0)) return sendJson(res, 400, { error: { message: 'Content-Length 无效' } });
      // Slow mobile uploads are fine; a stalled one must not hold the upload slot forever.
      req.setTimeout(UPLOAD_IDLE_MS, () => req.destroy(new BackupError('备份上传超时', 408, 'backup_timeout')));
      try {
        const meta = await backupStore.save(req, { declaredLength: length, device: req.headers['x-pocket-device'], appVersion: req.headers['x-pocket-app-version'], reason: req.headers['x-pocket-backup-reason'] });
        return sendJson(res, meta.duplicate ? 200 : 201, meta);
      } catch (error) {
        const known = error instanceof BackupError;
        if (!known) console.error('Backup upload failed:', error?.code || error?.message);
        if (!res.headersSent && !res.destroyed) return sendJson(res, known ? error.status : 400, { error: { message: known ? error.message : '备份上传失败', code: known ? error.code : 'backup_failed' } }, { connection: 'close' });
      } finally {
        req.socket?.setTimeout(0);
      }
      return undefined;
    }
    const match = parsed.pathname.match(BACKUP_PATH);
    if (match && req.method === 'GET') {
      const backup = await backupStore.open(match[1]);
      if (!backup) return sendJson(res, 404, { error: { message: '备份不存在' } });
      res.writeHead(200, {
        'content-type': 'application/zip', 'content-length': String(backup.size), 'cache-control': 'no-store',
        'content-disposition': `attachment; filename="pocket-tavern-${backup.meta.id}.zip"`, 'x-pocket-sha256': backup.meta.sha256,
      });
      backup.stream().on('error', (error) => res.destroy(error)).pipe(res);
      return undefined;
    }
    if (match && req.method === 'DELETE') {
      return (await backupStore.remove(match[1])) ? sendJson(res, 200, { deleted: match[1] }) : sendJson(res, 404, { error: { message: '备份不存在' } });
    }
    return sendJson(res, 404, { error: { message: '路径不存在' } });
  }

  async function handler(req, res) {
    const parsed = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    if (parsed.pathname === '/healthz' && req.method === 'GET') return sendJson(res, 200, { ok: true, ready });
    if (parsed.pathname === '/v1/pocket/hello' && req.method === 'GET') {
      // Lets a phone confirm a LAN address is this PC before sending the token over plain HTTP: it
      // proves knowing the token without revealing it. Only the LAN listener answers, so nobody can
      // fetch proofs through the internet tunnel and replay them on another network.
      const local = String(req.socket?.localAddress || '').replace(/^::ffff:/, '');
      if (resolved.lanMode !== true || local !== resolved.host) return sendJson(res, 404, { error: { message: '路径不存在' } });
      const nonce = parsed.searchParams.get('nonce') || '';
      if (!/^[0-9a-f]{32,128}$/.test(nonce)) return sendJson(res, 400, { error: { message: 'nonce 无效' } });
      return sendJson(res, 200, { proof: crypto.createHmac('sha256', String(resolved.token || '')).update(`pocket-tavern-lan:${nonce}`).digest('hex') });
    }
    const authorization = String(req.headers.authorization || '');
    if (!authorization.startsWith('Bearer ')) return sendJson(res, 401, { error: { message: '未授权', type: 'auth_error' } });
    const source = clientAddress(req, [resolved.host]);
    const retryAfter = authGuard.retryAfter(source);
    if (retryAfter) return sendJson(res, 429, { error: { message: '令牌错误次数过多，请稍后再试', type: 'auth_throttled' } }, { 'retry-after': String(retryAfter) });
    if (!resolved.token || !safeToken(resolved.token, authorization.slice(7))) {
      if (authGuard.fail(source)) console.warn(`Wrong gateway token repeated from ${source}; refusing it for 15 minutes.`);
      return sendJson(res, 401, { error: { message: '未授权', type: 'auth_error' } });
    }
    authGuard.succeed(source);
    if (parsed.pathname === '/v1/models' && req.method === 'GET') {
      const { models: available, error } = await models.list({ refresh: parsed.searchParams.get('refresh') === 'true' });
      return sendJson(res, 200, { ...modelList(resolved, available), ...(error ? { pocket_tavern_warning: error } : {}) });
    }
    if (parsed.pathname === '/v1/pocket/config') return handleSettings(req, res);
    if (parsed.pathname === '/v1/pocket/dsh' && req.method === 'POST') {
      // One DSH tool call from a phone chat; the engine's own answer ({ok, data, error}) goes back as is.
      try {
        const body = await readBody(req);
        return sendJson(res, 200, await dshRuntime.run(body?.command, body?.input ?? {}));
      } catch (error) {
        const known = error instanceof DshError || error.status;
        if (!known) console.error('DSH call failed:', error?.code || error?.message);
        return sendJson(res, error.status || 500, { error: { message: known ? error.message : 'DSH 引擎调用失败', code: error.code || 'dsh_failed' } });
      }
    }
    if (parsed.pathname === '/v1/pocket/reach' && req.method === 'POST') {
      // One web search / page read from a phone chat, run by Agent Reach on this PC.
      try {
        return sendJson(res, 200, await reachRuntime.run(await readBody(req)));
      } catch (error) {
        const known = error instanceof ReachError || error.status;
        if (!known) console.error('Agent Reach call failed:', error?.code || error?.message);
        return sendJson(res, error.status || 500, { error: { message: known ? error.message : '联网查询失败', code: error.code || 'reach_failed' } });
      }
    }
    if (parsed.pathname === '/v1/stickers' || parsed.pathname.startsWith('/v1/stickers/')) return handleStickers(req, res, parsed);
    if (parsed.pathname === '/v1/pocket/status' && req.method === 'GET') return sendJson(res, 200, await status(source));
    if (parsed.pathname === '/v1/backups' || parsed.pathname.startsWith('/v1/backups/')) return handleBackups(req, res, parsed);
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
    if (typeof body.model !== 'string' || !MODEL_ID.test(body.model)) return sendJson(res, 400, { error: { message: '模型 ID 无效' } });
    if (body.metadata !== undefined && (!body.metadata || typeof body.metadata !== 'object' || Array.isArray(body.metadata))) return sendJson(res, 400, { error: { message: 'metadata 必须是对象' } });
    const metadata = body.metadata || {};
    const chatId = String(metadata.pocket_tavern_chat_id || '').trim();
    if (metadata.pocket_tavern_chat_id !== undefined && (typeof metadata.pocket_tavern_chat_id !== 'string' || chatId.length > 200)) return sendJson(res, 400, { error: { message: '会话 ID 格式无效' } });
    if (metadata.pocket_tavern_purpose !== undefined && (typeof metadata.pocket_tavern_purpose !== 'string' || !['chat', 'summary', 'compact', 'rebuild'].includes(metadata.pocket_tavern_purpose))) return sendJson(res, 400, { error: { message: '请求用途无效' } });
    if (body.model === 'pocket-writer' && !chatId) return sendJson(res, 400, { error: { message: 'pocket-writer 缺少会话 ID' } });
    // A summary may run beside the chat reply, so the purpose is part of the lock.
    const lockId = chatId ? `${chatId}\u0000${metadata.pocket_tavern_purpose || 'chat'}` : `direct-${crypto.createHash('sha256').update(JSON.stringify(body.messages)).digest('hex')}`;
    const previous = active.get(lockId);
    if (previous) {
      // The phone sent the same request again, usually after its connection dropped: the newer one wins.
      previous.superseded = true;
      previous.abort.abort();
      await Promise.race([previous.done, new Promise((resolve) => setTimeout(resolve, SUPERSEDE_WAIT_MS).unref())]);
      if (active.has(lockId)) return sendJson(res, 409, { error: { message: '同一会话已有运行中的请求', type: 'busy' } });
    }
    let release;
    const entry = { abort: new AbortController(), superseded: false, done: new Promise((resolve) => { release = resolve; }) };
    active.set(lockId, entry);
    const abort = entry.abort;
    const onClose = () => { if (!res.writableEnded) abort.abort(); };
    req.once('aborted', onClose);
    res.once('close', onClose);
    const stream = body.stream !== false;
    const answer = stream ? answerStream(res, `chatcmpl-${crypto.randomUUID()}`) : null;
    if (stream) res.writeHead(200, { 'content-type': 'text/event-stream; charset=utf-8', 'cache-control': 'no-cache, no-store', connection: 'keep-alive', 'x-accel-buffering': 'no' });
    let heartbeat = null;
    try {
      // A model the upstream marks as non-reasoning gets no reasoning settings it would reject.
      const target = ['pocket-direct', 'pocket-writer'].includes(body.model) ? resolved.upstreamModel : body.model;
      const requestConfig = capability(models.find(target), 'supportsReasoning') === false ? { ...resolved, reasoningEffort: 'default' } : { ...resolved };
      const result = await runCompletion({ request: body, config: requestConfig, vault: store, upstream: remote, signal: abort.signal, skills,
        onProgress: stream ? (_stage, partial) => {
          answer.push(partial);
          if (res.writableLength > MAX_STREAM_BACKLOG) abort.abort();
        } : null,
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
      if (stream) answer.finish(result); else sendJson(res, 200, resultJson(result));
    } catch (error) {
      const reported = entry.superseded ? Object.assign(new Error('同一会话有新的请求，本次已取消'), { code: 'superseded', status: 409 }) : error;
      const partial = !abort.signal.aborted ? error.partial : undefined;
      if (!res.headersSent) sendJson(res, reported.status || 502, { ...errorPayload(reported), partial: partialPayload(partial) });
      else if (stream) answer.fail(reported, partial);
    } finally {
      if (heartbeat) clearInterval(heartbeat);
      if (active.get(lockId) === entry) active.delete(lockId);
      release();
      req.off('aborted', onClose);
      res.off('close', onClose);
    }
  }

  const requestListener = (req, res) => { handler(req, res).catch((error) => { if (!res.headersSent) sendJson(res, 500, errorPayload(error)); else res.destroy(error); }); };
  const server = http.createServer(requestListener);
  // Notice phones that vanished without closing the connection (Wi-Fi lost, app killed).
  server.on('connection', (socket) => socket.setKeepAlive(true, 30_000));
  server.pocket = {
    requestListener,
    vault: store,
    activeRequests: () => active.size,
    abortAll: () => { for (const item of active.values()) item.abort.abort(); },
  };
  return server;
}

export { DEFAULT_CONFIG, MAX_BODY, SERVER_VERSION, modelList, safeToken };
