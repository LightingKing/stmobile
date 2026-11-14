import { spawn } from 'node:child_process';
import { randomUUID } from 'node:crypto';
import { existsSync, readdirSync } from 'node:fs';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { ReachError, trimResult } from './reach.mjs';

/**
 * Douyin, TikTok, Weibo, Xiaohongshu and X for chats and the character drafter. They only answer
 * logged-in browsers, so this PC keeps one Chrome profile of its own (runtime/social-browser) that
 * the user logs into once per platform, in a window opened from the dashboard. Lookups run that
 * profile headless and drive it with OpenCLI (github.com/jackwener/opencli, installed with Agent
 * Reach) through Chrome's debugging port on loopback. Only read commands are offered; OpenCLI's
 * commands that post, delete, follow, like or comment are never built. The user's own browsers and
 * their logins are never read.
 */
const RUNTIME = fileURLToPath(new URL('../runtime/', import.meta.url));
const OPENCLI_MAIN = path.join('agent-reach-home', '.agent-reach', 'tools', 'npm-global', 'node_modules', '@jackwener', 'opencli', 'dist', 'src', 'main.js');

export const SOCIAL_PORT = 9335;
const CALL_TIMEOUT_SECONDS = 45;
const KILL_AFTER_MS = 80_000;
const START_WAIT_MS = 20_000;
const IDLE_CLOSE_MS = 10 * 60_000;
const MAX_OUTPUT_BYTES = 8 * 1024 * 1024;
const MAX_QUERY = 300;
const MAX_TARGET = 2000;
const PARALLEL = 2;

/**
 * What each platform offers. `cookies`: any one of these names on the platform's domains means a
 * login (visitors get none of them). `actions` map the tool's action to one fixed OpenCLI command;
 * `target(value)` turns a link or name into what the command takes.
 */
export const SOCIAL = {
  douyin: {
    // Douyin puts its captcha on the video search page, which is where lookups go.
    name: '抖音', domains: ['douyin.com', 'iesdouyin.com'], loginUrl: 'https://www.douyin.com/', cookies: ['sessionid', 'sessionid_ss'],
    verifyUrl: 'https://www.douyin.com/search/%E8%A7%92%E8%89%B2%E4%BB%8B%E7%BB%8D?type=video',
    actions: {
      search: { command: 'search', arg: 'query', limit: true },
      read: { command: 'detail', arg: 'target' },
      posts: { command: 'user-videos', arg: 'target', limit: true },
    },
  },
  tiktok: {
    name: 'TikTok', domains: ['tiktok.com'], loginUrl: 'https://www.tiktok.com/login', cookies: ['sessionid', 'sessionid_ss'],
    actions: {
      search: { command: 'search', arg: 'query', limit: true },
      user: { command: 'profile', arg: 'target', target: username },
      posts: { command: 'user', arg: 'target', limit: true, target: username },
    },
  },
  weibo: {
    name: '微博', domains: ['weibo.com', 'weibo.cn'], loginUrl: 'https://weibo.com/login.php', cookies: ['ALF', 'SSOLoginState', 'SCF'],
    actions: {
      search: { command: 'search', arg: 'query', limit: true },
      user: { command: 'user', arg: 'target', target: weiboUser },
      posts: { command: 'user-posts', arg: 'target', limit: true, target: weiboUser },
      comments: { command: 'comments', arg: 'target', limit: true, target: weiboPost },
    },
  },
  xiaohongshu: {
    // Visitors get a web_session cookie as well, so the open page is asked whether it is logged in.
    name: '小红书', domains: ['xiaohongshu.com', 'xhslink.com'], loginUrl: 'https://www.xiaohongshu.com/explore', cookies: ['web_session'],
    page: `(() => { const u = window.__INITIAL_STATE__ && window.__INITIAL_STATE__.user; if (!u) return null;
      const v = (x) => (x && typeof x === 'object' && '_rawValue' in x ? x._rawValue : x && typeof x === 'object' && 'value' in x ? x.value : x);
      const logged = v(u.loggedIn); return typeof logged === 'boolean' ? logged : null; })()`,
    actions: {
      search: { command: 'search', arg: 'query', limit: true },
      read: { command: 'note', arg: 'target' },
      comments: { command: 'comments', arg: 'target', limit: true },
      posts: { command: 'user', arg: 'target', limit: true },
    },
  },
  twitter: {
    name: 'X', domains: ['x.com', 'twitter.com'], loginUrl: 'https://x.com/i/flow/login', cookies: ['auth_token'],
    actions: {
      search: { command: 'search', arg: 'query', limit: true },
      read: { command: 'thread', arg: 'target', limit: true },
      user: { command: 'profile', arg: 'target', target: username },
      posts: { command: 'tweets', arg: 'target', limit: true, target: username },
    },
  },
};

export const SOCIAL_ACTIONS = ['search', 'read', 'user', 'posts', 'comments'];

function hostOf(value) {
  try { return new URL(/^[a-z][a-z0-9+.-]*:\/\//i.test(value) ? value : `https://${value}`).hostname.toLowerCase().replace(/\.$/, ''); } catch { return ''; }
}
const onDomain = (host, domains) => domains.some((domain) => host === domain || host.endsWith(`.${domain}`));

/** The platform a link belongs to, or ''. */
export function platformForLink(target) {
  const host = hostOf(String(target ?? ''));
  if (!host) return '';
  return Object.keys(SOCIAL).find((key) => onDomain(host, SOCIAL[key].domains)) || '';
}

/** "@name", "name" or a profile link → "name". */
function username(value) {
  const text = value.trim();
  if (/^https?:\/\//i.test(text)) {
    const first = new URL(text).pathname.split('/').filter(Boolean)[0] || '';
    return first.replace(/^@/, '');
  }
  return text.replace(/^@/, '');
}

/** A Weibo uid or screen name from "weibo.com/u/123", "weibo.com/123" or the name itself. */
function weiboUser(value) {
  const text = value.trim();
  if (!/^https?:\/\//i.test(text)) return text.replace(/^@/, '');
  const parts = new URL(text).pathname.split('/').filter(Boolean);
  if (parts[0] === 'u' && parts[1]) return parts[1];
  if (parts[0] === 'n' && parts[1]) return decodeURIComponent(parts[1]);
  return parts[0] || '';
}

/**
 * Weibo comments take a post's numeric id or its short id ("RpGhPp9h1", as search results and
 * post links give it); a post link is turned into the latter.
 */
function weiboPost(value) {
  let text = value.trim();
  if (/^https?:\/\//i.test(text)) {
    const parts = new URL(text).pathname.split('/').filter(Boolean);
    text = parts[0] === 'detail' || parts[0] === 'status' ? parts[1] || '' : parts.length >= 2 ? parts[1] : '';
  }
  if (/^\d{6,20}$/.test(text) || /^[A-Za-z0-9]{8,12}$/.test(text)) return text;
  throw new ReachError('微博评论要填微博帖子的链接或 ID', 400, 'reach_bad_target');
}

/** Checks one call and turns it into the fixed OpenCLI argument list. Never a free command line. */
export function buildSocialArgs(call) {
  if (call === null || typeof call !== 'object' || Array.isArray(call)) throw new ReachError('参数必须是 JSON 对象');
  const action = String(call.action ?? '').trim();
  const target = typeof call.target === 'string' ? call.target.trim() : '';
  let channel = String(call.channel ?? '').trim();
  if (!channel || channel === 'auto') channel = platformForLink(target);
  const platform = SOCIAL[channel];
  const spec = platform?.actions[action];
  if (!spec) {
    const offered = platform ? Object.keys(platform.actions).join('/') : Object.keys(SOCIAL).join('、');
    throw new ReachError(platform ? `${platform.name}不支持 ${action}；可用：${offered}` : `不支持的平台；可用：${offered}`, 400, 'reach_unsupported');
  }
  // Options first, then "--", then the one positional value: a query like "--help" stays a query.
  const args = [channel, spec.command];
  let positional;
  if (spec.arg === 'query') {
    const query = typeof call.query === 'string' ? call.query.trim() : '';
    if (!query) throw new ReachError('搜索需要 query');
    if (query.length > MAX_QUERY || /[\u0000-\u001f]/.test(query)) throw new ReachError(`query 最多 ${MAX_QUERY} 字，且不能含控制字符`);
    positional = query;
  } else {
    if (!target) throw new ReachError('需要 target（链接、ID 或用户名）');
    if (target.length > MAX_TARGET || /[\s\u0000-\u001f]/.test(target)) throw new ReachError(`target 最多 ${MAX_TARGET} 字，且不能含空白`);
    let value;
    try { value = spec.target ? spec.target(target) : target; } catch (error) {
      if (error instanceof ReachError) throw error;
      throw new ReachError('链接格式不对');
    }
    if (!value || value.startsWith('-')) throw new ReachError('链接里没有可用的用户名或 ID');
    positional = value;
  }
  if (spec.limit) {
    const limit = call.limit == null ? 5 : Number(call.limit);
    if (!Number.isInteger(limit) || limit < 1 || limit > 10) throw new ReachError('limit 必须是 1 到 10 的整数');
    args.push('--limit', String(limit));
  }
  args.push('-f', 'json', '--', positional);
  return { channel, action, args };
}

const text = (value) => (value == null ? '' : typeof value === 'string' ? value : typeof value === 'number' || typeof value === 'boolean' ? String(value) : '');
const firstText = (...values) => values.map(text).map((value) => value.trim()).find(Boolean) || '';
const short = (value, max = 60) => { const t = text(value).replace(/\s+/g, ' ').trim(); return t.length > max ? `${t.slice(0, max)}…` : t; };

const COUNTS = [['plays', '播放'], ['views', '浏览'], ['likes', '点赞'], ['digg', '点赞'], ['digg_count', '点赞'], ['comments', '评论'], ['comment', '评论'],
  ['followers', '粉丝'], ['follower_count', '粉丝'], ['reposts', '转发'], ['retweets', '转发'], ['statuses', '微博'], ['videos', '作品'], ['tweets', '推文']];

function commentLines(value) {
  const list = Array.isArray(value) ? value : [];
  return list.slice(0, 5).map((item) => (typeof item === 'string' ? item : firstText(item?.text, item?.content))).filter(Boolean);
}

/** One row of OpenCLI output as an item the chat model reads. */
function rowItem(row) {
  // A post's number lets the model ask for its comments or replies next (Weibo comments take it).
  const number = [row.id, row.aweme_id].map(text).find((value) => /^\d{6,24}$/.test(value)) || '';
  // An X profile's own `url` is the website in its bio; the profile itself is x.com/<name>.
  const profile = row.screen_name && 'bio' in row && !('text' in row) ? `https://x.com/${text(row.screen_name)}` : '';
  const main = firstText(row.desc, row.text, row.bio, row.description, row.title);
  const counts = COUNTS.filter(([key]) => row[key] != null && row[key] !== '').map(([key, label]) => `${label} ${text(row[key])}`);
  const quoted = row.quoted_tweet && typeof row.quoted_tweet === 'object' ? firstText(row.quoted_tweet.text) : '';
  const comments = commentLines(row.top_comments);
  const body = [main,
    quoted && `引用：${quoted}`,
    row.location && `所在地：${text(row.location)}`,
    row.verified === true && '已认证',
    counts.length && `（${[...new Set(counts)].join('，')}）`,
    comments.length && `热评：${comments.join(' / ')}`,
    number && `编号：${number}`].filter(Boolean).join('\n');
  return {
    title: firstText(row.title, row.word, row.screen_name && row.name ? `${row.name}（@${row.screen_name}）` : '', row.name, row.screen_name, row.nickname, row.username) || short(main),
    url: firstText(profile, row.url, row.share_url, row.profileUrl, row.profile_url),
    author: firstText(row.author, row.screen_name, row.nickname, row.username),
    published_at: firstText(row.published_at, row.created_at, row.time, row.created, row.date, row.createTime, row.create_time),
    text: body,
  };
}

/** OpenCLI's rows as items: a list of posts, one profile, or a note given as field/value pairs. */
export function socialItems(data) {
  const rows = Array.isArray(data) ? data : data && typeof data === 'object' ? [data] : [];
  if (rows.length && rows.every((row) => row && typeof row === 'object' && 'field' in row && 'value' in row)) {
    const fields = Object.fromEntries(rows.map((row) => [String(row.field), row.value]));
    const body = rows.filter((row) => !['url', 'link'].includes(String(row.field)))
      .map((row) => `${row.field}：${typeof row.value === 'string' ? row.value : JSON.stringify(row.value)}`).join('\n');
    return [{ title: firstText(fields.title, short(fields.desc)), url: firstText(fields.url, fields.link), author: firstText(fields.author, fields.nickname),
      published_at: firstText(fields.time, fields.published_at, fields.date), text: body }];
  }
  return rows.filter((row) => row && typeof row === 'object').map(rowItem);
}

/** OpenCLI's failure codes as the ones chats already know from Agent Reach. */
export function socialError(error, platformName) {
  const code = String(error?.code ?? '').toUpperCase();
  const message = String(error?.message ?? '').slice(0, 400);
  const low = message.toLowerCase();
  // Checked first: Douyin answers a verification wall with "…verify login/security state", which is not a lost login.
  if (/captcha|验证码|安全验证|环境异常|risk|security state|verify/.test(low)) {
    return { code: 'PLATFORM_BLOCKED', message, hint: `${platformName}要求验证：在管理网页打开${platformName}的登录窗口，完成页面上的验证后再试，查询放慢一些` };
  }
  if (code.includes('AUTH') || code.includes('LOGIN') || /not logged in|not signed in|please log ?in|login required|log in first|未登录|请登录|请先登录/.test(low)) {
    return { code: 'NEED_LOGIN', message: `电脑上的${platformName}没有登录或登录已过期`, hint: '在电脑的管理网页“社交平台”里重新登录' };
  }
  if (code === 'NOT_FOUND' || /no .*results|not found|没有找到/.test(low)) return { code: 'NOT_FOUND', message: message || '没有找到' };
  if (code.includes('RATE') || /429|too many|频繁/.test(low)) return { code: 'RATE_LIMITED', message, hint: '过一会儿再试' };
  if (code.includes('TIMEOUT') || /timeout|timed out|超时/.test(low)) return { code: 'TIMEOUT', message: message || '超时' };
  return { code: 'UPSTREAM_BROKEN', message: message || `${platformName}没有返回结果`, hint: error?.hint ? String(error.hint).slice(0, 300) : undefined };
}

/** The installed Chrome (or Edge), and the user agent a headless window should send: an ordinary Chrome one. */
export function findChrome(env = process.env) {
  const roots = [env.PROGRAMFILES, env['PROGRAMFILES(X86)'], env.LOCALAPPDATA].filter(Boolean);
  for (const [folder, exe] of [[['Google', 'Chrome', 'Application'], 'chrome.exe'], [['Microsoft', 'Edge', 'Application'], 'msedge.exe']]) {
    for (const root of roots) {
      const dir = path.join(root, ...folder);
      const file = path.join(dir, exe);
      if (!existsSync(file)) continue;
      let major = '';
      try { major = readdirSync(dir).filter((name) => /^\d+\.\d+\.\d+\.\d+$/.test(name)).map((name) => name.split('.')[0]).sort((a, b) => b - a)[0] || ''; } catch { /* keep the default */ }
      return { path: file, major: major || '150' };
    }
  }
  return null;
}

export function userAgent(major) {
  return `Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${major}.0.0.0 Safari/537.36`;
}

/**
 * The dedicated Chrome: headless for lookups, a visible window for logging in. One profile, so
 * only one of the two runs at a time. Everything goes through the loopback debugging port.
 */
export function createSocialBrowser({ runtimeDir = RUNTIME, port = SOCIAL_PORT, chrome = findChrome(), spawnImpl = spawn, fetchImpl = fetch } = {}) {
  const profile = path.join(runtimeDir, 'social-browser', 'profile');
  const marker = path.join(runtimeDir, 'social-browser', 'mode.txt');
  const endpoint = `http://127.0.0.1:${port}`;
  let idleTimer = null;
  let starting = null;

  async function version() {
    try {
      const response = await fetchImpl(`${endpoint}/json/version`, { signal: AbortSignal.timeout(2500) });
      return response.ok ? await response.json() : null;
    } catch { return null; }
  }

  /**
   * 'off', 'headless' or 'window' (a login window the user is using). Which one was launched is
   * written down, since a headless browser sends an ordinary user agent; when unsure it counts as
   * a window, which is never closed for a lookup.
   */
  async function mode() {
    if (!await version()) return 'off';
    const written = await readFile(marker, 'utf8').catch(() => '');
    return written.trim() === 'headless' ? 'headless' : 'window';
  }

  async function cdp(method, params = {}) {
    const info = await version();
    if (!info?.webSocketDebuggerUrl) throw new ReachError('专用浏览器没有运行', 503, 'social_browser_off');
    return cdpAt(info.webSocketDebuggerUrl, method, params);
  }

  async function cdpAt(url, method, params = {}) {
    const socket = new WebSocket(url);
    try {
      await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('timeout')), 5000);
        socket.onopen = () => { clearTimeout(timer); resolve(); };
        socket.onerror = () => { clearTimeout(timer); reject(new Error('cdp')); };
      });
      return await new Promise((resolve, reject) => {
        const timer = setTimeout(() => reject(new Error('timeout')), 8000);
        socket.onmessage = (event) => {
          const reply = JSON.parse(String(event.data));
          if (reply.id !== 1) return;
          clearTimeout(timer);
          if (reply.error) reject(new Error(reply.error.message || 'cdp error')); else resolve(reply.result || {});
        };
        socket.send(JSON.stringify({ id: 1, method, params }));
      });
    } finally { try { socket.close(); } catch { /* closed */ } }
  }

  async function waitFor(check, ms) {
    const until = Date.now() + ms;
    while (Date.now() < until) { if (await check()) return true; await new Promise((resolve) => setTimeout(resolve, 300)); }
    return false;
  }

  async function launch(url, headless) {
    if (!chrome) throw new ReachError('这台电脑上没有找到 Chrome 或 Edge', 503, 'social_no_chrome');
    await mkdir(profile, { recursive: true });
    await writeFile(marker, headless ? 'headless' : 'window');
    const args = [`--remote-debugging-address=127.0.0.1`, `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`,
      '--no-first-run', '--no-default-browser-check', '--lang=zh-CN', '--window-size=1280,900', '--disable-blink-features=AutomationControlled'];
    if (headless) args.push('--headless=new', `--user-agent=${userAgent(chrome.major)}`);
    args.push(url);
    const child = spawnImpl(chrome.path, args, { detached: true, stdio: 'ignore', windowsHide: headless });
    child.on?.('error', () => { /* reported by the wait below */ });
    child.unref?.();
  }

  /** Closes the browser properly, so it writes its cookies to disk. */
  async function close() {
    clearTimeout(idleTimer);
    if (!await version()) return true;
    try { await cdp('Browser.close'); } catch { /* it may close before answering */ }
    return waitFor(async () => !await version(), 10_000);
  }

  function scheduleIdleClose() {
    clearTimeout(idleTimer);
    idleTimer = setTimeout(async () => { if (await mode() === 'headless') await close(); }, IDLE_CLOSE_MS);
    idleTimer.unref?.();
  }

  /** A headless browser ready for lookups; refused while a login window is open. Two lookups start it once. */
  async function ensureHeadless() {
    while (starting) await starting.catch(() => null);
    const now = await mode();
    if (now === 'window') throw new ReachError('电脑上正在用登录窗口，关掉或在管理网页点“登录完成”后再查', 409, 'social_login_window');
    if (now === 'off') {
      starting = (async () => {
        await launch('about:blank', true);
        if (!await waitFor(async () => Boolean(await version()), START_WAIT_MS)) throw new ReachError('专用浏览器没有启动', 503, 'social_browser_failed');
      })();
      try { await starting; } finally { starting = null; }
    }
    scheduleIdleClose();
    return endpoint;
  }

  /** A visible window on [url]; the headless browser is closed first, since both use one profile. */
  async function openWindow(url) {
    const now = await mode();
    if (now === 'window') {
      await fetchImpl(`${endpoint}/json/new?${encodeURIComponent(url)}`, { method: 'PUT', signal: AbortSignal.timeout(5000) }).catch(() => null);
      return;
    }
    if (now === 'headless') await close();
    await launch(url, false);
    if (!await waitFor(async () => Boolean(await version()), START_WAIT_MS)) throw new ReachError('登录窗口没有打开', 503, 'social_browser_failed');
  }

  /** A tab of its own for one lookup, found again by its unique title. */
  async function openTab() {
    const tag = `pocket-${randomUUID()}`;
    const response = await fetchImpl(`${endpoint}/json/new?${encodeURIComponent(`data:text/html,<title>${tag}</title>`)}`, { method: 'PUT', signal: AbortSignal.timeout(5000) });
    const target = await response.json();
    return { tag, id: target.id };
  }

  async function closeTab(id) {
    if (!id) return;
    await fetchImpl(`${endpoint}/json/close/${encodeURIComponent(id)}`, { signal: AbortSignal.timeout(5000) }).catch(() => null);
  }

  /**
   * Runs [expression] in an open tab of one of [domains] and returns its value, or null when no
   * such tab is open. Used only to ask a page whether it is logged in.
   */
  async function evaluateOn(domains, expression) {
    const response = await fetchImpl(`${endpoint}/json/list`, { signal: AbortSignal.timeout(3000) });
    const tab = (await response.json()).find((target) => target.type === 'page' && onDomain(hostOf(String(target.url || '')), domains));
    if (!tab?.webSocketDebuggerUrl) return null;
    const result = await cdpAt(tab.webSocketDebuggerUrl, 'Runtime.evaluate', { expression, returnByValue: true });
    return result?.result?.value ?? null;
  }

  /** Names of the cookies set for each platform; values are never read out. */
  async function cookieNames() {
    const { cookies = [] } = await cdp('Storage.getCookies');
    const names = {};
    for (const key of Object.keys(SOCIAL)) names[key] = new Set(cookies.filter((cookie) => onDomain(String(cookie.domain || '').replace(/^\./, ''), SOCIAL[key].domains)).map((cookie) => cookie.name));
    return names;
  }

  /** Forgets one platform's login by expiring its cookies in the dedicated profile. */
  async function forget(key) {
    const { cookies = [] } = await cdp('Storage.getCookies');
    const mine = cookies.filter((cookie) => onDomain(String(cookie.domain || '').replace(/^\./, ''), SOCIAL[key].domains));
    if (mine.length) await cdp('Storage.setCookies', { cookies: mine.map(({ name, domain, path: cookiePath, secure, httpOnly, sameSite }) => ({ name, value: '', domain, path: cookiePath, secure, httpOnly, sameSite, expires: 1 })) });
    return mine.length;
  }

  return { endpoint, profile, chrome, mode, ensureHeadless, openWindow, close, openTab, closeTab, cookieNames, forget, evaluateOn };
}

function childEnv(home, extra) {
  const keep = ['PATH', 'Path', 'SYSTEMROOT', 'SystemRoot', 'WINDIR', 'TEMP', 'TMP', 'PATHEXT', 'COMSPEC'];
  const env = Object.fromEntries(keep.filter((name) => process.env[name] !== undefined).map((name) => [name, process.env[name]]));
  // OpenCLI keeps its own state under the home directory; it gets a private one.
  return { ...env, HOME: home, USERPROFILE: home, ...extra };
}

function runProcess(spawnImpl, command, args, { cwd, env, timeoutMs = KILL_AFTER_MS }) {
  return new Promise((resolve, reject) => {
    let child;
    try { child = spawnImpl(command, args, { cwd, env, windowsHide: true }); } catch (error) { return reject(error); }
    const out = [];
    let size = 0;
    let settled = false;
    const finish = (fn, value) => { if (!settled) { settled = true; clearTimeout(timer); fn(value); } };
    const timer = setTimeout(() => { child.kill(); finish(reject, new ReachError('查询超过 80 秒没有完成', 504, 'reach_timeout')); }, timeoutMs);
    child.stdout.on('data', (chunk) => {
      size += chunk.length;
      if (size > MAX_OUTPUT_BYTES) { child.kill(); finish(reject, new ReachError('返回内容过大', 502, 'reach_output_too_large')); return; }
      out.push(chunk);
    });
    child.stderr.on('data', () => { /* Node warnings; the answer is on stdout */ });
    child.on('error', (error) => finish(reject, error));
    child.on('close', (code) => finish(resolve, { code, stdout: Buffer.concat(out).toString('utf8') }));
    child.stdin?.end();
  });
}

export function createSocialRuntime({ runtimeDir = RUNTIME, spawnImpl = spawn, browser = null, node = process.execPath } = {}) {
  const dedicated = browser || createSocialBrowser({ runtimeDir });
  const opencli = path.join(runtimeDir, OPENCLI_MAIN);
  const home = path.join(runtimeDir, 'social-browser', 'home');
  const statePath = path.join(runtimeDir, 'social-browser', 'logins.json');
  let running = 0;
  const waiting = [];
  const busyPlatforms = new Set();

  const acquire = () => (running < PARALLEL ? (running++, Promise.resolve()) : new Promise((resolve) => waiting.push(resolve)));
  const release = () => { const next = waiting.shift(); if (next) next(); else running--; };

  async function saved() { try { return JSON.parse(await readFile(statePath, 'utf8')); } catch { return {}; } }
  async function save(logins) {
    await mkdir(path.dirname(statePath), { recursive: true });
    await writeFile(statePath, JSON.stringify(logins, null, 2));
    return logins;
  }

  /**
   * Which platforms are logged in, read from the running browser, or as last seen when it is
   * closed. A platform whose cookies cannot tell (Xiaohongshu) is asked through its open page;
   * with no such page, its last known state stands as long as its cookie is still there.
   */
  async function logins({ live = true, clearBlocks = false } = {}) {
    const known = await saved();
    if (!live || await dedicated.mode() === 'off') return known;
    try {
      const names = await dedicated.cookieNames();
      const now = new Date().toISOString();
      const next = {};
      for (const [key, platform] of Object.entries(SOCIAL)) {
        let logged = platform.cookies.some((name) => names[key].has(name));
        if (logged && platform.page) {
          const answer = await dedicated.evaluateOn(platform.domains, platform.page).catch(() => null);
          logged = answer === null ? known[key]?.logged_in === true : answer === true;
        }
        // A verification note stays until the user has had the window open again.
        next[key] = { logged_in: logged, checked_at: now, ...(!clearBlocks && logged && known[key]?.blocked_at ? { blocked_at: known[key].blocked_at } : {}) };
      }
      return await save(next);
    } catch { return known; }
  }

  /** The platform said it needs a login: it counts as logged out until the next login. */
  async function markLoggedOut(key) {
    const known = await saved();
    return save({ ...known, [key]: { logged_in: false, checked_at: new Date().toISOString() } });
  }

  /** The platform showed a verification page: remembered until the user opens the window again. */
  async function markBlocked(key) {
    const known = await saved();
    return save({ ...known, [key]: { ...(known[key] || { logged_in: true }), blocked_at: new Date().toISOString() } });
  }

  async function installed() {
    if (!existsSync(opencli)) throw new ReachError('电脑上还没有安装 OpenCLI：在 writing-server 目录运行 install-agent-reach.ps1', 503, 'reach_not_installed');
  }

  /** One read from a chat or the character drafter. Platform failures come back as results (ok:false). */
  async function run(call) {
    const { channel, action, args } = buildSocialArgs(call);
    const platform = SOCIAL[channel];
    const failed = (error) => trimResult({ ok: false, channel, action, backend: 'opencli', items: [], error }, action);
    await installed();
    const known = (await saved())[channel];
    if (known && known.logged_in === false) return failed({ code: 'NEED_LOGIN', message: `电脑上的${platform.name}还没有登录`, hint: '在电脑的管理网页“社交平台”里登录' });
    // One read per platform at a time keeps its risk control calm; two platforms may run side by side.
    while (busyPlatforms.has(channel)) await new Promise((resolve) => setTimeout(resolve, 200));
    busyPlatforms.add(channel);
    await acquire();
    let tab = null;
    try {
      const endpoint = await dedicated.ensureHeadless();
      tab = await dedicated.openTab();
      const result = await runProcess(spawnImpl, node, [opencli, ...args], {
        cwd: runtimeDir,
        env: childEnv(home, { OPENCLI_CDP_ENDPOINT: endpoint, OPENCLI_CDP_TARGET: tab.tag, OPENCLI_BROWSER_COMMAND_TIMEOUT: String(CALL_TIMEOUT_SECONDS), OPENCLI_BROWSER_CONNECT_TIMEOUT: '20' }),
      });
      const start = result.stdout.indexOf('{');
      let envelope = null;
      try { envelope = start >= 0 ? JSON.parse(result.stdout.slice(start)) : null; } catch { envelope = null; }
      if (!envelope) return failed({ code: 'UPSTREAM_BROKEN', message: `OpenCLI 没有返回 JSON（退出码 ${result.code}）` });
      if (envelope.ok === false) {
        const error = socialError(envelope.error, platform.name);
        if (error.code === 'NEED_LOGIN') await markLoggedOut(channel).catch(() => null);
        if (error.code === 'PLATFORM_BLOCKED') await markBlocked(channel).catch(() => null);
        return failed(error);
      }
      return trimResult({ ok: true, channel, action, backend: 'opencli', items: socialItems(envelope.data ?? envelope), error: null }, action);
    } finally {
      if (tab) await dedicated.closeTab(tab.id);
      release();
      busyPlatforms.delete(channel);
    }
  }

  /** True when a call is for one of these platforms (by channel, or by the link it reads). */
  function handles(call) {
    if (!call || typeof call !== 'object') return false;
    const channel = String(call.channel ?? '').trim();
    if (SOCIAL[channel]) return true;
    if (channel && channel !== 'auto') return false;
    return ['read', 'user', 'posts', 'comments'].includes(String(call.action ?? '')) && Boolean(platformForLink(String(call.target ?? '')));
  }

  async function status() {
    const mode = await dedicated.mode().catch(() => 'off');
    const known = await logins({ live: mode !== 'off' });
    return {
      available: existsSync(opencli) && Boolean(dedicated.chrome),
      browser: dedicated.chrome ? path.basename(dedicated.chrome.path) : '',
      mode,
      platforms: Object.fromEntries(Object.entries(SOCIAL).map(([key, platform]) => [key, {
        name: platform.name, logged_in: known[key]?.logged_in === true, checked_at: known[key]?.checked_at || null,
        blocked_at: known[key]?.blocked_at || null, verify: Boolean(platform.verifyUrl),
        actions: Object.keys(platform.actions),
      }])),
    };
  }

  /** Opens the login window on one platform's login page, or on its verification page; the user acts there. */
  async function openLogin(key, { verify = false } = {}) {
    const platform = SOCIAL[key];
    if (!platform) throw new ReachError('不认识的平台');
    if (running > 0) throw new ReachError('有查询正在进行，稍后再开登录窗口', 409, 'social_busy');
    await dedicated.openWindow(verify && platform.verifyUrl ? platform.verifyUrl : platform.loginUrl);
    return { ok: true, mode: 'window' };
  }

  /** Reads who is logged in from the window, then closes it so lookups can use the profile. */
  async function finishLogin() {
    const result = await logins({ clearBlocks: true });
    await dedicated.close();
    return { ok: true, platforms: result };
  }

  async function logout(key) {
    if (!SOCIAL[key]) throw new ReachError('不认识的平台');
    if (await dedicated.mode() === 'off') await dedicated.ensureHeadless();
    const cleared = await dedicated.forget(key);
    const result = await logins();
    return { ok: true, cleared, platforms: result };
  }

  return { run, handles, status, openLogin, finishLogin, logout, logins, paths: { opencli, home, statePath } };
}
