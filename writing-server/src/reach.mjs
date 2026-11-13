import { spawn } from 'node:child_process';
import { access, readFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Agent Reach (github.com/Panniantong/Agent-Reach, MIT) gives chats on this PC web search and
 * page reading. It is a Python CLI, so the phone reaches it through one tool and this PC runs it.
 * install-agent-reach.ps1 puts a pinned copy in runtime/agent-reach with its own home in
 * runtime/agent-reach-home, so Agent Reach never touches other AI tools' skill folders or a
 * browser's logins. Only channels that need no login are offered, and every call is one fixed
 * read-only command built here; the model never supplies a command line.
 */
const RUNTIME = fileURLToPath(new URL('../runtime/', import.meta.url));
const EXE = process.platform === 'win32' ? path.join('Scripts', 'agent-reach.exe') : path.join('bin', 'agent-reach');

/** channel -> action -> which argument it takes ('query', 'target' or none) and whether --limit applies. */
export const REACH_ACTIONS = {
  web: { search: { arg: 'query', limit: true }, read: { arg: 'target' } },
  // Bilibili subtitles need the QR login made on the PC dashboard (bililogin.mjs).
  bilibili: { search: { arg: 'query', limit: true }, read: { arg: 'target' }, hot: { limit: true }, subtitle: { arg: 'target' } },
  youtube: { search: { arg: 'query', limit: true }, read: { arg: 'target' }, subtitle: { arg: 'target' } },
  github: { search: { arg: 'query', limit: true }, read: { arg: 'target' } },
  v2ex: { hot: { limit: true }, read: { arg: 'target' } },
};

/** A link to one of these sites is read with its own channel; any other public page goes to web. */
const SITE_CHANNELS = [
  ['bilibili', ['bilibili.com', 'b23.tv']],
  ['youtube', ['youtube.com', 'youtu.be']],
  ['github', ['github.com']],
  ['v2ex', ['v2ex.com']],
];

const CALL_TIMEOUT_SECONDS = 45;
const KILL_AFTER_MS = 75_000;
const MAX_OUTPUT_BYTES = 8 * 1024 * 1024;
const MAX_QUERY = 300;
const MAX_TARGET = 2000;
const SEARCH_TEXT = 600;
const READ_TEXT = 12_000;
const MAX_RESULT_CHARS = 40_000;
const PARALLEL = 2;

export class ReachError extends Error {
  constructor(message, status = 400, code = 'reach_error') { super(message); this.status = status; this.code = code; }
}

/**
 * Only what Python needs, plus Agent Reach's private home; secrets in this process stay out.
 * LOCALAPPDATA and APPDATA are left out on purpose: they point at the user's browser profiles, and
 * the bundled bili-cli would otherwise try to read Bilibili cookies from Chrome, Edge or Firefox.
 */
function childEnv(home) {
  const keep = ['PATH', 'Path', 'SYSTEMROOT', 'SystemRoot', 'WINDIR', 'TEMP', 'TMP', 'USERPROFILE', 'PATHEXT', 'COMSPEC',
    'HTTP_PROXY', 'HTTPS_PROXY', 'NO_PROXY', 'http_proxy', 'https_proxy', 'no_proxy'];
  const env = Object.fromEntries(keep.filter((name) => process.env[name] !== undefined).map((name) => [name, process.env[name]]));
  return { ...env, HOME: home, PYTHONIOENCODING: 'utf-8', PYTHONUTF8: '1' };
}

function runProcess(spawnImpl, command, args, { cwd, env, timeoutMs = KILL_AFTER_MS }) {
  return new Promise((resolve, reject) => {
    let child;
    try { child = spawnImpl(command, args, { cwd, env, windowsHide: true }); } catch (error) { return reject(error); }
    const out = [];
    let outBytes = 0;
    let errText = '';
    let settled = false;
    const finish = (fn, value) => { if (!settled) { settled = true; clearTimeout(timer); fn(value); } };
    const timer = setTimeout(() => { child.kill(); finish(reject, new ReachError('联网查询超过 75 秒没有完成', 504, 'reach_timeout')); }, timeoutMs);
    child.stdout.on('data', (chunk) => {
      outBytes += chunk.length;
      if (outBytes > MAX_OUTPUT_BYTES) { child.kill(); finish(reject, new ReachError('联网查询返回内容过大', 502, 'reach_output_too_large')); return; }
      out.push(chunk);
    });
    child.stderr.on('data', (chunk) => { if (errText.length < 16 * 1024) errText += chunk.toString('utf8'); });
    child.on('error', (error) => finish(reject, error));
    child.on('close', (code) => finish(resolve, { code, stdout: Buffer.concat(out).toString('utf8'), stderr: errText }));
    child.stdin?.end();
  });
}

const clip = (value, max) => {
  const text = typeof value === 'string' ? value : '';
  return text.length > max ? `${text.slice(0, max)}…（已截断，原文 ${text.length} 字）` : text;
};

function hostOf(target) {
  try { return new URL(/^[a-z][a-z0-9+.-]*:\/\//i.test(target) ? target : `https://${target}`).hostname.toLowerCase().replace(/\.$/, ''); } catch { return ''; }
}

/** Which channel reads this link: a known site's own, otherwise the general web reader. */
export function channelForLink(target) {
  const host = hostOf(target);
  for (const [channel, domains] of SITE_CHANNELS) {
    if (domains.some((domain) => host === domain || host.endsWith(`.${domain}`))) return channel;
  }
  return 'web';
}

/** Checks one tool call and turns it into the fixed argument list Agent Reach gets. */
export function buildArgs(call) {
  if (call === null || typeof call !== 'object' || Array.isArray(call)) throw new ReachError('参数必须是 JSON 对象');
  const action = String(call.action ?? '').trim();
  let channel = String(call.channel ?? '').trim() || (action === 'read' ? 'auto' : 'web');
  const target = typeof call.target === 'string' ? call.target.trim() : '';
  if (action === 'read' && channel === 'auto') channel = channelForLink(target);
  const spec = REACH_ACTIONS[channel]?.[action];
  if (!spec) throw new ReachError(`不支持 ${channel} 的 ${action}；可用：${Object.entries(REACH_ACTIONS).map(([c, a]) => `${c}(${Object.keys(a).join('/')})`).join('、')}`, 400, 'reach_unsupported');
  const args = [channel, action];
  if (spec.limit) {
    const limit = call.limit == null ? 5 : Number(call.limit);
    if (!Number.isInteger(limit) || limit < 1 || limit > 10) throw new ReachError('limit 必须是 1 到 10 的整数');
    args.push('--limit', String(limit));
  }
  args.push('--timeout', String(CALL_TIMEOUT_SECONDS));
  if (spec.arg === 'query') {
    const query = typeof call.query === 'string' ? call.query.trim() : '';
    if (!query) throw new ReachError('搜索需要 query');
    if (query.length > MAX_QUERY || /[\u0000-\u001f]/.test(query)) throw new ReachError(`query 最多 ${MAX_QUERY} 字，且不能含控制字符`);
    args.push('--', query);
  } else if (spec.arg === 'target') {
    if (!target) throw new ReachError('需要 target（链接或 ID）');
    if (target.length > MAX_TARGET || /[\s\u0000-\u001f]/.test(target)) throw new ReachError(`target 最多 ${MAX_TARGET} 字，且不能含空白`);
    args.push('--', target);
  }
  return { channel, action, args };
}

/** Keeps what a chat model needs: no raw upstream payloads, bounded text. */
export function trimResult(envelope, action) {
  const raw = Array.isArray(envelope?.items) ? envelope.items.slice(0, 10) : [];
  const error = envelope?.error ? { code: String(envelope.error.code ?? ''), message: clip(String(envelope.error.message ?? ''), 400), hint: clip(String(envelope.error.hint ?? ''), 400) || undefined } : null;
  const build = (perItem) => ({
    ok: envelope?.ok === true, channel: envelope?.channel, action: envelope?.action ?? action, backend: envelope?.backend ?? null,
    items: raw.map((item) => ({
      title: clip(item?.title, 300),
      url: clip(item?.url, 1000),
      author: clip(item?.author, 120) || undefined,
      published_at: clip(item?.published_at, 40) || undefined,
      // A video read comes back without text; its description is in the raw record.
      text: clip(item?.text || (typeof item?.raw?.description === 'string' ? item.raw.description : ''), perItem),
    })),
    error,
  });
  // A very long page still has to fit the chat: shorten every text until the whole answer does.
  let perItem = action === 'search' || action === 'hot' ? SEARCH_TEXT : READ_TEXT;
  let result = build(perItem);
  while (JSON.stringify(result).length > MAX_RESULT_CHARS && perItem > 200) {
    perItem = Math.floor(perItem * 0.7);
    result = build(perItem);
  }
  return result;
}

export function createReachRuntime({ runtimeDir = RUNTIME, spawnImpl = spawn } = {}) {
  const exe = path.join(runtimeDir, 'agent-reach', EXE);
  const home = path.join(runtimeDir, 'agent-reach-home');
  const biliCredential = path.join(home, '.agent-reach', 'sessions', 'bili-cli', '.bilibili-cli', 'credential.json');
  let running = 0;
  const waiting = [];
  let version = null;

  const acquire = () => (running < PARALLEL ? (running++, Promise.resolve()) : new Promise((resolve) => waiting.push(resolve)));
  const release = () => { const next = waiting.shift(); if (next) next(); else running--; };

  async function installed() {
    try { await access(exe); } catch { throw new ReachError('电脑上还没有安装 Agent Reach：在 writing-server 目录运行 install-agent-reach.ps1', 503, 'reach_not_installed'); }
  }

  async function invoke(args) {
    await installed();
    const result = await runProcess(spawnImpl, exe, args, { cwd: runtimeDir, env: childEnv(home) });
    try { return JSON.parse(result.stdout); } catch {
      throw new ReachError(`Agent Reach 没有返回 JSON（退出码 ${result.code}）：${result.stderr.trim().slice(-300) || '无输出'}`, 502, 'reach_bad_output');
    }
  }

  /** One tool call from a phone chat. Agent Reach's own failures (ok:false) come back as results. */
  async function run(call) {
    const { action, args } = buildArgs(call);
    await acquire();
    try { return trimResult(await invoke(args), action); } finally { release(); }
  }

  async function status() {
    try {
      if (!version) {
        const answer = await invoke(['version']);
        if (answer?.ok !== true) throw new ReachError('Agent Reach 版本检查失败', 502, 'reach_bad_output');
        version = String(answer.result?.version ?? '');
      }
      const bilibiliLogin = await readFile(biliCredential, 'utf8').then((text) => Boolean(JSON.parse(text).sessdata)).catch(() => false);
      return { available: true, version, bilibili_login: bilibiliLogin, channels: Object.fromEntries(Object.entries(REACH_ACTIONS).map(([c, a]) => [c, Object.keys(a)])) };
    } catch (error) {
      return { available: false, error: error.message };
    }
  }

  return { run, status, paths: { exe, home } };
}
