import { createHash, randomBytes } from 'node:crypto';
import { createReadStream, createWriteStream } from 'node:fs';
import { mkdir, readdir, readFile, rename, rm, stat, statfs, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { pipeline } from 'node:stream/promises';
import { Transform } from 'node:stream';

/** Matches the app's own restore limit, so every stored backup can be restored. */
export const MAX_BACKUP_BYTES = 128 * 1024 * 1024;
const FREE_SPACE_MARGIN = 512 * 1024 * 1024;
const ID = /^\d{8}-\d{6}-[0-9a-f]{6}$/;
// 'pre-restore' marks the copy the phone makes of its own data right before restoring another backup.
const REASONS = new Set(['manual', 'auto', 'pre-restore']);
const ZIP_MAGIC = Buffer.from([0x50, 0x4b, 0x03, 0x04]);

export class BackupError extends Error {
  constructor(message, status, code) { super(message); this.status = status; this.code = code; }
}

function label(value, max) {
  return String(value || '').replace(/[\u0000-\u001f\u007f]/g, '').trim().slice(0, max);
}

function newId(date = new Date()) {
  const pad = (n) => String(n).padStart(2, '0');
  const stamp = `${date.getFullYear()}${pad(date.getMonth() + 1)}${pad(date.getDate())}-${pad(date.getHours())}${pad(date.getMinutes())}${pad(date.getSeconds())}`;
  return `${stamp}-${randomBytes(3).toString('hex')}`;
}

/**
 * Phone backups kept on this PC: `<id>.zip` plus `<id>.json` metadata in the private data directory.
 * Uploads stream to a temp file and only appear once complete; the newest `keep` copies are retained.
 */
export function createBackupStore({ dataDir, keep = 20, maxBytes = MAX_BACKUP_BYTES }) {
  const dir = path.join(dataDir, 'backups');
  let uploading = false;
  let keepCount = keep;

  const zipFile = (id) => path.join(dir, `${id}.zip`);
  const metaFile = (id) => path.join(dir, `${id}.json`);

  async function list() {
    let names;
    try { names = await readdir(dir); } catch (error) { if (error.code === 'ENOENT') return []; throw error; }
    const items = [];
    for (const name of names) {
      const id = name.endsWith('.json') ? name.slice(0, -5) : '';
      if (!ID.test(id)) continue;
      try {
        const meta = JSON.parse(await readFile(metaFile(id), 'utf8'));
        await stat(zipFile(id));
        items.push(meta);
      } catch { /* incomplete pair: ignored until pruned */ }
    }
    return items.sort((a, b) => String(b.created_at).localeCompare(String(a.created_at)) || b.id.localeCompare(a.id));
  }

  async function remove(id) {
    if (!ID.test(String(id))) return false;
    const existed = await stat(zipFile(id)).then(() => true, () => false);
    await rm(metaFile(id), { force: true });
    await rm(zipFile(id), { force: true });
    return existed;
  }

  async function prune() {
    const items = await list();
    for (const item of items.slice(keepCount)) await remove(item.id);
  }

  async function save(source, { declaredLength = null, device = '', appVersion = '', reason = 'manual' } = {}) {
    if (declaredLength !== null && declaredLength > maxBytes) throw new BackupError('备份超过 128MB 上限', 413, 'backup_too_large');
    if (uploading) throw new BackupError('已有备份正在上传', 409, 'backup_busy');
    uploading = true;
    const temp = path.join(dir, `.upload-${randomBytes(6).toString('hex')}.tmp`);
    try {
      await mkdir(dir, { recursive: true, mode: 0o700 });
      const space = await statfs(dir).catch(() => null);
      if (space && Number(space.bavail) * Number(space.bsize) < (declaredLength ?? maxBytes) + FREE_SPACE_MARGIN) {
        throw new BackupError('电脑磁盘空间不足，未保存备份', 507, 'insufficient_storage');
      }
      const hash = createHash('sha256');
      let size = 0;
      let head = Buffer.alloc(0);
      const meter = new Transform({
        transform(chunk, _encoding, done) {
          size += chunk.length;
          if (size > maxBytes) return done(new BackupError('备份超过 128MB 上限', 413, 'backup_too_large'));
          if (head.length < 4) head = Buffer.concat([head, chunk.subarray(0, 4 - head.length)]);
          if (head.length >= 4 && !head.equals(ZIP_MAGIC)) return done(new BackupError('上传内容不是 ZIP 备份', 415, 'not_a_zip'));
          hash.update(chunk);
          done(null, chunk);
        },
      });
      await pipeline(source, meter, createWriteStream(temp, { mode: 0o600 }));
      if (size < 4 || !head.equals(ZIP_MAGIC)) throw new BackupError('上传内容不是 ZIP 备份', 415, 'not_a_zip');
      if (declaredLength !== null && size !== declaredLength) throw new BackupError('备份上传不完整', 400, 'backup_incomplete');
      const sha256 = hash.digest('hex');
      const latest = (await list())[0];
      if (latest && latest.sha256 === sha256) return { ...latest, duplicate: true };
      const meta = {
        id: newId(), created_at: new Date().toISOString(), size, sha256,
        device: label(device, 60), app_version: label(appVersion, 30), reason: REASONS.has(reason) ? reason : 'manual',
      };
      await rename(temp, zipFile(meta.id));
      await writeFile(metaFile(meta.id), JSON.stringify(meta, null, 2), { mode: 0o600 });
      await prune();
      return meta;
    } finally {
      uploading = false;
      await rm(temp, { force: true });
    }
  }

  async function open(id) {
    if (!ID.test(String(id))) return null;
    try {
      const meta = JSON.parse(await readFile(metaFile(id), 'utf8'));
      const info = await stat(zipFile(id));
      return { meta, size: info.size, stream: () => createReadStream(zipFile(id)) };
    } catch { return null; }
  }

  async function summary() {
    const items = await list();
    return {
      count: items.length, keep: keepCount, max_bytes: maxBytes,
      total_bytes: items.reduce((sum, item) => sum + (Number(item.size) || 0), 0),
      latest: items[0] ? { id: items[0].id, created_at: items[0].created_at, size: items[0].size, device: items[0].device, reason: items[0].reason } : null,
    };
  }

  /** Takes effect from the next save; existing extra copies go then, not immediately. */
  function setKeep(count) { keepCount = count; }

  return { list, save, open, remove, summary, setKeep, get uploading() { return uploading; } };
}
