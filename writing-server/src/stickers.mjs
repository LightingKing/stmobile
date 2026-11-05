import { randomBytes } from 'node:crypto';
import { mkdir, readdir, readFile, rename, rm, stat, writeFile } from 'node:fs/promises';
import path from 'node:path';

/** Same limits as the phone's importer, plus a cap on one upload. */
export const STICKER_LIMITS = { packs: 20, stickers: 300, imageBytes: 2 * 1024 * 1024, uploadBytes: 64 * 1024 * 1024 };
export const STICKER_UPLOAD_TYPE = 'application/vnd.pocket-tavern.stickers';
export const IMAGE_TYPES = { png: 'image/png', jpg: 'image/jpeg', gif: 'image/gif', webp: 'image/webp' };
const PACK_ID = /^[0-9a-f]{12}$/;
const FILE_NAME = /^\d{3}\.(png|jpg|gif|webp)$/;
// The marker is [表情:key], so a key may not contain bracket, colon or newline characters.
const BAD_KEY = /[[\]【】:：\n\r]/;

export class StickerError extends Error {
  constructor(message, status = 400, code = 'invalid_stickers') { super(message); this.status = status; this.code = code; }
}

function kind(bytes) {
  if (bytes.length >= 8 && bytes[0] === 0x89 && bytes.subarray(1, 4).toString('latin1') === 'PNG') return 'png';
  if (bytes.length >= 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return 'jpg';
  if (bytes.length >= 6 && bytes.subarray(0, 4).toString('latin1') === 'GIF8') return 'gif';
  if (bytes.length >= 12 && bytes.subarray(0, 4).toString('latin1') === 'RIFF' && bytes.subarray(8, 12).toString('latin1') === 'WEBP') return 'webp';
  return null;
}

const text = (value, max) => String(value ?? '').replace(/[\u0000-\u001f\u007f]/g, ' ').trim().slice(0, max);

/**
 * A pack upload is one body: a 4-byte big-endian header length, the JSON header
 * ({ name, license, source, stickers: [{ key, name, description, tags, extension, size }] }),
 * then every image's bytes in header order. The phone has already read the user's ZIP.
 */
export function parsePackUpload(body) {
  if (body.length < 4) throw new StickerError('表情包上传内容不完整');
  const headerLength = body.readUInt32BE(0);
  if (headerLength < 2 || headerLength > 1024 * 1024 || 4 + headerLength > body.length) throw new StickerError('表情包上传内容不完整');
  let header;
  try { header = JSON.parse(body.subarray(4, 4 + headerLength).toString('utf8')); } catch { throw new StickerError('表情包说明不是有效 JSON'); }
  const entries = Array.isArray(header?.stickers) ? header.stickers : [];
  if (!entries.length) throw new StickerError('表情包里没有图片');
  if (entries.length > STICKER_LIMITS.stickers) throw new StickerError(`一个表情包最多 ${STICKER_LIMITS.stickers} 张`);
  let offset = 4 + headerLength;
  const keys = new Set();
  const stickers = entries.map((entry) => {
    const key = text(entry?.key, 24);
    if (!key || BAD_KEY.test(key)) throw new StickerError(`表情名称无效：${key || '（空）'}`);
    if (keys.has(key)) throw new StickerError(`表情名称重复：${key}`);
    keys.add(key);
    const size = Number(entry?.size);
    if (!Number.isSafeInteger(size) || size <= 0 || size > STICKER_LIMITS.imageBytes) throw new StickerError(`表情“${key}”的图片超过 2 MB 或大小无效`);
    if (offset + size > body.length) throw new StickerError('表情包上传内容不完整');
    const bytes = body.subarray(offset, offset + size);
    offset += size;
    const detected = kind(bytes);
    if (!detected) throw new StickerError(`表情“${key}”不是 PNG、JPEG、GIF 或 WebP 图片`);
    return {
      key, name: text(entry.name, 40) || key, description: text(entry.description, 200),
      tags: (Array.isArray(entry.tags) ? entry.tags : []).map((tag) => text(tag, 20)).filter(Boolean).slice(0, 10),
      extension: detected, bytes,
    };
  });
  if (offset !== body.length) throw new StickerError('表情包上传内容与说明不一致');
  return { name: text(header.name, 60) || '未命名表情包', license: text(header.license, 60), source: text(header.source, 120), stickers };
}

/** Packs in `stickers/<id>/`, each with pack.json and its images. Changes run one at a time. */
export function createStickerStore({ dataDir }) {
  const root = path.join(dataDir, 'stickers');
  let queue = Promise.resolve();
  const serial = (work) => { const next = queue.then(work); queue = next.catch(() => {}); return next; };
  const packFile = (id) => path.join(root, id, 'pack.json');

  async function read(id) {
    if (!PACK_ID.test(String(id))) return null;
    try { return JSON.parse(await readFile(packFile(id), 'utf8')); } catch { return null; }
  }

  async function list() {
    let names;
    try { names = await readdir(root); } catch (error) { if (error.code === 'ENOENT') return []; throw error; }
    const packs = [];
    for (const name of names) { const pack = await read(name); if (pack) packs.push(pack); }
    return packs.sort((a, b) => String(a.imported_at).localeCompare(String(b.imported_at)));
  }

  async function add(body) {
    const parsed = parsePackUpload(body);
    return serial(async () => {
      const packs = await list();
      if (packs.length >= STICKER_LIMITS.packs) throw new StickerError(`表情包已达 ${STICKER_LIMITS.packs} 个，请先删除不用的`, 409, 'too_many_packs');
      const taken = new Set(packs.flatMap((pack) => pack.stickers.map((sticker) => sticker.key)));
      const clashes = parsed.stickers.map((sticker) => sticker.key).filter((key) => taken.has(key));
      if (clashes.length) throw new StickerError(`表情名称与已有表情包重复：${clashes.slice(0, 5).join('、')}`, 409, 'duplicate_keys');
      const id = randomBytes(6).toString('hex');
      const temp = path.join(root, `.${id}.tmp`);
      await mkdir(temp, { recursive: true, mode: 0o700 });
      try {
        const stickers = [];
        for (const [index, sticker] of parsed.stickers.entries()) {
          const file = `${String(index + 1).padStart(3, '0')}.${sticker.extension}`;
          await writeFile(path.join(temp, file), sticker.bytes, { mode: 0o600 });
          stickers.push({ key: sticker.key, name: sticker.name, description: sticker.description, tags: sticker.tags, file: `${id}/${file}` });
        }
        const pack = { id, name: parsed.name, license: parsed.license, source: parsed.source, enabled: true, imported_at: new Date().toISOString(), stickers };
        await writeFile(path.join(temp, 'pack.json'), JSON.stringify(pack, null, 2), { mode: 0o600 });
        await rename(temp, path.join(root, id));
        return pack;
      } finally {
        await rm(temp, { recursive: true, force: true });
      }
    });
  }

  const setEnabled = (id, enabled) => serial(async () => {
    const pack = await read(id);
    if (!pack) return null;
    pack.enabled = enabled === true;
    await writeFile(packFile(id), JSON.stringify(pack, null, 2), { mode: 0o600 });
    return pack;
  });

  const remove = (id) => serial(async () => {
    if (!(await read(id))) return false;
    await rm(path.join(root, id), { recursive: true, force: true });
    return true;
  });

  async function file(id, name) {
    if (!PACK_ID.test(String(id)) || !FILE_NAME.test(String(name))) return null;
    const full = path.join(root, id, name);
    try {
      const info = await stat(full);
      return info.isFile() ? { path: full, size: info.size, type: IMAGE_TYPES[name.split('.').pop()] } : null;
    } catch { return null; }
  }

  async function summary() {
    const packs = await list();
    return { packs: packs.length, enabled: packs.filter((pack) => pack.enabled).length, stickers: packs.reduce((sum, pack) => sum + pack.stickers.length, 0) };
  }

  return { list, add, setEnabled, remove, file, summary };
}
