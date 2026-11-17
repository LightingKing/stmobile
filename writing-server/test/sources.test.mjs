import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createSources, checkBaseUrl, normalizedModel, sameModel } from '../src/sources.mjs';
import { createServer } from '../src/server.mjs';
import { createVault } from '../src/vault.mjs';
import { createAdminServer } from '../src/admin.mjs';

const VENICE = 'https://api.venice.ai/api/v1';
const RELAY = 'https://api.relay.example/v1';
const CATALOGS = {
  [VENICE]: [
    { id: 'deepseek-v4-1-flash-1017', model_spec: { name: 'DeepSeek V4.1 Flash', availableContextTokens: 1000000, capabilities: { supportsFunctionCalling: true, supportsReasoning: true } } },
    { id: 'kimi-k3-1-pro', model_spec: { name: 'Kimi K3.1 Pro', availableContextTokens: 1000000, capabilities: { supportsFunctionCalling: true, supportsVision: true, supportsReasoning: true } } },
  ],
  [RELAY]: [{ id: 'k3.1-pro', object: 'model' }, { id: 'mystery-7b', object: 'model' }],
  'http://127.0.0.1:11434/v1': [{ id: 'qwen3:8b', object: 'model' }],
};

/** Upstream clients that read the fixed catalogs above and record every request with its address and key. */
function fakeUpstreams() {
  const calls = [];
  const make = ({ baseUrl, key }) => ({
    async models() { if (!CATALOGS[baseUrl] || key === 'wrong') throw new Error('上游模型列表 HTTP 401'); return CATALOGS[baseUrl]; },
    async complete({ body }) { calls.push({ baseUrl, key, body }); return { id: 'x', content: '好', reasoning: '', tool_calls: [], finish_reason: 'stop', usage: { total_tokens: 3 } }; },
  });
  return { make, calls };
}

async function setup({ model = 'deepseek-v4-1-flash-1017' } = {}) {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'pocket-sources-'));
  const fake = fakeUpstreams();
  const state = { model };
  const sources = createSources({ dataDir, builtin: { baseUrl: VENICE, key: 'venice-secret-1234' }, makeUpstream: fake.make, currentModel: () => state.model, log: { log() {}, warn() {} } });
  return { dataDir, fake, sources, state };
}

test('model names are matched across sources only when they are specific', () => {
  assert.equal(normalizedModel('k3.1-pro'), 'k31pro');
  assert.equal(normalizedModel('e2ee-kimi-k2-6'), 'kimik26');
  assert.ok(sameModel('k31pro', 'kimik31pro'));
  assert.ok(!sameModel('pro', 'geminipro'));
  assert.ok(!sameModel('k31pro', 'kimik31prohighspeed'));
  // A release-date suffix is dropped, so a relay's undated id finds the dated one.
  assert.equal(normalizedModel('deepseek-v4-1-flash-1017'), normalizedModel('deepseek-v4.1-flash'));
  assert.equal(normalizedModel('gpt-4o-2024-08-06'), 'gpt4o');
  assert.equal(normalizedModel('claude-3-5-sonnet-20241022'), 'claude35sonnet');
  assert.equal(normalizedModel('qwen3-235b-a22b'), 'qwen3235ba22b');
});

test('addresses: https for remote APIs, this PC only for Ollama, nothing hidden in the URL', () => {
  assert.equal(checkBaseUrl('openai', 'https://api.relay.example/v1/'), 'https://api.relay.example/v1');
  assert.throws(() => checkBaseUrl('openai', 'http://api.relay.example/v1'), /https/);
  assert.throws(() => checkBaseUrl('openai', 'https://user:pw@api.relay.example/v1'), /账号密码/);
  assert.throws(() => checkBaseUrl('openai', 'https://api.relay.example/v1?key=1'), /参数/);
  assert.equal(checkBaseUrl('ollama', 'http://127.0.0.1:11434/v1'), 'http://127.0.0.1:11434/v1');
  assert.throws(() => checkBaseUrl('ollama', 'http://192.168.1.5:11434/v1'), /这台电脑/);
});

test('a source is saved only after its catalog was read, and its key never comes back', async () => {
  const { sources, dataDir } = await setup();
  await assert.rejects(sources.add({ kind: 'openai', name: '中转', baseUrl: RELAY, key: 'wrong' }), (error) => error.status === 502 && /读不到模型列表/.test(error.message));
  assert.equal(sources.list().length, 1);
  await assert.rejects(sources.add({ kind: 'openai', name: '中转', baseUrl: RELAY, key: '' }), /要填密钥/);
  const added = await sources.add({ kind: 'openai', name: '中转', baseUrl: RELAY, key: 'sk-relay-secret-c31d' });
  assert.equal(added.id, 'api');
  assert.equal(added.key_tail, 'c31d');
  assert.equal(added.tested.count, 2);
  assert.equal(added.models, 2);
  assert.ok(sources.hasKeyFile('api'));
  const listed = JSON.stringify(sources.list());
  assert.doesNotMatch(listed, /sk-relay-secret|venice-secret/);
  const saved = await fs.readFile(path.join(dataDir, 'sources.json'), 'utf8');
  assert.doesNotMatch(saved, /sk-relay-secret/);
  assert.equal((await fs.readFile(path.join(dataDir, 'source-keys', 'api.txt'), 'utf8')).trim(), 'sk-relay-secret-c31d');
  await assert.rejects(sources.add({ kind: 'openai', name: '中转', baseUrl: RELAY, key: 'sk-2' }), /已经有一个叫“中转”/);
  // Ollama needs no key and gets its own short id.
  const local = await sources.add({ kind: 'ollama', name: '本机', baseUrl: '' , key: '' });
  assert.equal(local.id, 'ollama');
  assert.equal(local.base_url, 'http://127.0.0.1:11434/v1');
});

test('other sources list as "id/model" named "name/model", with a guess where they say nothing', async () => {
  const { sources } = await setup();
  await sources.add({ kind: 'openai', name: '中转', baseUrl: RELAY, key: 'sk-relay' });
  const { models } = await sources.models();
  assert.deepEqual(models.map((entry) => entry.id), ['deepseek-v4-1-flash-1017', 'kimi-k3-1-pro', 'api/k3.1-pro', 'api/mystery-7b']);
  const relay = models.find((entry) => entry.id === 'api/k3.1-pro');
  assert.equal(relay.name, '中转/k3.1-pro');
  assert.equal(relay.context_length, 1000000);
  assert.equal(relay.supports_vision, true);
  assert.equal(relay.supports_tools, true);
  assert.deepEqual(relay.pocket_tavern_guessed, { from: 'kimi-k3-1-pro' });
  const unknown = models.find((entry) => entry.id === 'api/mystery-7b');
  assert.equal(unknown.pocket_tavern_guessed, undefined);
  assert.equal(unknown.supports_vision, undefined);
  // The built-in source's entries are untouched.
  assert.equal(models[1].model_spec.name, 'Kimi K3.1 Pro');
  assert.equal(models[1].pocket_source.name, 'Venice');
});

test('a model id finds its source; switched-off and in-use sources are protected', async () => {
  const { sources, state } = await setup();
  await sources.add({ kind: 'openai', name: '中转', baseUrl: RELAY, key: 'sk-relay' });
  await sources.models();
  assert.equal(sources.resolve('api/k3.1-pro').source.id, 'api');
  assert.equal(sources.resolve('api/k3.1-pro').model, 'k3.1-pro');
  assert.equal(sources.resolve('kimi-k3-1-pro').source.id, 'venice');
  // An id whose first part is not a source stays with the built-in one.
  assert.equal(sources.resolve('openai/gpt-x').model, 'openai/gpt-x');
  state.model = 'api/k3.1-pro';
  await assert.rejects(sources.remove('api'), /默认模型在这个来源里/);
  await assert.rejects(sources.update('api', { enabled: false }), /默认模型在这个来源里/);
  state.model = 'kimi-k3-1-pro';
  await sources.update('api', { enabled: false });
  assert.throws(() => sources.resolve('api/k3.1-pro'), /已停用/);
  assert.equal((await sources.models()).models.length, 2, 'a switched-off source lists nothing');
  await assert.rejects(sources.remove('venice'), /内置的来源不能删除/);
  await assert.rejects(sources.update('venice', { enabled: false }), /不能停用/);
});

test('sources come back after a restart, renames stick, and a new key is tried before it is saved', async () => {
  const { sources, dataDir, fake } = await setup();
  await sources.add({ kind: 'openai', name: '中转', baseUrl: RELAY, key: 'sk-relay' });
  await sources.update('api', { name: '我的中转' });
  await sources.update('venice', { name: '威尼斯' });
  await assert.rejects(sources.update('api', { key: 'wrong' }), /读不到模型列表/);
  assert.equal((await fs.readFile(path.join(dataDir, 'source-keys', 'api.txt'), 'utf8')).trim(), 'sk-relay', 'a key that fails is not written');
  await sources.update('venice', { key: 'venice-new-9999' });
  assert.equal((await fs.readFile(path.join(dataDir, 'venice-key.txt'), 'utf8')).trim(), 'venice-new-9999');
  const again = createSources({ dataDir, builtin: { baseUrl: VENICE, key: 'venice-new-9999' }, makeUpstream: fake.make, log: { log() {}, warn() {} } });
  assert.deepEqual(again.list().map((source) => [source.id, source.name, source.key_tail]), [['venice', '威尼斯', '9999'], ['api', '我的中转', 'elay']]);
  assert.equal((await again.models()).models.find((entry) => entry.id === 'api/k3.1-pro').name, '我的中转/k3.1-pro');
  await again.remove('api');
  assert.ok(!again.hasKeyFile('api'));
});

test('the server routes each request to its source and lists every source to the phone', async () => {
  const { sources, fake, dataDir, state } = await setup();
  await sources.add({ kind: 'openai', name: '中转', baseUrl: RELAY, key: 'sk-relay' });
  const config = { token: 'test-token', dataDir, upstreamBaseUrl: VENICE, upstreamKey: 'venice-secret-1234', upstreamModel: 'deepseek-v4-1-flash-1017', reasoningEffort: 'high' };
  const server = createServer({ config, vault: createVault({ dataDir }), sources });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}/v1`;
  const call = (url, init = {}) => fetch(`${base}${url}`, { ...init, headers: { authorization: 'Bearer test-token', 'content-type': 'application/json' } });
  const chat = (model) => call('/chat/completions', { method: 'POST', body: JSON.stringify({ model, stream: false, messages: [{ role: 'user', content: '写' }], metadata: { pocket_tavern_chat_id: 'c1' } }) });
  try {
    assert.equal((await chat('api/k3.1-pro')).status, 200);
    assert.equal(fake.calls.at(-1).baseUrl, RELAY);
    assert.equal(fake.calls.at(-1).key, 'sk-relay');
    assert.equal(fake.calls.at(-1).body.model, 'k3.1-pro');
    assert.equal(fake.calls.at(-1).body.venice_parameters, undefined, 'Venice-only parameters stay with Venice');
    assert.equal((await chat('pocket-direct')).status, 200);
    assert.equal(fake.calls.at(-1).baseUrl, VENICE);
    assert.equal(fake.calls.at(-1).body.model, 'deepseek-v4-1-flash-1017');
    assert.ok(fake.calls.at(-1).body.venice_parameters);
    // The default model can live in another source.
    const patched = await call('/pocket/config', { method: 'PATCH', body: JSON.stringify({ settings: { upstreamModel: 'api/k3.1-pro' } }) });
    assert.equal(patched.status, 200);
    state.model = 'api/k3.1-pro';
    await chat('pocket-direct');
    assert.equal(fake.calls.at(-1).baseUrl, RELAY);
    assert.equal(fake.calls.at(-1).body.model, 'k3.1-pro');
    assert.equal((await call('/pocket/config', { method: 'PATCH', body: JSON.stringify({ settings: { upstreamModel: 'api/nope' } }) })).status, 400);
    const models = await (await call('/models')).json();
    const relay = models.data.find((entry) => entry.id === 'api/k3.1-pro');
    assert.equal(relay.name, '中转/k3.1-pro');
    assert.equal(relay.upstream.host, 'api.relay.example');
    assert.equal(models.data[0].id, 'pocket-direct');
    assert.equal(models.data[0].upstream.host, 'api.relay.example');
    const status = await (await call('/pocket/status')).json();
    assert.deepEqual(status.sources.map((item) => item.id), ['venice', 'api']);
    assert.doesNotMatch(JSON.stringify(status), /secret|sk-relay|key_tail/);
    assert.equal(status.default_model.source, '中转');
    assert.ok(status.features.includes('model_sources'));
  } finally { await new Promise((resolve) => server.close(resolve)); }
});

test('the dashboard manages sources behind its own header; keys stay on this side', async () => {
  const { sources, dataDir } = await setup();
  const config = { token: 'test-token', dataDir, upstreamBaseUrl: VENICE, upstreamKey: 'venice-secret-1234' };
  const server = createServer({ config, vault: createVault({ dataDir }), sources });
  const admin = createAdminServer({ requestListener: server.pocket.requestListener, config, vault: server.pocket.vault, dataDir, sources, worlds: server.pocket.worlds });
  await new Promise((resolve) => admin.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${admin.address().port}/api/admin/sources`;
  const write = (url, method, body) => fetch(url, { method, headers: { 'x-pocket-admin': '1', 'content-type': 'application/json' }, body: body && JSON.stringify(body) });
  try {
    assert.equal((await fetch(base, { method: 'POST', body: '{}' })).status, 403, 'writes need the dashboard header');
    const tested = await (await write(`${base}/test`, 'POST', { kind: 'openai', baseUrl: RELAY, key: 'sk-relay' })).json();
    assert.equal(tested.count, 2);
    assert.equal((await write(`${base}/test`, 'POST', { kind: 'openai', baseUrl: RELAY, key: 'wrong' })).status, 502);
    assert.equal((await write(base, 'POST', { kind: 'openai', name: '中转', baseUrl: RELAY, key: 'sk-relay-secret' })).status, 201);
    const listed = await (await fetch(base)).json();
    assert.deepEqual(listed.sources.map((source) => source.id), ['venice', 'api']);
    assert.doesNotMatch(JSON.stringify(listed), /sk-relay-secret|venice-secret/);
    assert.equal((await write(`${base}/api/refresh`, 'POST')).status, 200);
    assert.equal((await write(`${base}/api`, 'PATCH', { name: '我的中转' })).status, 200);
    assert.equal((await write(`${base}/venice`, 'DELETE')).status, 400);
    assert.equal((await write(`${base}/api`, 'DELETE')).status, 200);
    assert.equal((await write(`${base}/nope`, 'DELETE')).status, 404);
  } finally { await new Promise((resolve) => admin.close(resolve)); }
});

test("the phone's last contact is shown, and the dashboard's own requests are not mistaken for it", async () => {
  const { sources, dataDir } = await setup();
  const config = { token: 'test-token', dataDir, upstreamBaseUrl: VENICE, upstreamKey: 'venice-secret-1234' };
  const server = createServer({ config, vault: createVault({ dataDir }), sources });
  const admin = createAdminServer({ requestListener: server.pocket.requestListener, config, vault: server.pocket.vault, dataDir, sources, worlds: server.pocket.worlds });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  await new Promise((resolve) => admin.listen(0, '127.0.0.1', resolve));
  const viaAdmin = `http://127.0.0.1:${admin.address().port}/api/v1/pocket/status`;
  try {
    assert.equal((await (await fetch(viaAdmin)).json()).phone, null, 'the dashboard reading status is not the phone');
    const direct = await fetch(`http://127.0.0.1:${server.address().port}/v1/models`, { headers: { authorization: 'Bearer test-token' } });
    assert.equal(direct.status, 200);
    const { phone } = await (await fetch(viaAdmin)).json();
    assert.equal(phone.via, 'remote', 'loopback is how the internet tunnel arrives');
    assert.ok(Date.now() - Date.parse(phone.at) < 5000);
  } finally {
    await new Promise((resolve) => admin.close(resolve));
    await new Promise((resolve) => server.close(resolve));
  }
});
