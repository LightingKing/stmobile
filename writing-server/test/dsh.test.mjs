import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn, spawnSync } from 'node:child_process';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createServer } from '../src/server.mjs';
import { createVault } from '../src/vault.mjs';
import { createDshRuntime, DshError } from '../src/dsh.mjs';

/**
 * Stands in for Python: "--version" answers like Python 3.12, anything else echoes the
 * arguments, stdin and the environment it was given, so the tests see what the engine would.
 */
const fakePython = (command, args, options) => {
  if (args.includes('--version')) return spawn(process.execPath, ['-e', 'console.log("Python 3.12.0")'], options);
  const echo = 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>process.stdout.write(JSON.stringify({ok:true,data:{argv:process.argv.slice(1),stdin:s,env:Object.keys(process.env)},error:null})))';
  return spawn(process.execPath, ['-e', echo, '--', ...args], options);
};

async function runtime(spawnImpl = fakePython) {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'pocket-dsh-'));
  return { dataDir, dsh: createDshRuntime({ dataDir, spawnImpl }) };
}

test('the engine is assembled from the pinned copy and the app Skill, and called with stdin JSON', async () => {
  const { dataDir, dsh } = await runtime();
  process.env.VENICE_API_KEY_FOR_TEST = 'must-not-leak';
  try {
    const result = await dsh.run('new-game', { request_id: 'r_12345678', mode: 'daily' });
    const { argv, stdin, env } = result.data;
    assert.equal(argv[1], 'new-game');
    assert.ok(argv.includes('--json'));
    assert.equal(argv[argv.indexOf('--data-dir') + 1], path.join(dataDir, 'dsh', 'data'));
    assert.equal(argv.at(-1), '-');
    assert.deepEqual(JSON.parse(stdin), { encoding_check: '汉字', request_id: 'r_12345678', mode: 'daily' });
    assert.ok(!env.includes('VENICE_API_KEY_FOR_TEST'), 'secrets in the server environment stay out of Python');
    for (const file of ['SKILL.md', 'scripts/adult_tension.py', 'runtime/adult_tension/launcher.py', 'content/index.json', 'references/commands.md']) {
      await fs.stat(path.join(dataDir, 'dsh', 'skill', file));
    }
    const doctor = await dsh.run('doctor');
    assert.ok(!doctor.data.argv.includes('--input-file'), 'commands without input get none');
    assert.equal((await dsh.status()).python, '3.12.0');
  } finally { delete process.env.VENICE_API_KEY_FOR_TEST; }
});

test('only game commands run, and no command may name a file path', async () => {
  const { dsh } = await runtime();
  await assert.rejects(dsh.run('smoke'), (error) => error instanceof DshError && error.code === 'dsh_unknown_command');
  await assert.rejects(dsh.run('verify-content'), { code: 'dsh_unknown_command' });
  await assert.rejects(dsh.run('export-save', { session_id: 's', path: 'C:\\Windows\\win.ini.json' }), { code: 'dsh_path_refused' });
  await assert.rejects(dsh.run('import-save', { path: 'C:\\secret.json' }), { code: 'dsh_path_refused' });
  await assert.rejects(dsh.run('status', ['not', 'an', 'object']), /JSON 对象/);
  assert.ok((await dsh.run('export-save', { session_id: 's' })).ok, 'exporting into the engine folder is fine');
});

test('a missing Python is reported, not crashed on', async () => {
  const { dsh } = await runtime((command, args, options) => spawn('pocket-no-such-python', args, options));
  assert.equal((await dsh.status()).available, false);
  await assert.rejects(dsh.run('doctor'), { code: 'dsh_python_missing' });
});

test('phones reach the engine through one authenticated endpoint', async () => {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'pocket-dsh-http-'));
  const calls = [];
  const fake = {
    run: async (command, input) => {
      calls.push({ command, input });
      if (command === 'smoke') throw new DshError('DSH 不支持这个命令：smoke', 400, 'dsh_unknown_command');
      return { ok: true, data: { echoed: input }, error: null };
    },
    status: async () => ({ available: true, python: '3.12.10' }),
  };
  const server = createServer({ config: { token: 'test-token', dataDir }, vault: createVault({ dataDir }), upstream: { complete: async () => ({}) }, dsh: fake });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}/v1`;
  const post = (body, headers = { authorization: 'Bearer test-token', 'content-type': 'application/json' }) => fetch(`${base}/pocket/dsh`, { method: 'POST', headers, body: JSON.stringify(body) });
  try {
    const ok = await post({ command: 'status', input: { session_id: 's1' } });
    assert.equal(ok.status, 200);
    assert.deepEqual((await ok.json()).data.echoed, { session_id: 's1' });
    const refused = await post({ command: 'smoke' });
    assert.equal(refused.status, 400);
    assert.equal((await refused.json()).error.code, 'dsh_unknown_command');
    assert.equal((await post({ command: 'status' }, { 'content-type': 'application/json' })).status, 401);
    const status = await (await fetch(`${base}/pocket/status`, { headers: { authorization: 'Bearer test-token' } })).json();
    assert.equal(status.dsh.available, true);
    assert.ok(status.features.includes('dsh_runtime'));
  } finally { server.closeAllConnections(); await new Promise((resolve) => server.close(resolve)); }
});

const hasPython = ['python', 'py'].some((command) => /Python 3\.(1\d|[2-9]\d)/.test(spawnSync(command, command === 'py' ? ['-3', '--version'] : ['--version'], { encoding: 'utf8' }).stdout || ''));

test('the real engine starts here and lists its worlds', { skip: !hasPython && 'Python 3.10+ is not installed' }, async () => {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'pocket-dsh-real-'));
  const dsh = createDshRuntime({ dataDir });
  const doctor = await dsh.run('doctor');
  assert.equal(doctor.ok, true, JSON.stringify(doctor.error));
  const worlds = await dsh.run('list-worlds', {});
  assert.equal(worlds.ok, true);
  assert.ok(worlds.data.worlds.length >= 200);
});
