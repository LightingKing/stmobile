import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createServer } from '../src/server.mjs';
import { createVault } from '../src/vault.mjs';
import { UpstreamError } from '../src/upstream.mjs';
import { createAuthGuard } from '../src/guard.mjs';

async function listen(options) {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'pocket-live-'));
  const server = createServer({ ...options, config: { token: 'test-token', dataDir, ...(options.config || {}) }, vault: createVault({ dataDir }) });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  const close = () => { server.closeAllConnections(); return new Promise((resolve) => server.close(resolve)); };
  return { server, base, close };
}

const auth = { authorization: 'Bearer test-token', 'content-type': 'application/json' };
const chatBody = (model, chatId, purpose = 'chat') => JSON.stringify({ model, stream: true, messages: [{ role: 'user', content: '写' }], metadata: { pocket_tavern_chat_id: chatId, pocket_tavern_purpose: purpose } });
const post = (base, body) => fetch(`${base}/v1/chat/completions`, { method: 'POST', headers: auth, body });
const frames = (text) => text.split('\n\n').filter((line) => line.startsWith('data: {')).map((line) => JSON.parse(line.slice(6)));
const joined = (chunks, key) => chunks.map((chunk) => chunk.choices?.[0]?.delta?.[key] || '').join('');
const waitForAbort = ({ signal }) => new Promise((_resolve, reject) => {
  signal.addEventListener('abort', () => reject(Object.assign(new Error('aborted'), { name: 'AbortError' })), { once: true });
});

async function readUntil(reader, pattern) {
  const decoder = new TextDecoder();
  let text = '';
  while (!pattern.test(text)) {
    const { value, done } = await reader.read();
    if (done) break;
    text += decoder.decode(value, { stream: true });
  }
  return text;
}

test('direct replies reach the phone while the upstream is still generating', async () => {
  let release;
  const gate = new Promise((resolve) => { release = resolve; });
  const { base, close } = await listen({ upstream: { complete: async ({ onProgress }) => {
    onProgress({ reasoning: '先想', content: '' });
    onProgress({ reasoning: '先想', content: '第一句。' });
    await gate;
    onProgress({ reasoning: '先想', content: '第一句。第二句。' });
    return { id: 'u', content: '第一句。第二句。', reasoning: '先想', tool_calls: [], finish_reason: 'stop', usage: { total_tokens: 9 } };
  } } });
  try {
    const reader = (await post(base, chatBody('pocket-direct', 'live-1'))).body.getReader();
    let text = await readUntil(reader, /第一句/);
    assert.match(text, /第一句/, 'the first sentence arrives before the upstream finishes');
    assert.doesNotMatch(text, /\[DONE\]/);
    release();
    text += await readUntil(reader, /\[DONE\]/);
    const chunks = frames(text);
    assert.equal(joined(chunks, 'content'), '第一句。第二句。');
    assert.equal(joined(chunks, 'reasoning_content'), '先想');
    assert.equal(chunks.filter((chunk) => chunk.choices?.[0]?.delta?.role).length, 1);
    assert.equal(new Set(chunks.map((chunk) => chunk.id)).size, 1);
    assert.equal(chunks.at(-1).choices[0].finish_reason, 'stop');
    assert.equal(chunks.at(-1).usage.total_tokens, 9);
  } finally { await close(); }
});

test('writer streams its draft live and appends only the review reasoning at the end', async () => {
  const live = [];
  const { base, close } = await listen({ config: { autoRevise: false }, upstream: { complete: async ({ onProgress }) => {
    live.push(Boolean(onProgress));
    if (live.length === 1) {
      onProgress?.({ reasoning: '草稿思考', content: '原始草稿' });
      return { id: 'd', content: '原始草稿', reasoning: '草稿思考', tool_calls: [], finish_reason: 'stop' };
    }
    return { id: 'a', content: '{"issues":[]}', reasoning: '审校思考', tool_calls: [], finish_reason: 'stop' };
  } } });
  try {
    const chunks = frames(await (await post(base, chatBody('pocket-writer', 'live-writer'))).text());
    assert.deepEqual(live, [true, false], 'only the draft is forwarded live');
    assert.equal(joined(chunks, 'content'), '原始草稿');
    const reasoning = joined(chunks, 'reasoning_content');
    assert.ok(reasoning.startsWith('草稿思考'));
    assert.match(reasoning, /审校思考/);
    assert.equal(reasoning.split('草稿思考').length, 2, 'the draft reasoning is sent once');
  } finally { await close(); }
});

test('auto-revision keeps the draft off the wire until the final text is known', async () => {
  const live = [];
  const { base, close } = await listen({ config: { autoRevise: true }, upstream: { complete: async ({ onProgress }) => {
    live.push(Boolean(onProgress));
    return { id: 'x', content: live.length === 1 ? '草稿' : '{"issues":[]}', reasoning: '', tool_calls: [], finish_reason: 'stop' };
  } } });
  try {
    await (await post(base, chatBody('pocket-writer', 'revise'))).text();
    assert.deepEqual(live, [false, false]);
  } finally { await close(); }
});

test('a stream that fails midway sends only the unsent part of the partial', async () => {
  const { base, close } = await listen({ upstream: { complete: async ({ onProgress }) => {
    onProgress({ reasoning: '', content: '前半' });
    throw new UpstreamError('上游 3 秒没有返回任何数据', { code: 'upstream_idle_timeout', partial: { id: 'p', content: '前半后半', reasoning: '', tool_calls: [], finish_reason: '' } });
  } } });
  try {
    const chunks = frames(await (await post(base, chatBody('pocket-direct', 'fail-mid'))).text());
    assert.equal(joined(chunks, 'content'), '前半后半');
    assert.equal(chunks.at(-1).error.code, 'upstream_idle_timeout');
  } finally { await close(); }
});

test('a repeated request for the same chat replaces the stuck one instead of answering 409', async () => {
  let calls = 0;
  const { base, close } = await listen({ upstream: { complete: async (args) => {
    calls++;
    if (calls === 1) return waitForAbort(args);
    return { id: 'b', content: '第二次', reasoning: '', tool_calls: [], finish_reason: 'stop' };
  } } });
  try {
    const firstReader = (await post(base, chatBody('pocket-direct', 'same-chat'))).body.getReader();
    await readUntil(firstReader, /stage=direct/);
    const second = await post(base, chatBody('pocket-direct', 'same-chat'));
    assert.equal(second.status, 200);
    assert.equal(joined(frames(await second.text()), 'content'), '第二次');
    assert.match(await readUntil(firstReader, /superseded/), /superseded/);
  } finally { await close(); }
});

test('a summary for the same chat runs beside the reply', async () => {
  let calls = 0;
  const { base, close } = await listen({ upstream: { complete: async (args) => {
    calls++;
    if (calls === 1) return waitForAbort(args);
    return { id: 's', content: '摘要', reasoning: '', tool_calls: [], finish_reason: 'stop' };
  } } });
  try {
    const replyReader = (await post(base, chatBody('pocket-direct', 'both'))).body.getReader();
    await readUntil(replyReader, /stage=direct/);
    const summary = await post(base, chatBody('pocket-direct', 'both', 'summary'));
    assert.equal(joined(frames(await summary.text()), 'content'), '摘要');
    await replyReader.cancel();
  } finally { await close(); }
});

test('a phone that disconnects frees its chat right away', async () => {
  const { server, base, close } = await listen({ upstream: { complete: waitForAbort } });
  try {
    const controller = new AbortController();
    const response = await fetch(`${base}/v1/chat/completions`, { method: 'POST', headers: auth, body: chatBody('pocket-direct', 'gone'), signal: controller.signal });
    await readUntil(response.body.getReader(), /stage=direct/);
    assert.equal(server.pocket.activeRequests(), 1);
    controller.abort();
    for (let i = 0; i < 50 && server.pocket.activeRequests() > 0; i++) await new Promise((resolve) => setTimeout(resolve, 20));
    assert.equal(server.pocket.activeRequests(), 0);
  } finally { await close(); }
});

test('wrong tokens are throttled per source while missing headers never block', async () => {
  const { base, close } = await listen({ guard: createAuthGuard({ limit: 3 }), upstream: { complete: async () => ({}) } });
  const models = (headers) => fetch(`${base}/v1/models`, { headers });
  try {
    for (let i = 0; i < 10; i++) assert.equal((await models({})).status, 401);
    assert.equal((await models(auth)).status, 200);
    for (let i = 0; i < 3; i++) assert.equal((await models({ authorization: 'Bearer wrong' })).status, 401);
    const blocked = await models(auth);
    assert.equal(blocked.status, 429, 'a blocked source is refused without checking its token');
    assert.ok(Number(blocked.headers.get('retry-after')) > 0);
    assert.equal((await models({ ...auth, 'x-forwarded-for': '203.0.113.9' })).status, 200, 'another client behind a local tunnel keeps working');
  } finally { await close(); }
});

test('status reports service health and backups without secrets', async () => {
  const { base, close } = await listen({ config: { upstreamKey: 'venice-secret-key', reviewSkillSource: { id: 'ss-stop-slop' } } });
  try {
    assert.equal((await fetch(`${base}/v1/pocket/status`)).status, 401);
    const text = await (await fetch(`${base}/v1/pocket/status`, { headers: auth })).text();
    const status = JSON.parse(text);
    assert.equal(status.ready, true);
    assert.equal(status.upstream.host, 'api.venice.ai');
    assert.equal(status.upstream.idle_timeout_seconds, 180);
    assert.equal(status.review_skill, 'ss-stop-slop');
    assert.equal(status.backups.count, 0);
    assert.equal(status.client_address, '127.0.0.1');
    assert.match(status.version, /^\d+\.\d+\.\d+$/);
    assert.ok(status.features.includes('backups'));
    assert.doesNotMatch(text, /venice-secret-key|test-token/);
  } finally { await close(); }
});

test('backups upload, list, download and delete over HTTP', async () => {
  const { base, close } = await listen({ upstream: { complete: async () => ({}) } });
  const zip = Buffer.concat([Buffer.from([0x50, 0x4b, 0x03, 0x04]), Buffer.from('备份内容')]);
  const upload = (bytes, type = 'application/zip') => fetch(`${base}/v1/backups`, { method: 'POST', body: bytes,
    headers: { authorization: 'Bearer test-token', 'content-type': type, 'x-pocket-device': 'Phone', 'x-pocket-app-version': '0.1.68', 'x-pocket-backup-reason': 'auto' } });
  try {
    assert.equal((await fetch(`${base}/v1/backups`, { method: 'POST', headers: { 'content-type': 'application/zip' }, body: zip })).status, 401);
    assert.equal((await upload(zip, 'application/json')).status, 415);
    assert.equal((await upload(Buffer.from('plain text'))).status, 415);
    const created = await upload(zip);
    assert.equal(created.status, 201);
    const meta = await created.json();
    assert.equal(meta.device, 'Phone');
    assert.equal(meta.reason, 'auto');
    const duplicate = await upload(zip);
    assert.equal(duplicate.status, 200);
    assert.equal((await duplicate.json()).duplicate, true);
    const list = await (await fetch(`${base}/v1/backups`, { headers: auth })).json();
    assert.deepEqual(list.data.map((item) => item.id), [meta.id]);
    const download = await fetch(`${base}/v1/backups/${meta.id}`, { headers: auth });
    assert.equal(download.headers.get('content-type'), 'application/zip');
    assert.equal(download.headers.get('x-pocket-sha256'), meta.sha256);
    assert.deepEqual(Buffer.from(await download.arrayBuffer()), zip);
    assert.equal((await fetch(`${base}/v1/backups/..%2Fconfig`, { headers: auth })).status, 404);
    assert.equal((await fetch(`${base}/v1/backups/${meta.id}`, { method: 'DELETE', headers: auth })).status, 200);
    assert.equal((await fetch(`${base}/v1/backups/${meta.id}`, { headers: auth })).status, 404);
    assert.equal((await fetch(`${base}/v1/models`, { headers: auth })).status, 200, 'requests keep working after an upload');
  } finally { await close(); }
});
