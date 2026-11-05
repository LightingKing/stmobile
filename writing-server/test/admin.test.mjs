import test from 'node:test';
import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { createServer } from '../src/server.mjs';
import { createVault } from '../src/vault.mjs';
import { createAdminServer } from '../src/admin.mjs';

async function start(extra = {}) {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'pocket-admin-'));
  await fs.writeFile(path.join(dataDir, 'config.json'), JSON.stringify({ host: '127.0.0.1', port: 8791, upstreamModel: 'm' }));
  await fs.writeFile(path.join(dataDir, 'server.log'), Array.from({ length: 30 }, (_, i) => `line ${i}`).join('\n'));
  await fs.writeFile(path.join(dataDir, 'gateway-token.txt'), 'test-token');
  const config = { token: 'test-token', dataDir, host: '127.0.0.1', port: 8791, lanMode: true, ...extra };
  const server = createServer({ config, vault: createVault({ dataDir }),
    upstream: { complete: async () => ({ id: 'u', content: '好', reasoning: '', tool_calls: [], finish_reason: 'stop', usage: { total_tokens: 7 } }) } });
  const admin = createAdminServer({ requestListener: server.pocket.requestListener, config, vault: server.pocket.vault, dataDir });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  await new Promise((resolve) => admin.listen(0, '127.0.0.1', resolve));
  const api = `http://127.0.0.1:${server.address().port}`;
  const ui = `http://127.0.0.1:${admin.address().port}`;
  const close = async () => {
    for (const s of [server, admin]) { s.closeAllConnections(); await new Promise((resolve) => s.close(resolve)); }
  };
  return { api, ui, close, dataDir, adminPort: admin.address().port };
}

/** A raw request, so the Host and Origin headers can be anything. */
function raw(port, { method = 'GET', path: target = '/', headers = {}, body = '' } = {}) {
  return new Promise((resolve, reject) => {
    const req = http.request({ host: '127.0.0.1', port, method, path: target, headers }, (res) => {
      let text = '';
      res.setEncoding('utf8');
      res.on('data', (chunk) => { text += chunk; });
      res.on('end', () => resolve({ status: res.statusCode, headers: res.headers, text }));
    });
    req.on('error', reject);
    req.end(body);
  });
}

test('the dashboard serves its page and reaches the API with the PC token', async () => {
  const pc = await start();
  try {
    const page = await fetch(`${pc.ui}/`);
    assert.equal(page.status, 200);
    assert.match(page.headers.get('content-security-policy'), /script-src 'self'/);
    assert.match(await page.text(), /口袋酒馆 · 电脑写作服务/);
    assert.equal((await fetch(`${pc.ui}/app.js`)).headers.get('content-type'), 'text/javascript; charset=utf-8');
    const status = await (await fetch(`${pc.ui}/api/v1/pocket/status`)).json();
    assert.equal(status.ok, true);
    const connection = await (await fetch(`${pc.ui}/api/admin/connection`)).json();
    assert.equal(connection.token, 'test-token');
    assert.equal(connection.lan_url, 'http://127.0.0.1:8791/v1');
  } finally { await pc.close(); }
});

test('other websites and rebinding hosts cannot use the dashboard', async () => {
  const pc = await start();
  try {
    assert.equal((await raw(pc.adminPort, { headers: { host: 'evil.example:80' } })).status, 403);
    assert.equal((await raw(pc.adminPort, { path: '/api/v1/pocket/status', headers: { host: `127.0.0.1:${pc.adminPort}`, origin: 'https://evil.example' } })).status, 403);
    const noHeader = await raw(pc.adminPort, { method: 'PATCH', path: '/api/v1/pocket/config', headers: { host: `127.0.0.1:${pc.adminPort}`, 'content-type': 'application/json' }, body: '{"settings":{"backupKeep":3}}' });
    assert.equal(noHeader.status, 403, 'writes need the dashboard header, which forces a CORS preflight');
    assert.equal((await raw(pc.adminPort, { method: 'OPTIONS', path: '/api/v1/pocket/config', headers: { host: `127.0.0.1:${pc.adminPort}`, origin: 'https://evil.example' } })).status, 403);
    const own = await raw(pc.adminPort, { method: 'PATCH', path: '/api/v1/pocket/config', headers: { host: `localhost:${pc.adminPort}`, origin: `http://localhost:${pc.adminPort}`, 'x-pocket-admin': '1', 'content-type': 'application/json' }, body: '{"settings":{"backupKeep":3}}' });
    assert.equal(own.status, 200);
  } finally { await pc.close(); }
});

test('the dashboard lists recent requests, opens their records, tails logs and keeps the remote address', async () => {
  const pc = await start();
  try {
    await (await fetch(`${pc.api}/v1/chat/completions`, { method: 'POST', headers: { authorization: 'Bearer test-token', 'content-type': 'application/json' },
      body: JSON.stringify({ model: 'pocket-direct', stream: false, messages: [{ role: 'user', content: '你好' }], metadata: { pocket_tavern_chat_id: 'c1', pocket_tavern_purpose: 'chat' } }) })).json();
    const { runs } = await (await fetch(`${pc.ui}/api/admin/runs`)).json();
    assert.equal(runs.length, 1);
    assert.equal(runs[0].status, 'complete');
    assert.equal(runs[0].usage.total_tokens, 7);
    const record = await fetch(`${pc.ui}/api/admin/runs/${runs[0].chat_hash}/${runs[0].run_id}`);
    assert.match(await record.text(), /你好/);
    assert.equal((await fetch(`${pc.ui}/api/admin/runs/../../config/x`)).status, 404);
    const logs = await (await fetch(`${pc.ui}/api/admin/logs?lines=10`)).json();
    assert.deepEqual(logs.server.at(-1), 'line 29');
    assert.equal(logs.server.length, 10);
    const write = (value) => fetch(`${pc.ui}/api/admin/connection`, { method: 'PATCH', headers: { 'x-pocket-admin': '1', 'content-type': 'application/json' }, body: JSON.stringify({ publicBaseUrl: value }) });
    assert.equal((await write('http://plain.example/v1')).status, 400);
    assert.equal((await (await write('https://pt.example.com/v1')).json()).public_url, 'https://pt.example.com/v1');
    assert.equal(JSON.parse(await fs.readFile(path.join(pc.dataDir, 'config.json'), 'utf8')).publicBaseUrl, 'https://pt.example.com/v1');
  } finally { await pc.close(); }
});

test('a LAN listener proves it knows the token without sending it; other listeners stay silent', async () => {
  const lan = await start();
  try {
    const nonce = crypto.randomBytes(16).toString('hex');
    const { proof } = await (await fetch(`${lan.api}/v1/pocket/hello?nonce=${nonce}`)).json();
    assert.equal(proof, crypto.createHmac('sha256', 'test-token').update(`pocket-tavern-lan:${nonce}`).digest('hex'));
    assert.equal((await fetch(`${lan.api}/v1/pocket/hello?nonce=short`)).status, 400);
  } finally { await lan.close(); }
  // Here the LAN address is another interface, so this (loopback / tunnel) listener must not answer.
  const tunnel = await start({ host: '192.168.1.22' });
  try {
    assert.equal((await fetch(`${tunnel.api}/v1/pocket/hello?nonce=${'a'.repeat(32)}`)).status, 404);
  } finally { await tunnel.close(); }
});
