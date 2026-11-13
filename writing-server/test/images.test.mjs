import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createServer } from '../src/server.mjs';
import { createVault } from '../src/vault.mjs';
import { IMAGE_LIMITS, ImageSearchError, createImageSearch, imageKind, parseResults, thumbnailUrl } from '../src/images.mjs';

const JPEG = Buffer.concat([Buffer.from([0xff, 0xd8, 0xff, 0xe0]), Buffer.alloc(60, 1)]);
const PNG = Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), Buffer.alloc(40, 2)]);

/** One result as Bing writes it: JSON in an HTML attribute. */
const result = (entry) => `<a class="iusc" m="${JSON.stringify(entry).replace(/&/g, '&amp;').replace(/"/g, '&quot;')}" href="#">x</a>`;
const page = (...entries) => `<html><body>${entries.map(result).join('\n')}</body></html>`;

const sample = page(
  { t: '五更琉璃（轻小说角色）', turl: 'https://ts3.mm.bing.net/th?id=OIP.aaa&pid=15.1', purl: 'https://baike.baidu.com/item/x', murl: 'https://img.example/full.jpg' },
  { t: '同一张图', turl: 'https://ts1.mm.bing.net/th?id=OIP.aaa&pid=15.1', purl: 'https://dup.example/' },
  { t: 'png 图', turl: 'https://tse2-mm.cn.bing.net/th?id=OIP.bbb&pid=15.1', purl: 'javascript:alert(1)' },
  { t: '别的网站的缩略图', turl: 'https://evil.example/th?id=OIP.ccc', purl: 'https://evil.example/' },
  { t: '内网地址', turl: 'https://127.0.0.1/th?id=OIP.ddd', purl: 'https://x.example/' },
  { t: '坏掉的图', turl: 'https://ts2.mm.bing.net/th?id=OIP.eee&pid=15.1', purl: 'https://x.example/e' },
);

/** A fetch that serves the result page and Bing thumbnails, and records every address asked for. */
function fakeFetch({ html = sample, redirectTo = null, pageStatus = 200 } = {}) {
  const asked = [];
  const impl = async (address, options = {}) => {
    const url = new URL(String(address));
    asked.push({ url: url.toString(), redirect: options.redirect, cookie: options.headers?.cookie });
    if (url.pathname === '/images/async') {
      if (redirectTo && url.hostname === 'www.bing.com') return new Response(null, { status: 302, headers: { location: redirectTo } });
      return new Response(html, { status: pageStatus, headers: { 'content-type': 'text/html; charset=utf-8' } });
    }
    const id = url.searchParams.get('id');
    if (id === 'OIP.aaa') return new Response(JPEG, { status: 200, headers: { 'content-type': 'image/jpeg' } });
    if (id === 'OIP.bbb') return new Response(PNG, { status: 200, headers: { 'content-type': 'image/jpeg' } });
    if (id === 'OIP.eee') return new Response('<html>not a picture</html>', { status: 200, headers: { 'content-type': 'image/jpeg' } });
    if (id === 'OIP.big') return new Response(Buffer.concat([JPEG, Buffer.alloc(IMAGE_LIMITS.thumbnailBytes)]), { status: 200 });
    return new Response('missing', { status: 404 });
  };
  return { impl, asked };
}

test('only Bing thumbnail addresses count as pictures; repeats and unsafe page links are dropped', () => {
  assert.ok(thumbnailUrl('https://ts3.mm.bing.net/th?id=OIP.x&pid=15.1'));
  assert.ok(thumbnailUrl('https://tse1-mm.cn.bing.net/th?id=OIP.x'));
  for (const bad of ['http://ts3.mm.bing.net/th?id=OIP.x', 'https://ts3.mm.bing.net.evil.example/th?id=OIP.x', 'https://evilmm.bing.net/th?id=x',
    'https://ts3.mm.bing.net/other?id=OIP.x', 'https://ts3.mm.bing.net/th', 'https://user:pw@ts3.mm.bing.net/th?id=OIP.x', 'https://10.0.0.5/th?id=OIP.x', 'not a url']) {
    assert.equal(thumbnailUrl(bad), null, bad);
  }
  const results = parseResults(sample);
  assert.deepEqual(results.map((item) => item.title), ['五更琉璃（轻小说角色）', 'png 图', '坏掉的图']);
  assert.equal(results[0].page_url, 'https://baike.baidu.com/item/x');
  assert.equal(results[1].page_url, '', 'a javascript: link is not kept');
  assert.deepEqual(parseResults('<p m="{not json}">'), []);
  assert.equal(imageKind(JPEG), 'jpeg');
  assert.equal(imageKind(PNG), 'png');
  assert.equal(imageKind(Buffer.from('<html>')), null);
});

test('a search returns thumbnails as base64, always with safe search, and never fetches other sites', async () => {
  const { impl, asked } = fakeFetch();
  const answer = await createImageSearch({ fetchImpl: impl }).search({ query: '五更琉璃', limit: 5 });
  assert.equal(answer.ok, true);
  assert.deepEqual(answer.items.map((item) => [item.title, item.mime]), [['五更琉璃（轻小说角色）', 'image/jpeg'], ['png 图', 'image/png']]);
  assert.deepEqual(Buffer.from(answer.items[0].data, 'base64'), JPEG);
  assert.equal(answer.items[0].bytes, JPEG.length);
  const search = new URL(asked[0].url);
  assert.equal(search.hostname, 'www.bing.com');
  assert.equal(search.searchParams.get('q'), '五更琉璃');
  assert.equal(search.searchParams.get('adlt'), 'strict');
  assert.match(asked[0].cookie, /ADLT=STRICT/);
  for (const call of asked.slice(1)) assert.match(new URL(call.url).hostname, /bing\.net$/, call.url);
  assert.ok(asked.slice(1).every((call) => call.redirect === 'error'), 'a thumbnail may not redirect elsewhere');
});

test('the result page may move to a Bing host but nowhere else; oversized thumbnails are skipped', async () => {
  const moved = fakeFetch({ redirectTo: 'https://cn.bing.com/images/async?q=x&adlt=strict' });
  assert.equal((await createImageSearch({ fetchImpl: moved.impl }).search({ query: 'x', limit: 2 })).items.length, 2);
  assert.equal(new URL(moved.asked[1].url).hostname, 'cn.bing.com');

  const hijacked = fakeFetch({ redirectTo: 'https://evil.example/images/async' });
  await assert.rejects(createImageSearch({ fetchImpl: hijacked.impl }).search({ query: 'x' }), (error) => error instanceof ImageSearchError && error.code === 'image_search_redirect');
  assert.equal(hijacked.asked.length, 1, 'the other site is never contacted');

  const big = fakeFetch({ html: page({ t: '太大', turl: 'https://ts1.mm.bing.net/th?id=OIP.big', purl: 'https://x.example/' }) });
  assert.deepEqual((await createImageSearch({ fetchImpl: big.impl }).search({ query: 'x' })).items, []);

  const failing = fakeFetch({ pageStatus: 503 });
  await assert.rejects(createImageSearch({ fetchImpl: failing.impl }).search({ query: 'x' }), (error) => error.status === 502 && error.code === 'image_search_failed');
  const offline = createImageSearch({ fetchImpl: async () => { throw new TypeError('fetch failed'); } });
  await assert.rejects(offline.search({ query: 'x' }), (error) => error.code === 'image_search_unreachable');
});

test('bad requests are refused before anything is fetched', async () => {
  const { impl, asked } = fakeFetch();
  const images = createImageSearch({ fetchImpl: impl });
  for (const bad of [null, [], {}, { query: '  ' }, { query: 'x'.repeat(IMAGE_LIMITS.queryChars + 1) }, { query: 'a\nb' }, { query: 'x', limit: 0 }, { query: 'x', limit: 9 }, { query: 'x', limit: 2.5 }]) {
    await assert.rejects(images.search(bad), (error) => error instanceof ImageSearchError && error.status === 400, JSON.stringify(bad));
  }
  assert.equal(asked.length, 0);
});

test('POST /v1/pocket/images needs the token and is listed as a feature', async () => {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'pocket-images-server-'));
  const { impl } = fakeFetch();
  const server = createServer({ config: { token: 'tok', dataDir }, vault: createVault({ dataDir }), upstream: { complete: async () => ({}) },
    reach: { run: async () => ({ ok: true }), status: async () => ({ available: false }) }, images: createImageSearch({ fetchImpl: impl }) });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  const post = (body, token = 'tok') => fetch(`${base}/v1/pocket/images`, { method: 'POST', headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' }, body: JSON.stringify(body) });
  try {
    assert.equal((await post({ query: 'x' }, 'wrong')).status, 401);
    const refused = await post({ query: '' });
    assert.equal(refused.status, 400);
    assert.equal((await refused.json()).error.code, 'image_search_error');
    const ok = await post({ query: '五更琉璃', limit: 3 });
    assert.equal(ok.status, 200);
    const body = await ok.json();
    assert.equal(body.items.length, 2);
    assert.equal(body.items[0].mime, 'image/jpeg');
    const status = await (await fetch(`${base}/v1/pocket/status`, { headers: { authorization: 'Bearer tok' } })).json();
    assert.ok(status.features.includes('image_search'));
  } finally {
    await new Promise((resolve) => server.close(resolve));
    await fs.rm(dataDir, { recursive: true, force: true });
  }
});
