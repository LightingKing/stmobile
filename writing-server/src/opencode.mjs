import { createHash, randomUUID } from 'node:crypto';
import { mkdir, realpath, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { setTimeout as delay } from 'node:timers/promises';

export class OpenCodeError extends Error {
  constructor(message, code = 'opencode_error', extra = {}) {
    super(message);
    this.name = 'OpenCodeError';
    this.code = code;
    this.status = 502;
    Object.assign(this, extra);
  }
}

// OpenCode 2 permissions are ordered rules. FileAccess separately authorizes
// external_directory, including resolved symlink targets. Shell is denied by
// default: prompt instructions and command-name allowlists are not sandboxes.
export function workspacePermissions() {
  return [
    { action: '*', resource: '*', effect: 'deny' },
    ...['read', 'edit', 'glob', 'grep', 'skill'].map(action => ({ action, resource: '*', effect: 'allow' })),
    ...['.opencode/*', '.agents/*', '.claude/*', '.git/*', 'opencode.json', 'opencode.jsonc'].map(resource => ({ action: 'edit', resource, effect: 'deny' })),
    { action: 'external_directory', resource: '*', effect: 'deny' },
  ];
}

function resolveConfig(config) {
  const options = config?.opencode;
  if (options?.enabled !== true) throw new OpenCodeError('OpenCode 写作代理尚未启用', 'opencode_disabled', { status: 503 });
  if (!String(config.upstreamKey || '').trim()) throw new OpenCodeError('未配置 Venice API Key；不会使用其他 OpenCode 账号', 'opencode_key_missing', { status: 503 });
  let url;
  try { url = new URL(options.baseUrl); } catch { throw new OpenCodeError('OpenCode baseUrl 无效', 'opencode_config'); }
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash || !['', '/'].includes(url.pathname)) {
    throw new OpenCodeError('OpenCode baseUrl 必须是不含凭据和路径的服务根地址', 'opencode_config');
  }
  if (!['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname)) throw new OpenCodeError('OpenCode 只能连接独立的本机回环服务', 'opencode_config');
  if (!options.workspaceRoot || !options.providerID || !options.modelID) throw new OpenCodeError('OpenCode 缺少 workspaceRoot、providerID 或 modelID', 'opencode_config');
  return { ...options, baseUrl: url.origin, pollMs: Math.max(10, Number(options.pollMs) || 750), requestTimeoutMs: Math.max(100, Number(options.requestTimeoutMs) || 10000) };
}

function validateRequest(request, chatId) {
  if (!String(chatId || '').trim()) throw new OpenCodeError('OpenCode 写作代理需要稳定的会话 ID', 'opencode_chat_id', { status: 400 });
  if (!Array.isArray(request?.messages) || !request.messages.length) throw new OpenCodeError('缺少 messages', 'opencode_request', { status: 400 });
  if (request.tools?.length || request.messages.some(m => !['system', 'developer', 'user', 'assistant'].includes(m.role) || typeof m.content !== 'string' || m.tool_calls?.length)) {
    throw new OpenCodeError('OpenCode 模式目前只接收纯文字会话；手机端工具与多模态请求请使用普通模式', 'opencode_unsupported_request', { status: 400 });
  }
  for (const key of ['max_tokens', 'max_completion_tokens', 'temperature', 'top_p', 'stop', 'response_format', 'n']) {
    if (request[key] !== undefined && request[key] !== null) throw new OpenCodeError(`OpenCode 2 prompt API 不支持逐请求 ${key}；请在独立模型配置中设置或使用普通模式`, 'opencode_unsupported_parameter', { status: 400 });
  }
}

export function normalizeMessages(messages, { sessionId, workspace, promptId } = {}) {
  const start = promptId ? messages.findIndex(m => m.id === promptId && m.type === 'user') : -1;
  if (promptId && start < 0) throw new OpenCodeError('OpenCode 未确认本次输入；不会复用上一次回答', 'opencode_prompt_missing');
  const turn = messages.slice(start + 1);
  const assistants = turn.filter(m => m.type === 'assistant');
  const last = assistants.at(-1);
  const text = (m, kind) => (m?.content || []).filter(p => p.type === kind).map(p => p.text || '').join('');
  const content = text(last, 'text');
  const reasoning = assistants.map(m => text(m, 'reasoning')).filter(Boolean).join('\n\n');
  const usage = { prompt_tokens: 0, completion_tokens: 0, total_tokens: 0, completion_tokens_details: { reasoning_tokens: 0 }, prompt_tokens_details: { cached_tokens: 0 } };
  let hasUsage = false;
  for (const m of turn) {
    if (!m.tokens || !['assistant', 'compaction'].includes(m.type)) continue;
    hasUsage = true;
    const finite = value => Number.isFinite(value) && value >= 0 ? value : 0;
    const tokens = m.tokens;
    usage.prompt_tokens += finite(tokens.input) + finite(tokens.cache?.read) + finite(tokens.cache?.write);
    usage.completion_tokens += finite(tokens.output) + finite(tokens.reasoning);
    usage.completion_tokens_details.reasoning_tokens += finite(tokens.reasoning);
    usage.prompt_tokens_details.cached_tokens += finite(tokens.cache?.read);
  }
  usage.total_tokens = usage.prompt_tokens + usage.completion_tokens;
  const finish = ({ 'tool-calls': 'tool_calls', 'content-filter': 'content_filter' })[last?.finish] || last?.finish || 'unknown';
  const result = {
    id: last?.id || `opencode-${sessionId}`, content, reasoning, finish_reason: finish,
    ...(hasUsage ? { usage } : {}),
    message: { role: 'assistant', content, ...(reasoning ? { reasoning_content: reasoning } : {}) },
    opencode: { sessionId, workspace, promptId, steps: assistants.length },
  };
  const failure = assistants.find(m => m.error);
  if (failure || ['error', 'tool_calls'].includes(finish) || turn.some(m => m.type === 'idle' && m.outcome !== 'succeeded')) {
    throw new OpenCodeError('OpenCode 代理未成功结束，已保留可用正文和思考输出', 'opencode_generation_failed', { partial: result });
  }
  if (!content.trim()) throw new OpenCodeError('OpenCode 返回空正文，已保留思考输出与用量', 'opencode_empty_response', { partial: result });
  return result;
}

export function createOpenCodeAdapter({ fetchImpl = globalThis.fetch } = {}) {
  const active = new Set();
  return async function runAgent({ request, chatId, config, signal, onProgress = async () => {} }) {
    const options = resolveConfig(config);
    validateRequest(request, chatId);
    signal?.throwIfAborted();
    const root = path.resolve(options.workspaceRoot);
    const chatKey = createHash('sha256').update(String(chatId)).digest('hex');
    const workspace = path.join(root, chatKey);
    if (active.has(workspace)) throw new OpenCodeError('同一会话的 OpenCode 请求仍在运行', 'opencode_busy', { status: 409 });
    active.add(workspace);
    const controller = new AbortController();
    const relayAbort = () => controller.abort(signal.reason);
    signal?.addEventListener('abort', relayAbort, { once: true });
    if (signal?.aborted) relayAbort();
    const headers = { 'content-type': 'application/json', 'x-opencode-directory': encodeURIComponent(workspace) };
    if (options.password) headers.authorization = `Basic ${Buffer.from(`${options.username || 'opencode'}:${options.password}`).toString('base64')}`;
    let sessionId;
    let promptId;
    let submitted = false;
    let waitTask;
    const api = async (endpoint, { method = 'GET', body, noTimeout = false, cleanup = false } = {}) => {
      const requestSignal = cleanup ? AbortSignal.timeout(5000) : noTimeout ? controller.signal : AbortSignal.any([controller.signal, AbortSignal.timeout(options.requestTimeoutMs)]);
      let response;
      try { response = await fetchImpl(`${options.baseUrl}${endpoint}`, { method, headers, ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: requestSignal }); }
      catch (error) {
        if (controller.signal.aborted && !cleanup) throw controller.signal.reason || error;
        throw new OpenCodeError('无法连接 OpenCode 独立服务；请求未自动重试', 'opencode_connection');
      }
      if (!response.ok) throw new OpenCodeError(`OpenCode API 返回 HTTP ${response.status}`, 'opencode_http_error', { httpStatus: response.status });
      if (response.status === 204) return null;
      try { return await response.json(); } catch { throw new OpenCodeError('OpenCode API 返回了无效 JSON', 'opencode_protocol'); }
    };
    try {
      await mkdir(workspace, { recursive: true });
      if (await realpath(workspace) !== path.join(await realpath(root), chatKey)) throw new OpenCodeError('会话目录不能指向其他目录', 'opencode_workspace');
      const runId = randomUUID();
      // Keep inputs out of the model-editable workspace's internal bookkeeping.
      const archiveRoot = path.join(root, '.requests', chatKey);
      await mkdir(archiveRoot, { recursive: true });
      await writeFile(path.join(archiveRoot, `${runId}.json`), JSON.stringify({ chatId, request }, null, 2), { mode: 0o600, flag: 'wx' });
      const info = await api('/api/info');
      if (!/^2\./.test(info?.version || '')) throw new OpenCodeError('此适配器需要 OpenCode 2 API', 'opencode_version');
      const created = await api('/api/session', { method: 'POST', body: {
        title: `Pocket Tavern ${chatKey.slice(0, 10)} ${runId.slice(0, 8)}`,
        location: { directory: workspace },
        model: { providerID: options.providerID, id: options.modelID },
        ...(options.agent ? { agent: options.agent } : {}),
        permissions: workspacePermissions(),
        metadata: { pocket_tavern_chat_hash: chatKey, pocket_tavern_run_id: runId },
      } });
      sessionId = created?.data?.id;
      if (typeof sessionId !== 'string' || !/^ses[A-Za-z0-9_-]+$/.test(sessionId)) throw new OpenCodeError('OpenCode 未返回有效 session ID', 'opencode_protocol');
      const prefix = `/api/session/${encodeURIComponent(sessionId)}`;
      promptId = `msg_${randomUUID().replaceAll('-', '')}`;
      const prompt = [
        '这是手机写作会话的完整输入快照，按 messages 中的角色顺序继续最后一条用户输入。',
        '此工作目录只属于这个会话。已有文件是可查证的资料；若与当前明确输入冲突，以当前输入为准，不把旧文件推测当事实。',
        '可按需要读取原版写作 skills 和文件记忆。只将最终正文作为最后一条回答，不输出工具日志或过程旁白。不能执行的工具要明确说明。',
        JSON.stringify({ messages: request.messages }),
      ].join('\n\n');
      await onProgress({ stage: 'opencode', status: 'started', sessionId });
      // Set before admission: if transport fails after acceptance, interrupt it.
      submitted = true;
      await api(`${prefix}/prompt`, { method: 'POST', body: { id: promptId, text: prompt, delivery: 'steer' } });
      let settled = false;
      let waitError;
      waitTask = api(`/api/experimental/session/${encodeURIComponent(sessionId)}/wait`, { method: 'POST', noTimeout: true })
        .catch(error => { waitError = error; }).finally(() => { settled = true; });
      do {
        const [permissions, forms, locationForms] = await Promise.all([api(`${prefix}/permission`), api(`${prefix}/form`), api('/api/form')]);
        if (!Array.isArray(permissions?.data) || !Array.isArray(forms?.data) || !Array.isArray(locationForms?.data)) throw new OpenCodeError('OpenCode 权限或表单响应格式不兼容', 'opencode_protocol');
        const globalForms = locationForms.data.filter(f => f.sessionID === 'global');
        if (permissions.data.length || forms.data.length || globalForms.length) {
          await Promise.allSettled([
            ...permissions.data.map(p => api(`${prefix}/permission/${encodeURIComponent(p.id)}/reply`, { method: 'POST', body: { decision: 'reject' }, cleanup: true })),
            ...forms.data.map(f => api(`${prefix}/form/${encodeURIComponent(f.id)}`, { method: 'DELETE', cleanup: true })),
            ...globalForms.map(f => api(`/api/session/global/form/${encodeURIComponent(f.id)}`, { method: 'DELETE', cleanup: true })),
          ]);
          throw new OpenCodeError('代理请求了当前自动模式不支持的权限或人工回答，已停止等待', 'opencode_interaction_required');
        }
        if (!settled) {
          await onProgress({ stage: 'opencode', status: 'running', sessionId });
          await Promise.race([waitTask, delay(options.pollMs, undefined, { signal: controller.signal })]);
        }
      } while (!settled);
      if (waitError) throw waitError;
      const messages = [];
      let cursor;
      const seen = new Set();
      do {
        const query = cursor ? `limit=200&cursor=${encodeURIComponent(cursor)}` : 'limit=200&order=asc';
        const page = await api(`${prefix}/message?${query}`);
        if (!Array.isArray(page?.data)) throw new OpenCodeError('OpenCode 消息格式不兼容', 'opencode_protocol');
        messages.push(...page.data);
        cursor = page.cursor?.next;
        if (cursor && seen.has(cursor)) throw new OpenCodeError('OpenCode 返回重复分页游标', 'opencode_protocol');
        if (cursor) seen.add(cursor);
      } while (cursor);
      const result = normalizeMessages(messages, { sessionId, workspace, promptId });
      await onProgress({ stage: 'opencode', status: 'complete', sessionId });
      return result;
    } catch (error) {
      if (submitted && sessionId) await api(`/api/session/${encodeURIComponent(sessionId)}/interrupt`, { method: 'POST', cleanup: true }).catch(() => {});
      // Cancellation and transport failures can follow already billed output.
      // Recover the latest projected tail without resuming or retrying a prompt.
      if (submitted && sessionId && !error.partial) {
        try {
          const page = await api(`/api/session/${encodeURIComponent(sessionId)}/message?limit=200&order=desc`, { cleanup: true });
          if (Array.isArray(page?.data)) {
            const tail = page.data.slice().reverse();
            const boundaryPresent = tail.some(m => m.id === promptId);
            let partial;
            try { partial = normalizeMessages(tail, { sessionId, workspace, ...(boundaryPresent ? { promptId } : {}) }); }
            catch (projectionError) { partial = projectionError.partial; }
            if (partial) error.partial = { ...partial, opencode: { ...partial.opencode, recoveredPartial: true, usageComplete: !page.cursor?.next } };
          }
        } catch { /* The input archive and OpenCode session remain recoverable. */ }
      }
      throw error;
    } finally {
      controller.abort();
      // The wait promise catches its own rejection; aborting it never discards a
      // successful generation and never leaves an unhandled rejection behind.
      signal?.removeEventListener('abort', relayAbort);
      active.delete(workspace);
    }
  };
}

export const runAgent = createOpenCodeAdapter();
