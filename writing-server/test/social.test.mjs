import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createServer } from '../src/server.mjs';
import { createVault } from '../src/vault.mjs';
import { createAdminServer } from '../src/admin.mjs';
import { SOCIAL, buildSocialArgs, createSocialRuntime, platformForLink, socialError, socialItems, userAgent } from '../src/social.mjs';

/** OpenCLI's read commands this server may run; anything else (publish, delete, follow, like, comment…) never. */
const READ_COMMANDS = new Set(['search', 'detail', 'user-videos', 'profile', 'user', 'user-posts', 'comments', 'note', 'thread', 'tweets']);

test('every offered action is one fixed read command; links and names become what the command takes', () => {
  for (const [key, platform] of Object.entries(SOCIAL)) {
    for (const [action, spec] of Object.entries(platform.actions)) assert.ok(READ_COMMANDS.has(spec.command), `${key} ${action} -> ${spec.command}`);
  }
  assert.deepEqual(buildSocialArgs({ channel: 'douyin', action: 'search', query: '五更琉璃' }).args, ['douyin', 'search', '--limit', '5', '-f', 'json', '--', '五更琉璃']);
  assert.deepEqual(buildSocialArgs({ channel: 'xiaohongshu', action: 'search', query: '--help', limit: 3 }).args, ['xiaohongshu', 'search', '--limit', '3', '-f', 'json', '--', '--help'],
    'a query that looks like an option is still the query');
  const head = (call) => { const args = buildSocialArgs(call).args; return [args[0], args[1], args.at(-1)]; };
  assert.deepEqual(head({ channel: 'tiktok', action: 'posts', target: 'https://www.tiktok.com/@some.one?lang=en' }), ['tiktok', 'user', 'some.one']);
  assert.deepEqual(head({ channel: 'tiktok', action: 'user', target: '@some.one' }), ['tiktok', 'profile', 'some.one']);
  assert.deepEqual(head({ channel: 'weibo', action: 'posts', target: 'https://weibo.com/u/1234567890' }), ['weibo', 'user-posts', '1234567890']);
  assert.deepEqual(head({ channel: 'weibo', action: 'user', target: 'https://weibo.com/1234567890/Abcdef' }), ['weibo', 'user', '1234567890']);
  assert.deepEqual(head({ channel: 'weibo', action: 'comments', target: '5012345678901234' }), ['weibo', 'comments', '5012345678901234']);
  // Seen on this PC: Weibo comments accept the short id that search results and post links carry.
  assert.deepEqual(head({ channel: 'weibo', action: 'comments', target: 'RpGhPp9h1' }), ['weibo', 'comments', 'RpGhPp9h1']);
  assert.deepEqual(head({ channel: 'weibo', action: 'comments', target: 'https://weibo.com/2857987344/RpGhPp9h1?refer_flag=1' }), ['weibo', 'comments', 'RpGhPp9h1']);
  assert.deepEqual(head({ channel: 'weibo', action: 'comments', target: 'https://m.weibo.cn/detail/5012345678901234' }), ['weibo', 'comments', '5012345678901234']);
  assert.deepEqual(head({ channel: 'twitter', action: 'posts', target: 'https://x.com/someone/status/1' }), ['twitter', 'tweets', 'someone']);
  // A link alone picks its platform.
  assert.equal(buildSocialArgs({ action: 'read', target: 'https://v.douyin.com/abc123/' }).channel, 'douyin');
  assert.equal(buildSocialArgs({ channel: 'auto', action: 'read', target: 'https://x.com/someone/status/1234567890' }).args[1], 'thread');
  assert.equal(buildSocialArgs({ action: 'read', target: 'https://www.xiaohongshu.com/explore/65f0?xsec_token=AB' }).args.at(-1), 'https://www.xiaohongshu.com/explore/65f0?xsec_token=AB');

  for (const bad of [
    { channel: 'douyin', action: 'comments', target: 'x' },
    { channel: 'douyin', action: 'publish', target: 'x' },
    { channel: 'weibo', action: 'post', query: 'x' },
    { channel: 'facebook', action: 'search', query: 'x' },
    { channel: 'douyin', action: 'search' },
    { channel: 'douyin', action: 'search', query: 'x'.repeat(301) },
    { channel: 'douyin', action: 'search', query: 'a\nb' },
    { channel: 'douyin', action: 'read', target: 'has space' },
    { channel: 'douyin', action: 'search', query: 'x', limit: 11 },
    { channel: 'weibo', action: 'comments', target: 'https://weibo.com/hot' },
    { channel: 'tiktok', action: 'user', target: 'https://www.tiktok.com/' },
    { channel: 'tiktok', action: 'user', target: '--version' },
    null, [],
  ]) assert.throws(() => buildSocialArgs(bad), (error) => error.status === 400, JSON.stringify(bad));
});

test('links are told apart by their site, not by look-alike names', () => {
  assert.equal(platformForLink('https://www.douyin.com/video/1'), 'douyin');
  assert.equal(platformForLink('https://www.iesdouyin.com/share/video/1'), 'douyin');
  assert.equal(platformForLink('http://xhslink.com/a/b'), 'xiaohongshu');
  assert.equal(platformForLink('https://m.weibo.cn/detail/1'), 'weibo');
  assert.equal(platformForLink('https://mobile.twitter.com/a'), 'twitter');
  assert.equal(platformForLink('https://evildouyin.com/x'), '');
  assert.equal(platformForLink('https://douyin.com.evil.example/x'), '');
  assert.equal(platformForLink('not a link'), '');
  assert.equal(userAgent('157'), 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/157.0.0.0 Safari/537.36');
});

test('OpenCLI rows become items a chat reads; notes given as fields become one item', () => {
  const posts = socialItems([{ rank: 1, desc: '黑猫的cos', author: '某人', url: 'https://www.douyin.com/video/1', plays: '1.2万', likes: 300 }]);
  assert.deepEqual(posts[0], { title: '黑猫的cos', url: 'https://www.douyin.com/video/1', author: '某人', published_at: '', text: '黑猫的cos\n（播放 1.2万，点赞 300）' });
  const profile = socialItems({ screen_name: 'ruri', name: '五更', bio: '堕天圣黑猫', followers: 10, verified: true, location: '千叶', url: 'https://t.co/site' })[0];
  assert.equal(profile.title, '五更（@ruri）');
  assert.equal(profile.url, 'https://x.com/ruri', 'the profile, not the website in its bio');
  const weiboPost = socialItems([{ rank: 1, id: '5012345678901234', title: '微博正文', author: 'a', url: 'https://weibo.com/1/AbC' }])[0];
  assert.match(weiboPost.text, /编号：5012345678901234$/, 'the number Weibo comments take');
  assert.match(profile.text, /^堕天圣黑猫\n所在地：千叶\n已认证\n（粉丝 10）$/);
  const note = socialItems([{ field: 'title', value: '黑猫仿妆' }, { field: 'desc', value: '正文' }, { field: 'author', value: '小红' }, { field: 'url', value: 'https://www.xiaohongshu.com/explore/1' }, { field: 'tags', value: ['cos', '黑猫'] }]);
  assert.equal(note.length, 1);
  assert.equal(note[0].title, '黑猫仿妆');
  assert.equal(note[0].url, 'https://www.xiaohongshu.com/explore/1');
  assert.match(note[0].text, /desc：正文/);
  assert.match(note[0].text, /tags：\["cos","黑猫"\]/);
  assert.doesNotMatch(note[0].text, /url：/);
  const withComments = socialItems([{ title: '视频', top_comments: [{ text: '好可爱' }, '哈哈'] }])[0];
  assert.match(withComments.text, /热评：好可爱 \/ 哈哈/);
  assert.deepEqual(socialItems(null), []);
});

test('platform failures are reported with the codes chats already know', () => {
  assert.equal(socialError({ code: 'AUTH_REQUIRED', message: 'x' }, '抖音').code, 'NEED_LOGIN');
  assert.equal(socialError({ code: 'COMMAND_EXEC', message: 'Please log in first' }, '抖音').code, 'NEED_LOGIN');
  assert.equal(socialError({ code: 'NOT_FOUND', message: 'No Weibo search results found' }, '微博').code, 'NOT_FOUND');
  assert.equal(socialError({ code: 'COMMAND_EXEC', message: '请完成安全验证' }, '小红书').code, 'PLATFORM_BLOCKED');
  assert.equal(socialError({ code: 'X', message: 'HTTP 429 Too Many Requests' }, 'X').code, 'RATE_LIMITED');
  assert.equal(socialError({ code: 'TIMEOUT', message: '' }, 'X').code, 'TIMEOUT');
  assert.equal(socialError({ code: 'COMMAND_EXEC', message: 'Douyin search did not render' }, '抖音').code, 'UPSTREAM_BROKEN');
  // Seen on this PC: Douyin's search page showed a captcha to the logged-in headless browser.
  assert.equal(socialError({ code: 'COMMAND_EXEC', message: 'Douyin search did not render result cards within the timeout. Open the same search in Chrome and verify login/security state before retrying.' }, '抖音').code, 'PLATFORM_BLOCKED');
  assert.equal(socialError({ code: 'COMMAND_EXEC', message: 'Please log in to continue' }, '小红书').code, 'NEED_LOGIN');
});

/** A dedicated browser that records what it was asked to do. */
function fakeBrowser({ mode = 'off', cookies = {}, page = null } = {}) {
  const log = [];
  let current = mode;
  return {
    log, chrome: { path: 'C:/chrome.exe', major: '157' },
    mode: async () => current,
    ensureHeadless: async () => {
      if (current === 'window') throw Object.assign(new Error('电脑上正在用登录窗口'), { status: 409, code: 'social_login_window' });
      current = 'headless'; log.push('ensure'); return 'http://127.0.0.1:9335';
    },
    openWindow: async (url) => { current = 'window'; log.push(`window ${url}`); },
    close: async () => { current = 'off'; log.push('close'); return true; },
    openTab: async () => { log.push('tab'); return { tag: 'pocket-test-tag', id: 'T1' }; },
    closeTab: async (id) => { log.push(`close-tab ${id}`); },
    cookieNames: async () => Object.fromEntries(Object.keys(SOCIAL).map((key) => [key, new Set(cookies[key] || [])])),
    forget: async (key) => { log.push(`forget ${key}`); cookies[key] = []; return 3; },
    evaluateOn: async () => page,
  };
}

/** Stands in for OpenCLI: prints one answer and reports the arguments and environment it got. */
const fakeOpencli = (answer, seen) => (command, args, options) => {
  seen.push({ command, args, env: options.env });
  return spawn(process.execPath, ['-e', `process.stdout.write('(node:1) Warning: x\\n' + ${JSON.stringify(JSON.stringify(answer))})`], options);
};

async function runtimeDir() {
  const dir = await fs.mkdtemp(path.join(os.tmpdir(), 'pocket-social-'));
  const main = path.join(dir, 'agent-reach-home', '.agent-reach', 'tools', 'npm-global', 'node_modules', '@jackwener', 'opencli', 'dist', 'src', 'main.js');
  await fs.mkdir(path.dirname(main), { recursive: true });
  await fs.writeFile(main, '');
  return dir;
}

test('a lookup runs OpenCLI on its own tab of the dedicated browser, with a private home and no secrets', async () => {
  const dir = await runtimeDir();
  const seen = [];
  const browser = fakeBrowser({ cookies: { douyin: ['sessionid'] } });
  const social = createSocialRuntime({ runtimeDir: dir, browser, spawnImpl: fakeOpencli({ ok: true, data: [{ desc: '黑猫', author: 'a', url: 'https://www.douyin.com/video/1' }] }, seen) });
  process.env.VENICE_API_KEY = 'must-not-leak';
  try {
    const answer = await social.run({ channel: 'douyin', action: 'search', query: '五更琉璃', limit: 2 });
    assert.equal(answer.ok, true);
    assert.equal(answer.backend, 'opencli');
    assert.equal(answer.items[0].title, '黑猫');
    const [{ command, args, env }] = seen;
    assert.equal(command, process.execPath);
    assert.ok(args[0].endsWith(path.join('opencli', 'dist', 'src', 'main.js')));
    assert.deepEqual(args.slice(1), ['douyin', 'search', '--limit', '2', '-f', 'json', '--', '五更琉璃']);
    assert.equal(env.OPENCLI_CDP_ENDPOINT, 'http://127.0.0.1:9335');
    assert.equal(env.OPENCLI_CDP_TARGET, 'pocket-test-tag');
    assert.equal(env.USERPROFILE, path.join(dir, 'social-browser', 'home'));
    assert.equal(env.HOME, env.USERPROFILE);
    for (const name of ['VENICE_API_KEY', 'LOCALAPPDATA', 'APPDATA']) assert.equal(env[name], undefined, name);
    assert.deepEqual(browser.log, ['ensure', 'tab', 'close-tab T1']);
  } finally { delete process.env.VENICE_API_KEY; }
});

test('logins are read from the cookie names; a platform known to be logged out is not even tried', async () => {
  const dir = await runtimeDir();
  const seen = [];
  const browser = fakeBrowser({ mode: 'headless', cookies: { weibo: ['SUB', 'SUBP'], twitter: ['auth_token', 'ct0'] } });
  const social = createSocialRuntime({ runtimeDir: dir, browser, spawnImpl: fakeOpencli({ ok: true, data: [] }, seen) });
  const status = await social.status();
  assert.equal(status.platforms.twitter.logged_in, true);
  assert.equal(status.platforms.weibo.logged_in, false, 'visitor cookies are not a login');
  assert.deepEqual(status.platforms.douyin.actions, ['search', 'read', 'posts']);
  const refused = await social.run({ channel: 'weibo', action: 'search', query: 'x' });
  assert.equal(refused.ok, false);
  assert.equal(refused.error.code, 'NEED_LOGIN');
  assert.equal(seen.length, 0);
  // With the browser closed, the last check is remembered.
  const closed = createSocialRuntime({ runtimeDir: dir, browser: fakeBrowser(), spawnImpl: fakeOpencli({ ok: true, data: [] }, seen) });
  assert.equal((await closed.status()).platforms.twitter.logged_in, true);
});

test('Xiaohongshu visitors have the cookie too, so its page decides; a refused lookup marks a platform logged out', async () => {
  const dir = await runtimeDir();
  const visitor = createSocialRuntime({ runtimeDir: dir, browser: fakeBrowser({ mode: 'window', cookies: { xiaohongshu: ['web_session'] }, page: false }) });
  assert.equal((await visitor.logins()).xiaohongshu.logged_in, false);
  const member = createSocialRuntime({ runtimeDir: dir, browser: fakeBrowser({ mode: 'window', cookies: { xiaohongshu: ['web_session'] }, page: true }) });
  assert.equal((await member.logins()).xiaohongshu.logged_in, true);
  // Headless, with no Xiaohongshu page open: the last answer stands while the cookie is there.
  const headless = createSocialRuntime({ runtimeDir: dir, browser: fakeBrowser({ mode: 'headless', cookies: { xiaohongshu: ['web_session'] }, page: null }) });
  assert.equal((await headless.logins()).xiaohongshu.logged_in, true);
  const seen = [];
  const refusing = createSocialRuntime({ runtimeDir: dir, browser: fakeBrowser({ mode: 'headless', cookies: { xiaohongshu: ['web_session'] }, page: null }),
    spawnImpl: fakeOpencli({ ok: false, error: { code: 'AUTH_REQUIRED', message: 'login required' } }, seen) });
  assert.equal((await refusing.run({ channel: 'xiaohongshu', action: 'search', query: 'x' })).error.code, 'NEED_LOGIN');
  assert.equal((await refusing.logins({ live: false })).xiaohongshu.logged_in, false);
  assert.equal((await refusing.logins()).xiaohongshu.logged_in, false, 'and stays so while no page says otherwise');
});

test('a verification wall is remembered for the dashboard until the next login', async () => {
  const dir = await runtimeDir();
  const seen = [];
  const blocked = createSocialRuntime({ runtimeDir: dir, browser: fakeBrowser({ mode: 'headless', cookies: { douyin: ['sessionid'] } }),
    spawnImpl: fakeOpencli({ ok: false, error: { code: 'COMMAND_EXEC', message: 'Douyin search did not render result cards within the timeout. Open the same search in Chrome and verify login/security state before retrying.' } }, seen) });
  assert.equal((await blocked.run({ channel: 'douyin', action: 'search', query: 'x' })).error.code, 'PLATFORM_BLOCKED');
  const status = await createSocialRuntime({ runtimeDir: dir, browser: fakeBrowser() }).status();
  assert.equal(status.platforms.douyin.logged_in, true, 'still logged in');
  // A live check of the cookies (the headless browser running) keeps the note.
  const live = await createSocialRuntime({ runtimeDir: dir, browser: fakeBrowser({ mode: 'headless', cookies: { douyin: ['sessionid'] } }) }).status();
  assert.ok(live.platforms.douyin.blocked_at);
  assert.ok(status.platforms.douyin.blocked_at);
  assert.equal(status.platforms.douyin.verify, true);
  const after = await createSocialRuntime({ runtimeDir: dir, browser: fakeBrowser({ mode: 'window', cookies: { douyin: ['sessionid'] } }) }).finishLogin();
  assert.equal(after.platforms.douyin.blocked_at, undefined);
});

test('a login failure from OpenCLI is reported as NEED_LOGIN; a login window blocks lookups', async () => {
  const dir = await runtimeDir();
  const seen = [];
  const social = createSocialRuntime({ runtimeDir: dir, browser: fakeBrowser({ cookies: {} }),
    spawnImpl: fakeOpencli({ ok: false, error: { code: 'AUTH_REQUIRED', message: 'Not logged in to x.com' } }, seen) });
  const answer = await social.run({ channel: 'twitter', action: 'search', query: 'x' });
  assert.deepEqual([answer.ok, answer.error.code], [false, 'NEED_LOGIN']);
  const windowed = createSocialRuntime({ runtimeDir: dir, browser: fakeBrowser({ mode: 'window' }), spawnImpl: fakeOpencli({ ok: true, data: [] }, seen) });
  await fs.rm(path.join(dir, 'social-browser', 'logins.json'), { force: true });
  await assert.rejects(windowed.run({ channel: 'douyin', action: 'search', query: 'x' }), (error) => error.status === 409);
});

test('the login window opens on the platform page and closing it records who is logged in', async () => {
  const dir = await runtimeDir();
  const browser = fakeBrowser({ cookies: { xiaohongshu: ['web_session'] }, page: true });
  const social = createSocialRuntime({ runtimeDir: dir, browser });
  await social.openLogin('xiaohongshu');
  assert.deepEqual(browser.log, ['window https://www.xiaohongshu.com/explore']);
  await social.openLogin('douyin', { verify: true });
  assert.ok(browser.log.at(-1).startsWith('window https://www.douyin.com/search/'), 'the page where Douyin shows its captcha');
  const done = await social.finishLogin();
  assert.equal(done.platforms.xiaohongshu.logged_in, true);
  assert.equal(browser.log.at(-1), 'close');
  const out = await social.logout('xiaohongshu');
  assert.equal(out.platforms.xiaohongshu.logged_in, false);
  assert.ok(browser.log.includes('forget xiaohongshu'));
  await assert.rejects(social.openLogin('facebook'));
  assert.equal(social.handles({ channel: 'weibo', action: 'search' }), true);
  assert.equal(social.handles({ action: 'read', target: 'https://www.douyin.com/video/1' }), true);
  assert.equal(social.handles({ action: 'read', target: 'https://zh.wikipedia.org/wiki/A' }), false);
  assert.equal(social.handles({ channel: 'bilibili', action: 'read', target: 'https://www.douyin.com/video/1' }), false);
  assert.equal(social.handles({ action: 'search', query: 'x' }), false, 'a plain search stays a web search');
});

test('the phone endpoint sends platform calls to the dedicated browser and the dashboard opens the login window', async () => {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'pocket-social-server-'));
  const calls = [];
  const social = {
    handles: (call) => call.channel === 'douyin',
    run: async (call) => { calls.push(['run', call.channel]); return { ok: true, channel: call.channel, items: [] }; },
    status: async () => ({ available: true, mode: 'off', platforms: {} }),
    openLogin: async (platform) => { calls.push(['login', platform]); return { ok: true }; },
    finishLogin: async () => { calls.push(['done']); return { ok: true }; },
    logout: async (platform) => { calls.push(['logout', platform]); return { ok: true }; },
  };
  const reach = { run: async (call) => { calls.push(['reach', call.channel || 'web']); return { ok: true, items: [] }; }, status: async () => ({ available: true }) };
  const config = { token: 'tok', dataDir };
  const server = createServer({ config, vault: createVault({ dataDir }), upstream: { complete: async () => ({}) }, reach, social });
  const admin = createAdminServer({ requestListener: server.pocket.requestListener, config, vault: server.pocket.vault, dataDir, social: server.pocket.social });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  await new Promise((resolve) => admin.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  const ui = `http://127.0.0.1:${admin.address().port}`;
  try {
    const post = (body) => fetch(`${base}/v1/pocket/reach`, { method: 'POST', headers: { authorization: 'Bearer tok', 'content-type': 'application/json' }, body: JSON.stringify(body) });
    assert.equal((await post({ channel: 'douyin', action: 'search', query: 'x' })).status, 200);
    assert.equal((await post({ action: 'search', query: 'x' })).status, 200);
    const status = await (await fetch(`${base}/v1/pocket/status`, { headers: { authorization: 'Bearer tok' } })).json();
    assert.ok(status.features.includes('social_reach'));
    assert.equal(status.social.available, true);
    assert.equal((await fetch(`${ui}/api/admin/social/login?platform=weibo`, { method: 'POST' })).status, 403, 'writes need the dashboard header');
    const write = (url, method) => fetch(`${ui}${url}`, { method, headers: { 'x-pocket-admin': '1' } });
    assert.equal((await write('/api/admin/social/login?platform=weibo', 'POST')).status, 200);
    assert.equal((await write('/api/admin/social/done', 'POST')).status, 200);
    assert.equal((await write('/api/admin/social/login?platform=weibo', 'DELETE')).status, 200);
    assert.equal((await fetch(`${ui}/api/admin/social`)).status, 200);
    assert.deepEqual(calls, [['run', 'douyin'], ['reach', 'web'], ['login', 'weibo'], ['done'], ['logout', 'weibo']]);
  } finally {
    for (const s of [server, admin]) { s.closeAllConnections(); await new Promise((resolve) => s.close(resolve)); }
    await fs.rm(dataDir, { recursive: true, force: true });
  }
});
