import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createServer } from '../src/server.mjs';
import { createVault } from '../src/vault.mjs';
import { buildArgs, channelForLink, createReachRuntime, ReachError, trimResult } from '../src/reach.mjs';

/** Stands in for agent-reach.exe: echoes its arguments and environment as one Agent Reach result. */
const fakeReach = (reply) => (command, args, options) => {
  const script = `const r=${JSON.stringify(reply ?? null)};process.stdout.write(JSON.stringify(r||{schema_version:1,ok:true,channel:process.argv[1],action:process.argv[2],backend:'fake',items:[{title:'t',url:'https://example.com',text:JSON.stringify({argv:process.argv.slice(1),env:Object.keys(process.env),home:process.env.HOME}),raw:{huge:'x'}}],error:null}))`;
  return spawn(process.execPath, ['-e', script, '--', ...args], options);
};

async function installed(spawnImpl) {
  const runtimeDir = await fs.mkdtemp(path.join(os.tmpdir(), 'pocket-reach-'));
  const exe = path.join(runtimeDir, 'agent-reach', process.platform === 'win32' ? 'Scripts' : 'bin', process.platform === 'win32' ? 'agent-reach.exe' : 'agent-reach');
  await fs.mkdir(path.dirname(exe), { recursive: true });
  await fs.writeFile(exe, '');
  return { runtimeDir, reach: createReachRuntime({ runtimeDir, spawnImpl }) };
}

test('tool calls become fixed read-only commands; links go to their site or the web reader', () => {
  assert.deepEqual(buildArgs({ action: 'search', query: '原神 7.2' }).args, ['web', 'search', '--limit', '5', '--timeout', '45', '--', '原神 7.2']);
  assert.deepEqual(buildArgs({ channel: 'bilibili', action: 'hot', limit: 3 }).args, ['bilibili', 'hot', '--limit', '3', '--timeout', '45']);
  assert.deepEqual(buildArgs({ channel: 'bilibili', action: 'subtitle', target: 'BV1GJ411x7h7' }).args, ['bilibili', 'subtitle', '--timeout', '45', '--', 'BV1GJ411x7h7']);
  // A query that looks like an option stays an argument.
  assert.deepEqual(buildArgs({ action: 'search', query: '--help' }).args.slice(-2), ['--', '--help']);
  assert.equal(buildArgs({ action: 'read', target: 'https://www.bilibili.com/video/BV1GJ411x7h7' }).channel, 'bilibili');
  assert.equal(buildArgs({ action: 'read', target: 'https://m.youtube.com/watch?v=x' }).channel, 'youtube');
  assert.equal(buildArgs({ action: 'read', target: 'https://zh.wikipedia.org/wiki/A' }).channel, 'web');
  assert.equal(channelForLink('https://evilbilibili.com/x'), 'web', 'a look-alike domain is not the site');
  assert.equal(channelForLink('github.com/SillyTavern/SillyTavern'), 'github');

  for (const bad of [
    { action: 'search' },
    { action: 'search', query: 'x'.repeat(301) },
    { action: 'search', query: 'a\nb' },
    { action: 'search', query: 'x', limit: 11 },
    { action: 'read', target: 'https://a.com/x y' },
    { channel: 'xiaohongshu', action: 'search', query: 'x' },
    { channel: 'twitter', action: 'search', query: 'x' },
    { channel: 'web', action: 'login' },
    { channel: 'youtube', action: 'transcribe', target: 'x' },
    [],
    null,
  ]) assert.throws(() => buildArgs(bad), ReachError, JSON.stringify(bad));
});

test('results keep what a chat needs and fit in the conversation', () => {
  const long = { ok: true, channel: 'web', action: 'read', items: [{ title: 'T', url: 'https://a.com', text: '字'.repeat(100_000), raw: { big: 'x'.repeat(10_000) } }], error: null };
  const read = trimResult(long, 'read');
  assert.equal(read.items[0].raw, undefined);
  assert.ok(JSON.stringify(read).length <= 40_000);
  assert.match(read.items[0].text, /已截断，原文 100000 字/);
  const search = trimResult({ ok: true, items: Array.from({ length: 20 }, (_, i) => ({ title: `t${i}`, text: 'x'.repeat(2000) })) }, 'search');
  assert.equal(search.items.length, 10);
  assert.ok(search.items[0].text.length < 700);
  // A video read has no text of its own: its description is what there is to read.
  const video = trimResult({ ok: true, channel: 'bilibili', action: 'read', items: [{ title: 'V', url: 'https://www.bilibili.com/video/BV1', text: '', raw: { description: '角色介绍', owner: { mid: 1 } } }], error: null }, 'read');
  assert.equal(video.items[0].text, '角色介绍');
  assert.equal(trimResult({ ok: true, items: [{ title: 'V', text: '字幕', raw: { description: '简介' } }] }, 'subtitle').items[0].text, '字幕');
  assert.equal(trimResult({ ok: true, items: [{ title: 'V', raw: { description: { not: 'text' } } }] }, 'read').items[0].text, '');
  const failed = trimResult({ ok: false, items: [], error: { code: 'RATE_LIMITED', message: 'm', hint: 'h', retry_after_seconds: 30 } }, 'search');
  assert.deepEqual(failed.error, { code: 'RATE_LIMITED', message: 'm', hint: 'h' });
});

test('Agent Reach runs with its own home and none of the server secrets', async () => {
  const { runtimeDir, reach } = await installed(fakeReach());
  process.env.VENICE_API_KEY_FOR_TEST = 'must-not-leak';
  try {
    const result = await reach.run({ action: 'search', query: '小猫' });
    const echoed = JSON.parse(result.items[0].text);
    assert.deepEqual(echoed.argv, ['web', 'search', '--limit', '5', '--timeout', '45', '--', '小猫']);
    assert.equal(echoed.home, path.join(runtimeDir, 'agent-reach-home'));
    assert.ok(!echoed.env.includes('VENICE_API_KEY_FOR_TEST'));
    // Browser profile locations stay out, so the bundled bili-cli cannot read the user's browser cookies.
    assert.ok(!echoed.env.includes('LOCALAPPDATA') && !echoed.env.includes('APPDATA'));
  } finally { delete process.env.VENICE_API_KEY_FOR_TEST; }
  const missing = createReachRuntime({ runtimeDir: path.join(runtimeDir, 'nowhere'), spawnImpl: fakeReach() });
  await assert.rejects(missing.run({ action: 'search', query: 'x' }), (error) => error.code === 'reach_not_installed');
  assert.deepEqual(await missing.status(), { available: false, error: '电脑上还没有安装 Agent Reach：在 writing-server 目录运行 install-agent-reach.ps1' });
});

test('POST /v1/pocket/reach needs the token, refuses bad calls and reports status', async () => {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'pocket-reach-server-'));
  const { reach } = await installed(fakeReach({ schema_version: 1, ok: true, action: 'version', result: { version: '1.6.1' }, error: null }));
  const server = createServer({ config: { token: 'tok', dataDir }, vault: createVault({ dataDir }), upstream: { complete: async () => ({}) }, reach });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  const post = (body, token = 'tok') => fetch(`${base}/v1/pocket/reach`, { method: 'POST', headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' }, body: JSON.stringify(body) });
  try {
    assert.equal((await post({ action: 'search', query: 'x' }, 'wrong')).status, 401);
    const refused = await post({ channel: 'xiaohongshu', action: 'search', query: 'x' });
    assert.equal(refused.status, 400);
    assert.equal((await refused.json()).error.code, 'reach_unsupported');
    const ok = await post({ action: 'search', query: 'x' });
    assert.equal(ok.status, 200);
    assert.equal((await ok.json()).ok, true);
    const status = await (await fetch(`${base}/v1/pocket/status`, { headers: { authorization: 'Bearer tok' } })).json();
    assert.ok(status.features.includes('web_reach'));
    assert.equal(status.reach.available, true);
    assert.equal(status.reach.version, '1.6.1');
    assert.deepEqual(status.reach.channels.web, ['search', 'read']);
  } finally {
    server.closeAllConnections();
    await new Promise((resolve) => server.close(resolve));
  }
});
