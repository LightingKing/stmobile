import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createServer } from '../src/server.mjs';
import { createVault } from '../src/vault.mjs';
import { STICKER_UPLOAD_TYPE, createStickerStore, parsePackUpload } from '../src/stickers.mjs';

const png = (extra = 'a') => Buffer.concat([Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]), Buffer.from(extra)]);
const jpg = Buffer.from([0xff, 0xd8, 0xff, 0xe0, 1, 2, 3]);

/** Builds the phone's upload: header length, JSON header, then the images in order. */
function upload(stickers, header = {}) {
  const json = Buffer.from(JSON.stringify({ name: '大肥鱼', license: 'personal', source: 'dafeifish.zip', ...header,
    stickers: stickers.map(({ bytes, ...rest }) => ({ ...rest, size: bytes.length })) }));
  const length = Buffer.alloc(4);
  length.writeUInt32BE(json.length);
  return Buffer.concat([length, json, ...stickers.map((sticker) => sticker.bytes)]);
}

async function listen() {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'pocket-stickers-'));
  const server = createServer({ config: { token: 'test-token', dataDir }, vault: createVault({ dataDir }), upstream: { complete: async () => ({}) } });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}/v1/stickers`;
  const close = () => { server.closeAllConnections(); return new Promise((resolve) => server.close(resolve)); };
  const send = (body, type = STICKER_UPLOAD_TYPE) => fetch(`${base}/packs`, { method: 'POST', headers: { authorization: 'Bearer test-token', 'content-type': type }, body });
  const auth = { authorization: 'Bearer test-token' };
  return { base, close, send, auth, dataDir };
}

test('a pack uploaded from the phone is listed, served with a long cache, toggled and deleted', async () => {
  const pc = await listen();
  try {
    const created = await pc.send(upload([
      { key: '干饭', name: '干饭', description: '戴锅盔吃白米饭', tags: ['饭点'], extension: 'png', bytes: png('rice') },
      { key: 'hello', description: '挥手', tags: [], extension: 'jpg', bytes: jpg },
    ]));
    assert.equal(created.status, 201);
    const pack = await created.json();
    assert.match(pack.id, /^[0-9a-f]{12}$/);
    assert.deepEqual(pack.stickers.map((sticker) => sticker.file), [`${pack.id}/001.png`, `${pack.id}/002.jpg`]);
    assert.equal(pack.stickers[1].name, 'hello', 'a missing name falls back to the key');
    assert.equal(pack.enabled, true);

    const list = await (await fetch(pc.base, { headers: pc.auth })).json();
    assert.deepEqual(list.packs.map((item) => item.id), [pack.id]);
    assert.equal(list.limits.packs, 20);

    const image = await fetch(`${pc.base}/files/${pack.stickers[0].file}`, { headers: pc.auth });
    assert.equal(image.headers.get('content-type'), 'image/png');
    assert.match(image.headers.get('cache-control'), /immutable/);
    assert.deepEqual(Buffer.from(await image.arrayBuffer()), png('rice'));

    const off = await fetch(`${pc.base}/packs/${pack.id}`, { method: 'PATCH', headers: { ...pc.auth, 'content-type': 'application/json' }, body: JSON.stringify({ enabled: false }) });
    assert.equal((await off.json()).enabled, false);
    const status = await (await fetch(pc.base.replace('/stickers', '/pocket/status'), { headers: pc.auth })).json();
    assert.deepEqual(status.stickers, { packs: 1, enabled: 0, stickers: 2 });
    assert.ok(status.features.includes('stickers'));

    assert.equal((await fetch(`${pc.base}/packs/${pack.id}`, { method: 'DELETE', headers: pc.auth })).status, 200);
    assert.equal((await fetch(`${pc.base}/files/${pack.stickers[0].file}`, { headers: pc.auth })).status, 404);
    assert.equal((await fetch(pc.base, { headers: pc.auth }).then((r) => r.json())).packs.length, 0);
  } finally { await pc.close(); }
});

test('sticker names stay unique across packs on the PC', async () => {
  const pc = await listen();
  try {
    assert.equal((await pc.send(upload([{ key: 'smile', extension: 'png', bytes: png() }]))).status, 201);
    const clash = await pc.send(upload([{ key: 'smile', extension: 'png', bytes: png() }, { key: 'new', extension: 'png', bytes: png() }]));
    assert.equal(clash.status, 409);
    assert.match((await clash.json()).error.message, /smile/);
  } finally { await pc.close(); }
});

test('bad uploads are refused and leave nothing behind', async () => {
  const pc = await listen();
  try {
    const cases = [
      [upload([{ key: 'fake', extension: 'png', bytes: Buffer.from('not an image') }]), /不是 PNG/],
      [upload([{ key: '[坏]', extension: 'png', bytes: png() }]), /表情名称无效/],
      [upload([{ key: 'a', extension: 'png', bytes: png() }, { key: 'a', extension: 'png', bytes: png() }]), /表情名称重复/],
      [upload([]), /没有图片/],
      [Buffer.concat([upload([{ key: 'a', extension: 'png', bytes: png() }]), Buffer.from('trailing')]), /不一致/],
      [upload([{ key: 'a', extension: 'png', bytes: png() }]).subarray(0, 20), /不完整/],
    ];
    for (const [body, message] of cases) {
      const response = await pc.send(body);
      assert.equal(response.status, 400);
      assert.match((await response.json()).error.message, message);
    }
    assert.equal((await pc.send(upload([{ key: 'a', extension: 'png', bytes: png() }]), 'application/zip')).status, 415);
    assert.equal((await fetch(`${pc.base}/files/../config.json`, { headers: pc.auth })).status, 404);
    assert.equal((await fetch(pc.base)).status, 401);
    const left = await fs.readdir(path.join(pc.dataDir, 'stickers')).catch(() => []);
    assert.deepEqual(left, []);
  } finally { await pc.close(); }
});

test('the PC holds at most 20 packs and 300 stickers per pack', async () => {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'pocket-sticker-limits-'));
  const store = createStickerStore({ dataDir });
  for (let i = 0; i < 20; i++) await store.add(upload([{ key: `k${i}`, extension: 'png', bytes: png() }]));
  await assert.rejects(store.add(upload([{ key: 'one-more', extension: 'png', bytes: png() }])), { status: 409 });
  const many = Array.from({ length: 301 }, (_, i) => ({ key: `m${i}`, extension: 'png', bytes: png() }));
  assert.throws(() => parsePackUpload(upload(many)), /最多 300 张/);
});
