import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { Readable } from 'node:stream';
import { buffer } from 'node:stream/consumers';
import { createBackupStore } from '../src/backups.mjs';

const zip = (text) => Buffer.concat([Buffer.from([0x50, 0x4b, 0x03, 0x04]), Buffer.from(text)]);
// Splits the bytes so the ZIP signature check has to work across chunks.
const body = (bytes) => Readable.from([bytes.subarray(0, 2), bytes.subarray(2)]);

async function withStore(options, fn) {
  const dataDir = await mkdtemp(path.join(tmpdir(), 'pocket-backups-'));
  try { return await fn(createBackupStore({ dataDir, ...options }), dataDir); }
  finally { await rm(dataDir, { recursive: true, force: true }); }
}

test('backups round-trip, newest first, and keep only the newest copies', async () => {
  await withStore({ keep: 2 }, async (store, dataDir) => {
    const first = await store.save(body(zip('one')), { device: 'Pixel\u0007 8', appVersion: '0.1.68', reason: 'auto' });
    assert.equal(first.device, 'Pixel 8');
    assert.equal(first.reason, 'auto');
    await new Promise((resolve) => setTimeout(resolve, 5));
    const second = await store.save(body(zip('two')), { reason: 'pre-restore' });
    assert.equal(second.reason, 'pre-restore');
    await new Promise((resolve) => setTimeout(resolve, 5));
    const third = await store.save(body(zip('three')), { reason: 'weird' });
    assert.equal(third.reason, 'manual');
    assert.deepEqual((await store.list()).map((item) => item.id), [third.id, second.id]);
    const opened = await store.open(second.id);
    assert.deepEqual(await buffer(opened.stream()), zip('two'));
    assert.equal(await store.open(first.id), null);
    const files = await readdir(path.join(dataDir, 'backups'));
    assert.equal(files.filter((name) => name.endsWith('.tmp')).length, 0);
    const summary = await store.summary();
    assert.equal(summary.count, 2);
    assert.equal(summary.latest.id, third.id);
    assert.equal(summary.total_bytes, zip('two').length + zip('three').length);
  });
});

test('an unchanged backup is not stored twice', async () => {
  await withStore({}, async (store) => {
    const first = await store.save(body(zip('same')));
    const again = await store.save(body(zip('same')));
    assert.equal(again.duplicate, true);
    assert.equal(again.id, first.id);
    assert.equal((await store.list()).length, 1);
  });
});

test('non-ZIP, oversized and truncated uploads leave nothing behind', async () => {
  await withStore({ maxBytes: 64 }, async (store, dataDir) => {
    await assert.rejects(store.save(body(Buffer.from('not a zip at all'))), { status: 415 });
    await assert.rejects(store.save(body(zip('x'.repeat(100)))), { status: 413 });
    await assert.rejects(store.save(body(zip('x')), { declaredLength: 1000 }), { status: 413 });
    await assert.rejects(store.save(body(zip('short')), { declaredLength: 40 }), { status: 400 });
    assert.deepEqual(await store.list(), []);
    assert.deepEqual(await readdir(path.join(dataDir, 'backups')), []);
    assert.equal(store.uploading, false);
  });
});

test('backup ids are validated before touching the disk', async () => {
  await withStore({}, async (store) => {
    assert.equal(await store.open('../config'), null);
    assert.equal(await store.remove('..\config'), false);
    assert.equal(await store.open('20261104-120000-abcdef'), null);
  });
});
