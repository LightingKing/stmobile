import { spawn } from 'node:child_process';
import { mkdir, readFile, rename, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

/**
 * Bilibili QR login for Agent Reach, made from the PC dashboard. Subtitles need a logged-in
 * account; bili-cli 0.6.2 cannot finish a QR login since Bilibili moved the cookies into
 * Set-Cookie, so the server runs Bilibili's web QR flow itself and writes the credential where
 * Agent Reach's private bili-cli reads it. Nothing is taken from a browser; the user scans the code
 * with the Bilibili app, ideally on a spare account. Cookies only ever go to that file.
 */
const RUNTIME = fileURLToPath(new URL('../runtime/', import.meta.url));
const PASSPORT = 'https://passport.bilibili.com/x/passport-login/web/qrcode';
const HEADERS = {
  'user-agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36',
  referer: 'https://www.bilibili.com/',
};
const QR_LIFETIME_MS = 180_000;

export class BiliLoginError extends Error {
  constructor(message, status = 400, code = 'bili_login_error') { super(message); this.status = status; this.code = code; }
}

/** name -> value from Set-Cookie lines and the cross-domain URL Bilibili returns on success. */
export function loginCookies(setCookies, crossDomainUrl = '') {
  const cookies = {};
  for (const line of setCookies || []) {
    const pair = String(line).split(';')[0];
    const at = pair.indexOf('=');
    if (at > 0) cookies[pair.slice(0, at).trim()] = pair.slice(at + 1).trim();
  }
  try {
    for (const [name, value] of new URL(crossDomainUrl).searchParams) {
      if (['SESSDATA', 'bili_jct', 'DedeUserID'].includes(name) && !cookies[name]) cookies[name] = encodeURIComponent(value);
    }
  } catch { /* no URL */ }
  return cookies;
}

/** The fields bili-cli's save_credential writes. */
export function credentialFor(cookies, refreshToken, buvid = {}, savedAt = Date.now() / 1000) {
  if (!cookies.SESSDATA) throw new BiliLoginError('B 站返回的登录结果里没有 SESSDATA', 502, 'bili_login_incomplete');
  return {
    sessdata: cookies.SESSDATA, bili_jct: cookies.bili_jct || '', ac_time_value: refreshToken || '',
    buvid3: buvid.b_3 || cookies.buvid3 || '', buvid4: buvid.b_4 || cookies.buvid4 || '', dedeuserid: cookies.DedeUserID || '',
    saved_at: savedAt,
  };
}

/** Draws the QR with the qrcode package already in Agent Reach's bili-cli environment. */
function pythonQr(python) {
  return (text) => new Promise((resolve, reject) => {
    const script = 'import base64,io,sys,qrcode\nimg=qrcode.make(sys.stdin.read().strip(),box_size=8,border=2)\nb=io.BytesIO()\nimg.save(b,format="PNG")\nsys.stdout.write(base64.b64encode(b.getvalue()).decode())';
    const env = { SYSTEMROOT: process.env.SYSTEMROOT, PATH: process.env.PATH, PYTHONIOENCODING: 'utf-8' };
    let child;
    try { child = spawn(python, ['-I', '-c', script], { env, windowsHide: true }); } catch (error) { return reject(error); }
    let out = '';
    const timer = setTimeout(() => child.kill(), 20_000);
    child.stdout.on('data', (chunk) => { out += chunk; });
    child.on('error', reject);
    child.on('close', (code) => { clearTimeout(timer); code === 0 && out ? resolve(Buffer.from(out, 'base64')) : reject(new BiliLoginError('二维码生成失败', 500, 'bili_qr_failed')); });
    child.stdin.end(text);
  });
}

export function createBiliLogin({ runtimeDir = RUNTIME, fetchImpl = fetch, renderQr = null, now = Date.now } = {}) {
  const reachHome = path.join(runtimeDir, 'agent-reach-home', '.agent-reach');
  const file = path.join(reachHome, 'sessions', 'bili-cli', '.bilibili-cli', 'credential.json');
  const python = path.join(reachHome, 'tools', 'bili-cli', 'venv', process.platform === 'win32' ? 'Scripts' : 'bin', process.platform === 'win32' ? 'python.exe' : 'python');
  const drawQr = renderQr || pythonQr(python);
  let pending = null;
  let account = null;

  async function getJson(url, cookie = '') {
    const response = await fetchImpl(url, { headers: { ...HEADERS, ...(cookie ? { cookie } : {}) }, signal: AbortSignal.timeout(15_000) });
    if (!response.ok) throw new BiliLoginError(`B 站返回 HTTP ${response.status}`, 502, 'bili_http');
    return { body: await response.json(), response };
  }

  async function saved() {
    try { return JSON.parse(await readFile(file, 'utf8')); } catch { return null; }
  }

  /** A new QR code; any earlier one is dropped. */
  async function start() {
    const { body } = await getJson(`${PASSPORT}/generate?source=main-fe-header`);
    const { url, qrcode_key: key } = body?.data || {};
    if (body?.code !== 0 || !url || !key) throw new BiliLoginError('B 站没有返回二维码', 502, 'bili_qr_failed');
    pending = { key, png: await drawQr(url), startedAt: now() };
    return { state: 'waiting', expires_in: Math.round(QR_LIFETIME_MS / 1000) };
  }

  /** Asks Bilibili once whether the code was scanned; on success the credential is saved. */
  async function poll() {
    if (!pending) return { state: 'idle' };
    if (now() - pending.startedAt > QR_LIFETIME_MS) { pending = null; return { state: 'expired' }; }
    const { body, response } = await getJson(`${PASSPORT}/poll?qrcode_key=${encodeURIComponent(pending.key)}&source=main-fe-header`);
    const code = body?.data?.code;
    if (code === 86101) return { state: 'waiting' };
    if (code === 86090) return { state: 'scanned' };
    if (code === 86038) { pending = null; return { state: 'expired' }; }
    if (code !== 0) throw new BiliLoginError(`B 站登录返回 ${code}：${body?.data?.message || body?.message || ''}`, 502, 'bili_login_failed');
    const cookies = loginCookies(response.headers.getSetCookie?.() || [], body.data.url);
    const buvid = await getJson('https://api.bilibili.com/x/frontend/finger/spi').then(({ body: spi }) => spi?.data || {}).catch(() => ({}));
    const credential = credentialFor(cookies, body.data.refresh_token, buvid, now() / 1000);
    await mkdir(path.dirname(file), { recursive: true });
    const temp = `${file}.${process.pid}.tmp`;
    await writeFile(temp, JSON.stringify(credential, null, 2), { mode: 0o600 });
    await rename(temp, file);
    pending = null;
    account = null;
    return { state: 'done' };
  }

  function qrImage() {
    return pending && now() - pending.startedAt <= QR_LIFETIME_MS ? pending.png : null;
  }

  /** Whether a credential is saved; with check, Bilibili is asked whose it is (cached for a minute). */
  async function status({ check = false } = {}) {
    const credential = await saved();
    if (!credential?.sessdata) return { logged_in: false, pending: Boolean(qrImage()) };
    if (!check) return { logged_in: true, pending: Boolean(qrImage()) };
    if (!account || now() - account.at > 60_000) {
      const cookie = `SESSDATA=${credential.sessdata}${credential.bili_jct ? `; bili_jct=${credential.bili_jct}` : ''}`;
      const nav = await getJson('https://api.bilibili.com/x/web-interface/nav', cookie).then(({ body }) => body).catch(() => null);
      account = { at: now(), valid: nav ? nav.data?.isLogin === true : null, name: nav?.data?.uname || '' };
    }
    return { logged_in: account.valid !== false, valid: account.valid, name: account.name, pending: Boolean(qrImage()) };
  }

  async function logout() {
    pending = null;
    account = null;
    await rm(file, { force: true });
    return { logged_in: false };
  }

  return { start, poll, qrImage, status, logout, paths: { file } };
}
