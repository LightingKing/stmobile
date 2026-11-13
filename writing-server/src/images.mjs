/**
 * Picture search for the character drafter: the phone asks for pictures of a fictional character,
 * and this PC finds them with Bing's image search and sends back small thumbnails, so a model
 * that can see pictures writes the character's looks from them and the user picks an avatar.
 *
 * Only Bing is contacted: the result page comes from bing.com and every thumbnail from Bing's own
 * thumbnail hosts. The sites the pictures were found on are never fetched, so a search result
 * cannot point this PC at an arbitrary address. Safe search is always strict.
 */
const SEARCH_URL = 'https://www.bing.com/images/async';
const USER_AGENT = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36';
const SEARCH_HOSTS = new Set(['www.bing.com', 'cn.bing.com', 'www4.bing.com', 'global.bing.com']);
const THUMBNAIL_HOST = /^(?:ts[0-9]{0,2}\.mm\.bing\.net|tse[0-9]{0,2}[.-]mm\.(?:cn\.)?bing\.net)$/;
const MIME = { jpeg: 'image/jpeg', png: 'image/png', webp: 'image/webp' };

export const IMAGE_LIMITS = { results: 8, queryChars: 120, thumbnailBytes: 400 * 1024, pageBytes: 2 * 1024 * 1024 };
const SEARCH_TIMEOUT_MS = 20_000;
const THUMBNAIL_TIMEOUT_MS = 12_000;
const PARALLEL_DOWNLOADS = 4;
const PARALLEL_SEARCHES = 2;

export class ImageSearchError extends Error {
  constructor(message, status = 400, code = 'image_search_error') { super(message); this.status = status; this.code = code; }
}

const unescapeHtml = (text) => text.replace(/&quot;/g, '"').replace(/&#39;/g, "'").replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&');
// Bing marks the matched words with private-use characters; they are not part of the title.
const cleanTitle = (value) => String(value ?? '').replace(/[\u0000-\u001f\ue000-\uf8ff]/g, '').replace(/\s+/g, ' ').trim().slice(0, 120);

function httpsUrl(value) {
  try { const url = new URL(String(value)); return url.protocol === 'https:' && !url.username && !url.password ? url : null; } catch { return null; }
}

/** A Bing thumbnail address, or null for anything else. */
export function thumbnailUrl(value) {
  const url = httpsUrl(value);
  return url && THUMBNAIL_HOST.test(url.hostname) && url.pathname === '/th' && url.searchParams.get('id') ? url : null;
}

/** The pictures on one result page: title, the page it was found on, and Bing's thumbnail of it. */
export function parseResults(html) {
  const seen = new Set();
  const results = [];
  for (const match of String(html).matchAll(/\bm="(\{[^"]{1,6000}\})"/g)) {
    let entry;
    try { entry = JSON.parse(unescapeHtml(match[1])); } catch { continue; }
    const thumbnail = thumbnailUrl(entry?.turl);
    if (!thumbnail) continue;
    const id = thumbnail.searchParams.get('id');
    if (seen.has(id)) continue;
    seen.add(id);
    const page = String(entry.purl ?? '');
    const pageUrl = /^https?:\/\//i.test(page) && page.length <= 1000 && !/[\s\u0000-\u001f]/.test(page) ? page : '';
    results.push({ title: cleanTitle(entry.t), page_url: pageUrl, thumbnail: thumbnail.toString() });
  }
  return results;
}

/** What the bytes are, from their first bytes: Bing's content type alone is not trusted. */
export function imageKind(bytes) {
  if (bytes.length > 3 && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes[2] === 0xff) return 'jpeg';
  if (bytes.length > 8 && bytes.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) return 'png';
  if (bytes.length > 12 && bytes.subarray(0, 4).toString('latin1') === 'RIFF' && bytes.subarray(8, 12).toString('latin1') === 'WEBP') return 'webp';
  return null;
}

async function bounded(response, limit) {
  const declared = Number(response.headers.get('content-length') || 0);
  if (declared > limit) throw new ImageSearchError('内容过大', 502, 'image_too_large');
  const chunks = [];
  let size = 0;
  for await (const chunk of response.body) {
    size += chunk.length;
    if (size > limit) throw new ImageSearchError('内容过大', 502, 'image_too_large');
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
}

export function createImageSearch({ fetchImpl = fetch } = {}) {
  let running = 0;

  /** The result page; Bing may send www to a regional host, and only its own hosts are followed. */
  async function resultPage(query, count) {
    let url = new URL(SEARCH_URL);
    url.search = new URLSearchParams({ q: query, first: '0', count: String(count), mmasync: '1', adlt: 'strict' }).toString();
    const signal = AbortSignal.timeout(SEARCH_TIMEOUT_MS);
    for (let hop = 0; hop < 3; hop++) {
      const response = await fetchImpl(url, {
        redirect: 'manual', signal,
        headers: { 'user-agent': USER_AGENT, 'accept-language': 'zh-CN,zh;q=0.9,en;q=0.6', cookie: 'SRCHHPGUSR=ADLT=STRICT' },
      });
      if (response.status >= 300 && response.status < 400) {
        let next = null;
        try { next = httpsUrl(new URL(response.headers.get('location') || '', url)); } catch { /* not an address */ }
        if (!next || !SEARCH_HOSTS.has(next.hostname)) throw new ImageSearchError('图片搜索被转到了别的网站', 502, 'image_search_redirect');
        url = next;
        continue;
      }
      if (!response.ok) throw new ImageSearchError(`图片搜索返回 ${response.status}`, 502, 'image_search_failed');
      return (await bounded(response, IMAGE_LIMITS.pageBytes)).toString('utf8');
    }
    throw new ImageSearchError('图片搜索转向次数过多', 502, 'image_search_redirect');
  }

  async function thumbnail(address) {
    const url = thumbnailUrl(address);
    if (!url) return null;
    try {
      const response = await fetchImpl(url, { redirect: 'error', signal: AbortSignal.timeout(THUMBNAIL_TIMEOUT_MS), headers: { 'user-agent': USER_AGENT } });
      if (!response.ok) return null;
      const bytes = await bounded(response, IMAGE_LIMITS.thumbnailBytes);
      const kind = imageKind(bytes);
      return kind ? { mime: MIME[kind], data: bytes.toString('base64'), bytes: bytes.length } : null;
    } catch { return null; }
  }

  /** `{ query, limit }` from the phone -> pictures with their thumbnails as base64. */
  async function search(input) {
    if (input === null || typeof input !== 'object' || Array.isArray(input)) throw new ImageSearchError('参数必须是 JSON 对象');
    const query = typeof input.query === 'string' ? input.query.trim() : '';
    if (!query) throw new ImageSearchError('图片搜索需要 query');
    if (query.length > IMAGE_LIMITS.queryChars || /[\u0000-\u001f]/.test(query)) throw new ImageSearchError(`query 最多 ${IMAGE_LIMITS.queryChars} 字，且不能含控制字符`);
    const limit = input.limit == null ? 6 : Number(input.limit);
    if (!Number.isInteger(limit) || limit < 1 || limit > IMAGE_LIMITS.results) throw new ImageSearchError(`limit 必须是 1 到 ${IMAGE_LIMITS.results} 的整数`);
    if (running >= PARALLEL_SEARCHES) throw new ImageSearchError('正在找别的图片，请稍后再试', 429, 'image_search_busy');
    running++;
    try {
      let html;
      try { html = await resultPage(query, limit * 2); } catch (error) {
        if (error instanceof ImageSearchError) throw error;
        const timedOut = error?.name === 'TimeoutError' || error?.name === 'AbortError';
        throw new ImageSearchError(timedOut ? '图片搜索超过 20 秒没有完成' : '这台电脑连不上图片搜索', timedOut ? 504 : 502, timedOut ? 'image_search_timeout' : 'image_search_unreachable');
      }
      // A few spare results, since some thumbnails fail to download.
      const candidates = parseResults(html).slice(0, limit + 4);
      const items = [];
      for (let start = 0; start < candidates.length && items.length < limit; start += PARALLEL_DOWNLOADS) {
        const batch = candidates.slice(start, start + PARALLEL_DOWNLOADS);
        const loaded = await Promise.all(batch.map((candidate) => thumbnail(candidate.thumbnail)));
        loaded.forEach((picture, index) => {
          if (picture && items.length < limit) items.push({ title: batch[index].title, page_url: batch[index].page_url, mime: picture.mime, bytes: picture.bytes, data: picture.data });
        });
      }
      return { ok: true, query, source: 'bing', items };
    } finally { running--; }
  }

  return { search };
}
