import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { createServer } from '../src/server.mjs';
import { createVault } from '../src/vault.mjs';
import { createCatalog } from '../src/catalog.mjs';
import { createUpstream } from '../src/upstream.mjs';

const DEEPSEEK = { id: 'deepseek-v4-1-flash-1017', type: 'text', object: 'model', model_spec: { name: 'DeepSeek V4.1 Flash', availableContextTokens: 1000000,
  capabilities: { supportsFunctionCalling: true, supportsReasoning: true, supportsVision: false }, pricing: { input: { usd: 0.3 }, output: { usd: 1.2 } } } };
const LLAMA = { id: 'llama-3.3-70b', type: 'text', object: 'model', model_spec: { name: 'Llama 3.3 70B', availableContextTokens: 65536,
  capabilities: { supportsFunctionCalling: true, supportsReasoning: false, supportsVision: false }, pricing: { input: { usd: 0.7 }, output: { usd: 2.8 } } } };

const auth = { authorization: 'Bearer test-token', 'content-type': 'application/json' };

async function listen({ catalogModels = [DEEPSEEK, LLAMA], failCatalog = false, config = {} } = {}) {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'pocket-models-'));
  await fs.writeFile(path.join(dataDir, 'config.json'), JSON.stringify({ host: '127.0.0.1', port: 8791, upstreamModel: DEEPSEEK.id, lanMode: false, contextTokens: 1000000 }));
  const calls = [];
  const upstream = {
    models: async () => { if (failCatalog) throw new Error('上游维护中'); return catalogModels; },
    complete: async ({ body }) => { calls.push(body); return { id: 'u', content: `来自 ${body.model}`, reasoning: '', tool_calls: [], finish_reason: 'stop' }; },
  };
  const server = createServer({ config: { token: 'test-token', upstreamKey: 'venice-secret-key', dataDir, reasoningEffort: 'high', backupKeep: 20, ...config },
    vault: createVault({ dataDir }), upstream, catalog: createCatalog({ upstream }) });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  const close = () => { server.closeAllConnections(); return new Promise((resolve) => server.close(resolve)); };
  const get = async (route) => (await fetch(`${base}${route}`, { headers: auth })).json();
  const patch = (settings) => fetch(`${base}/v1/pocket/config`, { method: 'PATCH', headers: auth, body: JSON.stringify({ settings }) });
  const chat = (model) => fetch(`${base}/v1/chat/completions`, { method: 'POST', headers: auth,
    body: JSON.stringify({ model, stream: false, messages: [{ role: 'user', content: '你好' }], metadata: { pocket_tavern_chat_id: `c-${model}`, pocket_tavern_purpose: 'chat' } }) });
  return { base, close, calls, dataDir, get, patch, chat };
}

test('the model list offers the default-model aliases and every upstream text model', async () => {
  const pc = await listen();
  try {
    const list = await pc.get('/v1/models');
    assert.deepEqual(list.data.map((model) => model.id), ['pocket-direct', 'pocket-writer', DEEPSEEK.id, LLAMA.id]);
    const [direct, writer, , llama] = list.data;
    assert.equal(direct.name, '默认模型 · DeepSeek V4.1 Flash');
    assert.match(writer.name, /审校建议/);
    assert.equal(direct.context_length, 1000000);
    assert.equal(direct.supports_reasoning, true);
    assert.deepEqual(direct.pricing, DEEPSEEK.model_spec.pricing);
    assert.deepEqual(llama.model_spec, LLAMA.model_spec, 'upstream entries keep their own context, capabilities and prices');
    assert.deepEqual(llama.upstream, { host: 'api.venice.ai', model: LLAMA.id });
    assert.doesNotMatch(JSON.stringify(list), /venice-secret-key|test-token/);
  } finally { await pc.close(); }
});

test('an unreachable upstream catalog still leaves the aliases usable', async () => {
  const pc = await listen({ failCatalog: true });
  try {
    const list = await pc.get('/v1/models');
    assert.deepEqual(list.data.map((model) => model.id), ['pocket-direct', 'pocket-writer']);
    assert.match(list.pocket_tavern_warning, /上游维护中/);
    assert.equal((await pc.get('/v1/pocket/status')).models_available, null);
  } finally { await pc.close(); }
});

test('the catalog keeps its last good list when a refresh fails', async () => {
  let fail = false;
  const catalog = createCatalog({ upstream: { models: async () => { if (fail) throw new Error('down'); return [LLAMA]; } }, ttlMs: 0 });
  assert.equal((await catalog.list()).models.length, 1);
  fail = true;
  const again = await catalog.list();
  assert.equal(again.models.length, 1);
  assert.equal(again.error, 'down');
});

test('any upstream model id is sent as is, and non-reasoning models get no reasoning settings', async () => {
  const pc = await listen();
  try {
    await pc.get('/v1/models');
    const llama = await (await pc.chat(LLAMA.id)).json();
    assert.equal(llama.choices[0].message.content, `来自 ${LLAMA.id}`);
    assert.equal(pc.calls.length, 1, 'an upstream model is a single direct call');
    assert.equal(pc.calls[0].model, LLAMA.id);
    assert.equal(pc.calls[0].reasoning_effort, undefined);
    await (await pc.chat('pocket-direct')).json();
    assert.equal(pc.calls[1].model, DEEPSEEK.id);
    assert.equal(pc.calls[1].reasoning_effort, 'high');
    assert.equal((await pc.chat('bad model!')).status, 400);
  } finally { await pc.close(); }
});

test('the phone can read and change safe settings, which apply at once and persist', async () => {
  const pc = await listen();
  try {
    const before = await pc.get('/v1/pocket/config');
    assert.equal(before.settings.upstreamModel, DEEPSEEK.id);
    assert.deepEqual(before.options.reviewSkill, ['none', 'ss-stop-slop']);
    assert.equal(before.fixed.upstream_host, 'api.venice.ai');
    assert.doesNotMatch(JSON.stringify(before), /venice-secret-key|test-token/);

    const saved = await pc.patch({ upstreamModel: LLAMA.id, upstreamIdleTimeoutSec: 0, backupKeep: 3, reviewSkill: 'none', reasoningEffort: 'low' });
    assert.equal(saved.status, 200);
    assert.equal((await saved.json()).settings.upstreamModel, LLAMA.id);
    const file = JSON.parse(await fs.readFile(path.join(pc.dataDir, 'config.json'), 'utf8'));
    assert.equal(file.upstreamModel, LLAMA.id);
    assert.equal(file.host, '127.0.0.1', 'fields the phone did not send are kept');
    assert.equal(file.upstreamIdleTimeoutSec, 0);

    const status = await pc.get('/v1/pocket/status');
    assert.equal(status.default_model.name, 'Llama 3.3 70B');
    assert.equal(status.upstream.idle_timeout_seconds, 0);
    assert.equal(status.backups.keep, 3);
    assert.equal(status.review_skill, 'none');
    assert.equal((await pc.get('/v1/models')).data[0].name, '默认模型 · Llama 3.3 70B');
    await (await pc.chat('pocket-direct')).json();
    assert.equal(pc.calls.at(-1).model, LLAMA.id);
  } finally { await pc.close(); }
});

test('PC-only, unknown and invalid settings are refused without touching config.json', async () => {
  const pc = await listen();
  try {
    await pc.get('/v1/models');
    const original = await fs.readFile(path.join(pc.dataDir, 'config.json'), 'utf8');
    for (const [settings, message] of [
      [{ upstreamBaseUrl: 'https://evil.example/v1' }, /不能从手机修改：upstreamBaseUrl/],
      [{ token: 'x' }, /不能从手机修改/],
      [{ upstreamModel: 'not-on-venice' }, /上游没有这个模型/],
      [{ reasoningEffort: 'extreme' }, /思考强度无效/],
      [{ upstreamIdleTimeoutSec: 10 }, /0 或 30–3600/],
      [{}, /没有要修改的设置/],
    ]) {
      const response = await pc.patch(settings);
      assert.equal(response.status, 400, JSON.stringify(settings));
      assert.match((await response.json()).error.message, message);
    }
    assert.equal(await fs.readFile(path.join(pc.dataDir, 'config.json'), 'utf8'), original);
    assert.equal((await fetch(`${pc.base}/v1/pocket/config`, { method: 'PATCH', body: '{}' })).status, 401);
  } finally { await pc.close(); }
});

test('upstream model listing sends the key and keeps only text models', async () => {
  const seen = [];
  const server = http.createServer((req, res) => {
    seen.push({ url: req.url, auth: req.headers.authorization });
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ data: [LLAMA, { id: 'flux-dev', type: 'image' }, { id: 'plain-model' }] }));
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  try {
    const upstream = createUpstream({ baseUrl: `http://127.0.0.1:${server.address().port}/v1`, key: 'fixture' });
    assert.deepEqual((await upstream.models()).map((model) => model.id), [LLAMA.id, 'plain-model']);
    assert.equal(seen[0].url, '/v1/models');
    assert.equal(seen[0].auth, 'Bearer fixture');
  } finally { await new Promise((resolve) => server.close(resolve)); }
});
