import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, readdir, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createOpenCodeAdapter, normalizeMessages, workspacePermissions } from '../src/opencode.mjs';

const json = data => new Response(JSON.stringify(data), { headers: { 'content-type': 'application/json' } });
const noContent = () => new Response(null, { status: 204 });
const request = { messages: [{ role: 'system', content: '保留事实。' }, { role: 'user', content: '继续。' }] };

async function fixture(t, settings = {}) {
  const root = await mkdtemp(path.join(os.tmpdir(), 'pocket-opencode-test-'));
  t.after(() => rm(root, { recursive: true, force: true }));
  const calls = [];
  const sessions = new Map();
  let count = 0;
  const fetchImpl = async (url, init) => {
    const target = new URL(url);
    const body = init.body && JSON.parse(init.body);
    calls.push({ path: target.pathname, query: target.search, method: init.method, body, authorization: init.headers.authorization });
    const sessionId = target.pathname.match(/\/session\/([^/]+)/)?.[1];
    const session = sessions.get(sessionId);
    const custom = await settings.handle?.({ target, init, body, session, sessions, calls });
    if (custom) return custom;
    if (target.pathname === '/api/info') return json({ version: '2.0.11' });
    if (target.pathname === '/api/session') {
      const id = `ses_test${++count}`;
      sessions.set(id, { create: body });
      return json({ data: { id } });
    }
    if (target.pathname.endsWith('/prompt')) {
      session.prompt = body;
      return json({ data: { id: body.id } });
    }
    if (target.pathname.endsWith('/wait')) return noContent();
    if (target.pathname.endsWith('/permission') || target.pathname.endsWith('/form')) return json({ data: [] });
    if (target.pathname.endsWith('/interrupt')) return json({ interrupted: true });
    if (target.pathname.endsWith('/message')) return json({ data: [
      { id: session.prompt.id, type: 'user', text: session.prompt.text },
      { id: 'msg_answer', type: 'assistant', finish: 'stop', content: [{ type: 'reasoning', text: '检查已有事实。' }, { type: 'text', text: '这是最后的正文。' }], tokens: { input: 100, output: 20, reasoning: 30, cache: { read: 4, write: 5 } } },
      { id: 'msg_idle', type: 'idle', outcome: 'succeeded' },
    ], cursor: {} });
    throw new Error(`Unhandled mock endpoint ${target.pathname}`);
  };
  const config = { upstreamKey: 'fixture-key-only', opencode: { enabled: true, baseUrl: 'http://127.0.0.1:4896', workspaceRoot: root, providerID: 'venice', modelID: 'deepseek-v4-1-flash-1017', pollMs: 10, requestTimeoutMs: 500 } };
  return { root, calls, sessions, config, run: createOpenCodeAdapter({ fetchImpl }) };
}

test('OpenCode 2 routes, explicit model, reasoning and cumulative usage are normalized', async t => {
  const f = await fixture(t);
  const result = await f.run({ request, chatId: 'chat-a', config: f.config });
  assert.equal(result.content, '这是最后的正文。');
  assert.equal(result.reasoning, '检查已有事实。');
  assert.equal(result.message.reasoning_content, result.reasoning);
  assert.deepEqual(result.usage, { prompt_tokens: 109, completion_tokens: 50, total_tokens: 159, completion_tokens_details: { reasoning_tokens: 30 }, prompt_tokens_details: { cached_tokens: 4 } });
  assert.deepEqual(f.calls.find(c => c.path === '/api/session').body.model, { providerID: 'venice', id: f.config.opencode.modelID });
  assert(f.calls.some(c => c.path === '/api/experimental/session/ses_test1/wait'));
  assert(!JSON.stringify(f.calls).includes(f.config.upstreamKey));
});

test('chat isolation, hostile chat IDs and fresh sessions preserve one workspace without duplicate histories', async t => {
  const f = await fixture(t);
  const a = await f.run({ request, chatId: '../../outside', config: f.config });
  const b = await f.run({ request, chatId: 'chat-b', config: f.config });
  const c = await f.run({ request, chatId: '../../outside', config: f.config });
  assert.notEqual(a.opencode.workspace, b.opencode.workspace);
  assert.equal(a.opencode.workspace, c.opencode.workspace);
  assert.notEqual(a.opencode.sessionId, c.opencode.sessionId);
  assert.equal(path.dirname(a.opencode.workspace), f.root);
  const entries = await readdir(path.join(f.root, '.requests', path.basename(a.opencode.workspace)));
  assert.equal(entries.length, 2);
  const archived = JSON.parse(await readFile(path.join(f.root, '.requests', path.basename(a.opencode.workspace), entries[0]), 'utf8'));
  assert.deepEqual(archived.request.messages, request.messages);
});

test('disabled / missing key / remote endpoint / unsupported inputs fail before network', async t => {
  const f = await fixture(t);
  const invoke = (config, input = request) => f.run({ request: input, chatId: 'a', config });
  await assert.rejects(invoke({ ...f.config, opencode: { ...f.config.opencode, enabled: false } }), { code: 'opencode_disabled' });
  await assert.rejects(invoke({ ...f.config, upstreamKey: '' }), { code: 'opencode_key_missing' });
  await assert.rejects(invoke({ ...f.config, opencode: { ...f.config.opencode, baseUrl: 'http://example.com' } }), { code: 'opencode_config' });
  await assert.rejects(invoke(f.config, { ...request, tools: [{ type: 'function' }] }), { code: 'opencode_unsupported_request' });
  await assert.rejects(invoke(f.config, { ...request, max_tokens: 10000 }), { code: 'opencode_unsupported_parameter' });
  assert.equal(f.calls.length, 0);
});

test('permission request is rejected and session interrupted instead of hanging', async t => {
  const f = await fixture(t, { handle: ({ target, init }) => {
    if (target.pathname.endsWith('/wait')) return new Promise((_, reject) => init.signal.addEventListener('abort', () => reject(init.signal.reason), { once: true }));
    if (target.pathname.endsWith('/permission')) return json({ data: [{ id: 'per_request', action: 'shell', resources: ['python'] }] });
    if (target.pathname.endsWith('/reply')) return noContent();
  } });
  await assert.rejects(f.run({ request, chatId: 'a', config: f.config }), { code: 'opencode_interaction_required' });
  assert.equal(f.calls.find(c => c.path.endsWith('/reply')).body.decision, 'reject');
  assert(f.calls.some(c => c.path.endsWith('/interrupt')));
});

test('human question form is cancelled explicitly', async t => {
  const f = await fixture(t, { handle: ({ target, init }) => {
    if (target.pathname.endsWith('/form')) return json({ data: [{ id: 'frm_question' }] });
    if (target.pathname.endsWith('/frm_question') && init.method === 'DELETE') return noContent();
  } });
  await assert.rejects(f.run({ request, chatId: 'a', config: f.config }), { code: 'opencode_interaction_required' });
  assert(f.calls.some(c => c.path.endsWith('/frm_question') && c.method === 'DELETE'));
});

test('location global authentication forms cannot silently stall an unattended run', async t => {
  const f = await fixture(t, { handle: ({ target, init }) => {
    if (target.pathname === '/api/form') return json({ data: [{ id: 'frm_auth', sessionID: 'global' }] });
    if (target.pathname === '/api/session/global/form/frm_auth' && init.method === 'DELETE') return noContent();
  } });
  await assert.rejects(f.run({ request, chatId: 'a', config: f.config }), { code: 'opencode_interaction_required' });
  assert(f.calls.some(c => c.path === '/api/session/global/form/frm_auth' && c.method === 'DELETE'));
});

test('cancelled generation recovers already streamed reasoning and partial text without retry', async t => {
  const abort = new AbortController();
  const f = await fixture(t, { handle: ({ target, init, session }) => {
    if (target.pathname.endsWith('/wait')) {
      setImmediate(() => abort.abort());
      return new Promise((_, reject) => init.signal.addEventListener('abort', () => reject(init.signal.reason), { once: true }));
    }
    if (target.pathname.endsWith('/message')) return json({ data: [
      { type: 'assistant', id: 'msg_partial', content: [{ type: 'reasoning', text: '已收到的思考' }, { type: 'text', text: '正文片段' }] },
      { type: 'user', id: session.prompt.id },
    ], cursor: {} });
  } });
  await assert.rejects(f.run({ request, chatId: 'a', config: f.config, signal: abort.signal }), error => error.name === 'AbortError' && error.partial.content === '正文片段' && error.partial.reasoning === '已收到的思考' && error.partial.opencode.recoveredPartial);
  assert.equal(f.calls.filter(c => c.path.endsWith('/prompt')).length, 1);
});

test('user cancellation interrupts only the allocated session and releases the chat lock', async t => {
  let admit;
  const admitted = new Promise(resolve => { admit = resolve; });
  let hold = true;
  const f = await fixture(t, { handle: ({ target, init }) => {
    if (target.pathname.endsWith('/wait') && hold) {
      admit();
      return new Promise((_, reject) => init.signal.addEventListener('abort', () => reject(init.signal.reason), { once: true }));
    }
  } });
  const abort = new AbortController();
  const running = f.run({ request, chatId: 'a', config: f.config, signal: abort.signal });
  await admitted;
  await assert.rejects(f.run({ request, chatId: 'a', config: f.config }), { code: 'opencode_busy' });
  abort.abort();
  await assert.rejects(running, { name: 'AbortError' });
  assert.equal(f.calls.filter(c => c.path.endsWith('/interrupt')).length, 1);
  hold = false;
  await f.run({ request, chatId: 'a', config: f.config });
});

test('admission transport uncertainty is interrupted without automatic generation retry', async t => {
  const f = await fixture(t, { handle: ({ target }) => {
    if (target.pathname.endsWith('/prompt')) throw new Error('transport disconnected');
  } });
  await assert.rejects(f.run({ request, chatId: 'a', config: f.config }), { code: 'opencode_connection' });
  assert.equal(f.calls.filter(c => c.path.endsWith('/prompt')).length, 1);
  assert(f.calls.some(c => c.path.endsWith('/interrupt')));
});

test('HTTP errors do not expose upstream or local authentication response bodies', async t => {
  const f = await fixture(t, { handle: ({ target }) => target.pathname.endsWith('/prompt') ? new Response('secret-account-key', { status: 401 }) : undefined });
  await assert.rejects(f.run({ request, chatId: 'a', config: f.config }), error => error.code === 'opencode_http_error' && !error.message.includes('secret'));
});

test('pagination retains prompt boundary and final answer rather than intermediate tool narration', async t => {
  const f = await fixture(t, { handle: ({ target, session }) => {
    if (!target.pathname.endsWith('/message')) return;
    return target.searchParams.has('cursor') ? json({ data: [
      { type: 'assistant', id: 'msg_final', finish: 'stop', content: [{ type: 'text', text: '最终答案' }] },
    ], cursor: {} }) : json({ data: [
      { type: 'assistant', id: 'msg_old', content: [{ type: 'text', text: '旧答案' }] },
      { type: 'user', id: session.prompt.id },
      { type: 'assistant', id: 'msg_tool', finish: 'tool-calls', content: [{ type: 'text', text: '正在查看文件' }, { type: 'tool', state: { status: 'completed' } }] },
    ], cursor: { next: 'opaque/page2' } });
  } });
  const result = await f.run({ request, chatId: 'a', config: f.config });
  assert.equal(result.content, '最终答案');
  assert.equal(result.opencode.steps, 2);
});

test('empty body with reasoning and provider errors keep partial output and billable usage', () => {
  const messages = [{ type: 'assistant', id: 'msg_failure', finish: 'error', error: { message: 'unsafe provider details' }, content: [{ type: 'reasoning', text: '已有思考' }], tokens: { input: 50, output: 0, reasoning: 10, cache: { read: 0, write: 0 } } }];
  assert.throws(() => normalizeMessages(messages), error => error.code === 'opencode_generation_failed' && error.partial.reasoning === '已有思考' && error.partial.usage.total_tokens === 60 && !error.message.includes('unsafe'));
  delete messages[0].error;
  messages[0].finish = 'stop';
  assert.throws(() => normalizeMessages(messages), { code: 'opencode_empty_response' });
});

test('strict allowlist enables files but does not silently permit shell, network, task or external directories', () => {
  const rules = workspacePermissions();
  const allowed = rules.filter(r => r.effect === 'allow').map(r => r.action);
  assert.deepEqual(allowed, ['read', 'edit', 'glob', 'grep', 'skill']);
  assert.equal(rules.at(-1).action, 'external_directory');
  assert.equal(rules.at(-1).effect, 'deny');
});
