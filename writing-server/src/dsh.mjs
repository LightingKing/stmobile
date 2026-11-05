import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { cp, mkdir, readFile, rm, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * DSH 成人互动 (Adult Tension) keeps its story state in a local Python engine. The phone cannot
 * run it, so chats on this PC reach it through one tool. The engine is the pinned upstream copy in
 * vendor/adult-tension (verified against MANIFEST.json); SKILL.md, references/ and content/ are the
 * app's own copy of the same Skill. Both are assembled into dataDir/dsh/skill, and the engine keeps
 * its saves in dataDir/dsh/data.
 */
const VENDOR = fileURLToPath(new URL('../vendor/adult-tension/', import.meta.url));
const ASSETS = fileURLToPath(new URL('../../app/src/main/assets/writing-skills/at-adult-tension-original/', import.meta.url));

/** The game's commands; the engine's own developer checks (verify-content, smoke) are left out. */
export const DSH_COMMANDS = new Set([
  'doctor', 'version', 'list-worlds', 'new-game', 'get-context', 'commit-turn', 'undo-turn', 'save-slot', 'load-slot',
  'list-slots', 'delete-slot', 'save-world', 'my-worlds', 'delete-world', 'list-sessions', 'export-save', 'import-save',
  'set-boundary', 'set-safety', 'set-preferences', 'guide', 'status',
]);
const NO_INPUT = new Set(['doctor', 'version', 'list-slots', 'my-worlds']);
const TIMEOUT_MS = 60_000;
const MAX_INPUT_BYTES = 1024 * 1024;
const MAX_OUTPUT_BYTES = 4 * 1024 * 1024;

export class DshError extends Error {
  constructor(message, status = 400, code = 'dsh_error') { super(message); this.status = status; this.code = code; }
}

const gitBlob = (bytes) => createHash('sha1').update(`blob ${bytes.length}\0`).update(bytes).digest('hex');

/** Only what Python needs; the Venice key and other secrets in this process's environment stay out. */
function childEnv() {
  const keep = ['PATH', 'Path', 'SYSTEMROOT', 'SystemRoot', 'WINDIR', 'TEMP', 'TMP', 'USERPROFILE', 'HOME', 'LOCALAPPDATA', 'APPDATA', 'PATHEXT', 'COMSPEC'];
  const env = Object.fromEntries(keep.filter((name) => process.env[name] !== undefined).map((name) => [name, process.env[name]]));
  return { ...env, PYTHONIOENCODING: 'utf-8', PYTHONUTF8: '1' };
}

/** Runs a process with stdin, a time limit and a cap on stdout. */
function runProcess(spawnImpl, command, args, { input = null, cwd, timeoutMs = TIMEOUT_MS }) {
  return new Promise((resolve, reject) => {
    let child;
    try { child = spawnImpl(command, args, { cwd, env: childEnv(), windowsHide: true }); } catch (error) { return reject(error); }
    const out = [];
    const err = [];
    let outBytes = 0;
    let settled = false;
    const finish = (fn, value) => { if (!settled) { settled = true; clearTimeout(timer); fn(value); } };
    const timer = setTimeout(() => { child.kill(); finish(reject, new DshError('DSH 引擎超过 60 秒没有完成', 504, 'dsh_timeout')); }, timeoutMs);
    child.stdout.on('data', (chunk) => {
      outBytes += chunk.length;
      if (outBytes > MAX_OUTPUT_BYTES) { child.kill(); finish(reject, new DshError('DSH 引擎输出过大', 502, 'dsh_output_too_large')); return; }
      out.push(chunk);
    });
    child.stderr.on('data', (chunk) => { if (err.reduce((n, c) => n + c.length, 0) < 64 * 1024) err.push(chunk); });
    child.on('error', (error) => finish(reject, error));
    child.on('close', (code) => finish(resolve, { code, stdout: Buffer.concat(out).toString('utf8'), stderr: Buffer.concat(err).toString('utf8') }));
    child.stdin.on('error', () => { /* the engine may exit before reading */ });
    child.stdin.end(input ?? undefined);
  });
}

export function createDshRuntime({ dataDir, python = '', vendorDir = VENDOR, assetsDir = ASSETS, spawnImpl = spawn } = {}) {
  const root = path.join(dataDir, 'dsh');
  const skillRoot = path.join(root, 'skill');
  const dataRoot = path.join(root, 'data');
  let ready = null;
  let queue = Promise.resolve();

  async function findPython() {
    const candidates = python ? [[python, []]] : [['python', []], ['py', ['-3']], ['python3', []]];
    for (const [command, prefix] of candidates) {
      try {
        const result = await runProcess(spawnImpl, command, [...prefix, '--version'], { cwd: root, timeoutMs: 15_000 });
        const match = /Python (\d+)\.(\d+)\.(\d+)/.exec(`${result.stdout} ${result.stderr}`);
        if (match && (Number(match[1]) > 3 || (Number(match[1]) === 3 && Number(match[2]) >= 10))) return { command, prefix, version: match.slice(1).join('.') };
      } catch { /* try the next one */ }
    }
    throw new DshError('电脑上没有找到 Python 3.10 或更高版本，DSH 引擎无法运行', 503, 'dsh_python_missing');
  }

  /** Checks the pinned engine and assembles the Skill directory once per manifest. */
  async function prepare() {
    const manifest = JSON.parse(await readFile(path.join(vendorDir, 'MANIFEST.json'), 'utf8'));
    for (const [file, sha] of Object.entries(manifest.files)) {
      if (gitBlob(await readFile(path.join(vendorDir, file))) !== sha) throw new DshError(`DSH 引擎文件与固定版本不一致：${file}`, 500, 'dsh_integrity');
    }
    for (const required of ['SKILL.md', 'references', 'content/index.json']) {
      await stat(path.join(assetsDir, required)).catch(() => { throw new DshError(`缺少 DSH Skill 文件：${required}`, 500, 'dsh_missing_files'); });
    }
    const stamp = `${manifest.commit}:${createHash('sha256').update(JSON.stringify(manifest.files)).digest('hex')}`;
    const marker = path.join(skillRoot, '.pocket-tavern-build');
    if ((await readFile(marker, 'utf8').catch(() => '')) !== stamp) {
      await rm(skillRoot, { recursive: true, force: true });
      await mkdir(skillRoot, { recursive: true });
      for (const folder of ['scripts', 'runtime']) await cp(path.join(vendorDir, folder), path.join(skillRoot, folder), { recursive: true });
      for (const item of ['SKILL.md', 'references', 'content']) await cp(path.join(assetsDir, item), path.join(skillRoot, item), { recursive: true });
      await writeFile(marker, stamp);
    }
    await mkdir(dataRoot, { recursive: true });
    return { commit: manifest.commit, python: await findPython() };
  }

  function prepared() {
    ready ??= prepare().catch((error) => { ready = null; throw error; });
    return ready;
  }

  /** Validates one tool call and runs it after any earlier one (the engine keeps a single SQLite file). */
  async function run(command, input = {}) {
    if (typeof command !== 'string' || !DSH_COMMANDS.has(command)) throw new DshError(`DSH 不支持这个命令：${command}`, 400, 'dsh_unknown_command');
    if (input === null || typeof input !== 'object' || Array.isArray(input)) throw new DshError('input 必须是 JSON 对象');
    // Files stay inside the engine's data directory: a path here could read or write anywhere on this PC.
    if (['export-save', 'import-save'].includes(command) && input.path != null) {
      throw new DshError('在手机上导入请直接粘贴导出的 JSON（data），导出会写到电脑上 DSH 的数据目录，不能指定路径', 400, 'dsh_path_refused');
    }
    const body = NO_INPUT.has(command) ? null : Buffer.from(JSON.stringify({ encoding_check: '汉字', ...input }), 'utf8');
    if (body && body.length > MAX_INPUT_BYTES) throw new DshError('DSH 输入超过 1 MB');
    const task = queue.then(async () => {
      const { python: py } = await prepared();
      const args = [...py.prefix, path.join(skillRoot, 'scripts', 'adult_tension.py'), command, '--json', '--data-dir', dataRoot, ...(body ? ['--input-file', '-'] : [])];
      const result = await runProcess(spawnImpl, py.command, args, { input: body, cwd: root });
      try { return JSON.parse(result.stdout); } catch {
        throw new DshError(`DSH 引擎没有返回 JSON（退出码 ${result.code}）：${result.stderr.trim().slice(-300) || '无输出'}`, 502, 'dsh_bad_output');
      }
    });
    queue = task.catch(() => {});
    return task;
  }

  async function status() {
    try {
      const { commit, python: py } = await prepared();
      return { available: true, commit, python: py.version };
    } catch (error) {
      return { available: false, error: error.message };
    }
  }

  return { run, status, paths: { root, skillRoot, dataRoot } };
}
