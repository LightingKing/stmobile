import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { BiliLoginError, createBiliLogin, credentialFor, loginCookies } from '../src/bililogin.mjs';

/** Stands in for Bilibili: answers generate, poll (scripted states), spi and nav. */
function fakeBilibili(pollStates) {
  const calls = [];
  const json = (body, setCookie = []) => ({ ok: true, status: 200, json: async () => body, headers: { getSetCookie: () => setCookie } });
  const fetchImpl = async (url, options) => {
    calls.push({ url: String(url), cookie: options?.headers?.cookie || '' });
    if (url.includes('/qrcode/generate')) return json({ code: 0, data: { url: 'https://account.bilibili.com/h5/x?qrcode_key=k1', qrcode_key: 'k1' } });
    if (url.includes('/qrcode/poll')) {
      const code = pollStates.shift();
      if (code !== 0) return json({ code: 0, data: { code, message: '' } });
      return json({ code: 0, data: { code: 0, url: 'https://passport.biligame.com/crossDomain?DedeUserID=42', refresh_token: 'rt' } },
        ['SESSDATA=abc%2C123; Path=/; Domain=.bilibili.com; HttpOnly', 'bili_jct=jct; Path=/', 'DedeUserID=42; Path=/']);
    }
    if (url.includes('/finger/spi')) return json({ code: 0, data: { b_3: 'b3', b_4: 'b4' } });
    if (url.includes('/web-interface/nav')) return json(options.headers.cookie.includes('SESSDATA=abc') ? { code: 0, data: { isLogin: true, uname: '小号' } } : { code: -101, data: { isLogin: false } });
    throw new Error(`unexpected ${url}`);
  };
  return { fetchImpl, calls };
}

async function login(pollStates) {
  const runtimeDir = await fs.mkdtemp(path.join(os.tmpdir(), 'pocket-bili-'));
  const bili = fakeBilibili(pollStates);
  let clock = 1_000_000;
  const store = createBiliLogin({ runtimeDir, fetchImpl: bili.fetchImpl, renderQr: async (text) => Buffer.from(`QR:${text}`), now: () => clock });
  return { store, bili, runtimeDir, tick: (ms) => { clock += ms; } };
}

test('cookies come from Set-Cookie, with the cross-domain URL as a fallback', () => {
  assert.deepEqual(loginCookies(['SESSDATA=a%2Cb; Path=/', 'bili_jct=j; HttpOnly'], 'https://x.com/?DedeUserID=7&SESSDATA=ignored'), { SESSDATA: 'a%2Cb', bili_jct: 'j', DedeUserID: '7' });
  assert.equal(loginCookies([], 'https://x.com/?SESSDATA=a,b').SESSDATA, 'a%2Cb');
  assert.throws(() => credentialFor({}, 'rt'), BiliLoginError);
  assert.deepEqual(Object.keys(credentialFor({ SESSDATA: 's' }, 'rt', {}, 1)), ['sessdata', 'bili_jct', 'ac_time_value', 'buvid3', 'buvid4', 'dedeuserid', 'saved_at']);
});

test('a scanned and confirmed code saves the credential where bili-cli reads it', async () => {
  const { store, runtimeDir } = await login([86101, 86090, 0]);
  assert.deepEqual(await store.status(), { logged_in: false, pending: false });
  assert.equal((await store.start()).state, 'waiting');
  assert.equal(store.qrImage().toString(), 'QR:https://account.bilibili.com/h5/x?qrcode_key=k1');
  assert.equal((await store.poll()).state, 'waiting');
  assert.equal((await store.poll()).state, 'scanned');
  assert.equal((await store.poll()).state, 'done');
  assert.equal(store.qrImage(), null);
  const file = path.join(runtimeDir, 'agent-reach-home', '.agent-reach', 'sessions', 'bili-cli', '.bilibili-cli', 'credential.json');
  const saved = JSON.parse(await fs.readFile(file, 'utf8'));
  assert.equal(saved.sessdata, 'abc%2C123');
  assert.equal(saved.bili_jct, 'jct');
  assert.equal(saved.dedeuserid, '42');
  assert.equal(saved.ac_time_value, 'rt');
  assert.equal(saved.buvid3, 'b3');
  assert.deepEqual(await store.status({ check: true }), { logged_in: true, valid: true, name: '小号', pending: false });
  assert.deepEqual(await store.logout(), { logged_in: false });
  await assert.rejects(fs.stat(file));
});

test('an expired or abandoned code leaves nothing saved', async () => {
  const expired = await login([86038]);
  await expired.store.start();
  assert.equal((await expired.store.poll()).state, 'expired');
  assert.equal((await expired.store.poll()).state, 'idle');
  const late = await login([86101]);
  await late.store.start();
  late.tick(181_000);
  assert.equal(late.store.qrImage(), null);
  assert.equal((await late.store.poll()).state, 'expired');
  assert.equal((await late.store.status()).logged_in, false);
});
