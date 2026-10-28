import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createServer } from '../src/server.mjs';
import { createVault } from '../src/vault.mjs';
import { UpstreamError } from '../src/upstream.mjs';

async function start() {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'pocket-server-'));
  const calls = [];
  const server = createServer({
    config: { token: 'test-token', contextTokens: 123456, dataDir },
    vault: createVault({ dataDir }),
    upstream: { complete: async ({ body }) => { calls.push(body); return { id: 'answer', content: '最终正文', reasoning: '真实思考', tool_calls: [], finish_reason: 'stop', usage: { total_tokens: 2 } }; } },
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const address = server.address();
  return { server, calls, base: `http://127.0.0.1:${address.port}` };
}

test('server authenticates models and returns configured model capabilities', async () => {
  const { server, base } = await start();
  try {
    assert.equal((await fetch(`${base}/v1/models`)).status, 401);
    const response = await fetch(`${base}/v1/models`, { headers: { authorization: 'Bearer test-token' } });
    assert.equal(response.status, 200);
    const data = await response.json();
    assert.equal(data.data.length, 2);
    assert.equal(data.data[1].context_length, 123456);
    assert.equal(data.data[1].supports_tools, true);
  } finally { await new Promise((resolve) => server.close(resolve)); }
});

test('server streams one legitimate final delta and persists per-chat run', async () => {
  const { server, base, calls } = await start();
  try {
    const response = await fetch(`${base}/v1/chat/completions`, {
      method: 'POST', headers: { authorization: 'Bearer test-token', 'content-type': 'application/json' },
      body: JSON.stringify({ model: 'pocket-direct', stream: true, messages: [{ role: 'user', content: '写' }], metadata: { pocket_tavern_chat_id: 'chat-1', pocket_tavern_purpose: 'chat' } }),
    });
    assert.equal(response.status, 200);
    const text = await response.text();
    assert.match(text, /最终正文/);
    assert.match(text, /reasoning_content/);
    assert.equal(calls[0].model, 'deepseek-v4-1-flash-1017');
    assert.equal(calls[0].stream, true);
    const runs = await fetch(`${base}/v1/runs?chat_id=chat-1`, { headers: { authorization: 'Bearer test-token' } });
    const summaries = await runs.json();
    assert.equal(summaries.length, 1);
    assert.equal('request' in summaries[0], false);
  } finally { await new Promise((resolve) => server.close(resolve)); }
});

test('server rejects writer calls without chat metadata', async () => {
  const { server, base } = await start();
  try {
    const response = await fetch(`${base}/v1/chat/completions`, { method: 'POST', headers: { authorization: 'Bearer test-token', 'content-type': 'application/json' }, body: JSON.stringify({ model: 'pocket-writer', messages: [] }) });
    assert.equal(response.status, 400);
  } finally { await new Promise((resolve) => server.close(resolve)); }
});

test('server stays inspectable but not ready when upstream key is missing', async () => {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'pocket-not-ready-'));
  const server = createServer({ config: { token: 'test-token', dataDir } });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    const health = await fetch(`${base}/healthz`);
    assert.equal((await health.json()).ready, false);
    const response = await fetch(`${base}/v1/chat/completions`, { method: 'POST', headers: { authorization: 'Bearer test-token', 'content-type': 'application/json' }, body: JSON.stringify({ model: 'pocket-direct', messages: [] }) });
    assert.equal(response.status, 503);
  } finally { await new Promise((resolve) => server.close(resolve)); }
});

test('streaming upstream failure sends received partial before error', async () => {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'pocket-partial-'));
  const server = createServer({
    config: { token: 'test-token', dataDir },
    vault: createVault({ dataDir }),
    upstream: { complete: async () => { throw new UpstreamError('上游提前断流', { code: 'truncated_stream', partial: { id: 'partial', content: '已收到正文', reasoning: '已收到思考', tool_calls: [], finish_reason: '', usage: { total_tokens: 2 } } }); } },
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    const response = await fetch(`${base}/v1/chat/completions`, { method: 'POST', headers: { authorization: 'Bearer test-token', 'content-type': 'application/json' }, body: JSON.stringify({ model: 'pocket-direct', stream: true, messages: [{ role: 'user', content: '继续' }] }) });
    const text = await response.text();
    assert.match(text, /已收到正文/);
    assert.match(text, /已收到思考/);
    assert.match(text, /truncated_stream/);
  } finally { await new Promise((resolve) => server.close(resolve)); }
});

test('non-streaming upstream failure carries partial without declaring completion', async () => {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'pocket-partial-json-'));
  const server = createServer({
    config: { token: 'test-token', dataDir },
    vault: createVault({ dataDir }),
    upstream: { complete: async () => { throw new UpstreamError('上游失败', { code: 'stream_error', partial: { id: 'partial-json', content: '部分正文', reasoning: '', tool_calls: [], finish_reason: '' } }); } },
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    const response = await fetch(`${base}/v1/chat/completions`, { method: 'POST', headers: { authorization: 'Bearer test-token', 'content-type': 'application/json' }, body: JSON.stringify({ model: 'pocket-direct', stream: false, messages: [{ role: 'user', content: '继续' }] }) });
    assert.equal(response.status, 502);
    const body = await response.json();
    assert.equal(body.partial.content, '部分正文');
    assert.equal(body.choices, undefined);
  } finally { await new Promise((resolve) => server.close(resolve)); }
});

test('audit-only writer notice never enters story content', async () => {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'pocket-server-'));
  let count = 0;
  const server = createServer({
    config: { token: 'test-token', contextTokens: 123456, dataDir, autoRevise: false },
    vault: createVault({ dataDir }),
    upstream: { complete: async () => {
      count++;
      return { id: `x${count}`, content: count % 2 === 1 ? '原始草稿' : '{"issues":[{"problem":"可能误判","draft_quote":"原始草稿","input_evidence":"继续","fix":"不要自动采用"}]}', reasoning: '', finish_reason: 'stop', tool_calls: [] };
    } },
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  const request = (stream, chatId) => fetch(`${base}/v1/chat/completions`, {
    method: 'POST', headers: { authorization: 'Bearer test-token', 'content-type': 'application/json' },
    body: JSON.stringify({ model: 'pocket-writer', stream, messages: [{ role: 'user', content: '继续' }], metadata: { pocket_tavern_chat_id: chatId, pocket_tavern_purpose: 'chat' } }),
  });
  try {
    const streamed = await request(true, 'notice-stream');
    assert.equal(streamed.status, 200);
    const chunks = (await streamed.text()).split('\n\n')
      .filter((line) => line.startsWith('data: {')).map((line) => JSON.parse(line.slice(6)));
    const content = chunks.map((chunk) => chunk.choices?.[0]?.delta?.content || '').join('');
    assert.equal(count, 2);
    assert.equal(content, '原始草稿');
    assert.match(chunks.at(-1).pocket_tavern_notice, /写作审校/);
    assert.equal(chunks.at(-1).pocket_tavern_warning, 'experimental_audit_only');

    const plain = await (await request(false, 'notice-json')).json();
    assert.equal(plain.choices[0].message.content, '原始草稿');
    assert.match(plain.pocket_tavern_notice, /写作审校/);
  } finally { await new Promise((resolve) => server.close(resolve)); }
});
