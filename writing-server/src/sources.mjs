/**
 * Model sources: where the PC gets its models. The configured upstream (Venice) is the built-in
 * first source; the dashboard adds more (any OpenAI-compatible API, another Venice account, a local
 * Ollama). Each source has its own address, key and model catalog.
 *
 * - Model ids: the built-in source's models keep their own ids (chats made before keep working);
 *   another source's models are "<source id>/<model id>" (ASCII, what requests carry) and are named
 *   "<source name>/<model name>" (what the phone shows), e.g. id "api/k3.1-pro", name "中转/k3.1-pro".
 * - Keys live in the private directory (venice-key.txt for the built-in one, source-keys/<id>.txt for
 *   the others) and never leave this module: summaries carry the last four characters only.
 * - A source that does not describe its models gets context and capabilities from the same model in
 *   another source, matched by name; such entries are marked pocket_tavern_guessed.
 */
import { chmodSync, existsSync, mkdirSync, readFileSync, renameSync, rmSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { createUpstream } from './upstream.mjs';
import { capability, contextOf, createCatalog, displayName } from './catalog.mjs';

export class SourceError extends Error {
  constructor(message, status = 400, code = 'source_error') {
    super(message);
    this.name = 'SourceError';
    this.status = status;
    this.code = code;
  }
}

export const SOURCE_KINDS = { openai: 'OpenAI 兼容', venice: 'Venice', ollama: '本机 Ollama' };
export const BUILTIN_ID = 'venice';
const ID = /^[a-z][a-z0-9-]{0,15}$/;
const NAME_CHARS = 20;
const KEY_CHARS = 400;
const LOOPBACK = new Set(['127.0.0.1', 'localhost', '[::1]', '::1']);
const PREFIXES = ['e2ee', 'openai', 'venice'];
const OLLAMA_URL = 'http://127.0.0.1:11434/v1';

/** "kimi-k3-1-pro" and "k3.1-pro" both end in "k31pro". */
export function normalizedModel(id) {
  // A trailing release date ("-1017", "-2507", "-20241022", "-2024-08-06") names a snapshot, not another model.
  const base = String(id).split('/').pop().toLowerCase().replace(/[-_.@](?:\d{4}-\d{2}-\d{2}|\d{8}|\d{6}|\d{4})$/, '');
  let value = base.replace(/[^a-z0-9]/g, '');
  for (const prefix of PREFIXES) if (value.startsWith(prefix) && value.length > prefix.length + 3) value = value.slice(prefix.length);
  return value;
}

/** Same model under two names: equal, or the longer ends with a shorter one that has a version number. */
export function sameModel(a, b) {
  if (!a || !b) return false;
  if (a === b) return true;
  const [short, long] = a.length <= b.length ? [a, b] : [b, a];
  return short.length >= 5 && /\d/.test(short) && long.endsWith(short);
}

/** Whether a catalog entry says anything about its context or capabilities. */
const describes = (entry) => Boolean(entry?.model_spec || entry?.capabilities || ['supports_tools', 'supports_vision', 'context_length'].some((key) => key in (entry || {})));

/** A checked base URL: https, or http to this PC for Ollama; no credentials, query or fragment. */
export function checkBaseUrl(kind, value) {
  let url;
  try { url = new URL(String(value ?? '').trim()); } catch { throw new SourceError('接口地址不是有效的网址，例如 https://api.example.com/v1', 400, 'invalid_source'); }
  if (url.username || url.password || url.search || url.hash) throw new SourceError('接口地址里不能带账号密码、?参数或 #', 400, 'invalid_source');
  if (kind === 'ollama') {
    if (!['http:', 'https:'].includes(url.protocol) || !LOOPBACK.has(url.hostname)) throw new SourceError(`本机 Ollama 只能用这台电脑的地址，例如 ${OLLAMA_URL}`, 400, 'invalid_source');
  } else if (url.protocol !== 'https:') throw new SourceError('接口地址要以 https:// 开头', 400, 'invalid_source');
  return url.href.replace(/\/+$/, '');
}

function checkKey(kind, value, { required }) {
  const key = String(value ?? '').trim();
  if (!key) {
    if (required && kind !== 'ollama') throw new SourceError('要填密钥', 400, 'invalid_source');
    return '';
  }
  if (key.length > KEY_CHARS || /\s/.test(key)) throw new SourceError('密钥应该是一行，不带空格', 400, 'invalid_source');
  return key;
}

function checkName(value) {
  const name = String(value ?? '').trim();
  if (!name) throw new SourceError('要给来源起个名字', 400, 'invalid_source');
  if (name.length > NAME_CHARS || /[/\n]/.test(name)) throw new SourceError(`名字最多 ${NAME_CHARS} 个字，不能有 /`, 400, 'invalid_source');
  return name;
}

const tail = (key) => (key ? key.slice(-4) : '');
const hostOf = (baseUrl) => { try { return new URL(baseUrl).host; } catch { return ''; } };

function writePrivate(file, text) {
  mkdirSync(path.dirname(file), { recursive: true, mode: 0o700 });
  const temp = `${file}.${process.pid}.tmp`;
  writeFileSync(temp, text, { mode: 0o600 });
  renameSync(temp, file);
  try { chmodSync(file, 0o600); } catch { /* Windows keeps the directory's ACL */ }
}

function readPrivate(file) { try { return readFileSync(file, 'utf8').trim(); } catch { return ''; } }

/**
 * [builtin] is the configured upstream ({ baseUrl, key, idleTimeoutMs }); [upstream] / [catalog]
 * replace its client and catalog (tests). [currentModel]() is the default model, which a source
 * cannot be removed or switched off from under.
 */
export function createSources({ dataDir, builtin = {}, upstream = null, catalog = null, makeUpstream = createUpstream, currentModel = () => '', log = console } = {}) {
  const listFile = path.join(dataDir, 'sources.json');
  const keyDir = path.join(dataDir, 'source-keys');
  const sources = new Map();

  const client = (kind, baseUrl, key) => makeUpstream({ baseUrl, key: key || (kind === 'ollama' ? 'ollama' : ''), idleTimeoutMs: builtin.idleTimeoutMs });

  /** Gives [source] its key, client and an empty catalog. */
  function attach(source, key) {
    source.key = key;
    source.upstream = key || source.kind === 'ollama' ? client(source.kind, source.baseUrl, key) : null;
    source.catalog = createCatalog({ upstream: source.upstream });
    return source;
  }

  // The built-in source: the address in config.json and the Venice key file.
  const first = { id: BUILTIN_ID, name: 'Venice', kind: 'venice', baseUrl: builtin.baseUrl || 'https://api.venice.ai/api/v1', enabled: true, builtin: true, createdAt: 0, key: builtin.key || '' };
  first.upstream = upstream || (first.key ? client('venice', first.baseUrl, first.key) : null);
  first.catalog = catalog || createCatalog({ upstream: first.upstream });
  sources.set(BUILTIN_ID, first);

  try {
    const saved = JSON.parse(readFileSync(listFile, 'utf8'));
    if (typeof saved?.builtinName === 'string' && saved.builtinName.trim()) first.name = saved.builtinName.trim().slice(0, NAME_CHARS);
    for (const entry of Array.isArray(saved?.sources) ? saved.sources : []) {
      if (!ID.test(String(entry?.id)) || entry.id === BUILTIN_ID || !SOURCE_KINDS[entry.kind] || sources.has(entry.id)) continue;
      const source = attach({ id: entry.id, name: String(entry.name || entry.id).slice(0, NAME_CHARS), kind: entry.kind, baseUrl: String(entry.baseUrl || ''), enabled: entry.enabled !== false, builtin: false, createdAt: Number(entry.createdAt) || 0 },
        readPrivate(path.join(keyDir, `${entry.id}.txt`)));
      sources.set(source.id, source);
    }
  } catch (error) { if (error.code !== 'ENOENT') log.warn?.(`sources.json 读不出来，只用内置来源：${error.message}`); }

  function persist() {
    const list = [...sources.values()].filter((source) => !source.builtin)
      .map(({ id, name, kind, baseUrl, enabled, createdAt }) => ({ id, name, kind, baseUrl, enabled, createdAt }));
    writePrivate(listFile, JSON.stringify({ builtinName: first.name, sources: list }, null, 2));
  }

  function nextId(kind) {
    const stem = kind === 'ollama' ? 'ollama' : kind === 'venice' ? 'venice' : 'api';
    for (let n = 1; n < 100; n += 1) {
      const id = n === 1 && stem !== 'venice' ? stem : `${stem}${n}`;
      if (!sources.has(id)) return id;
    }
    throw new SourceError('来源太多了', 400, 'too_many_sources');
  }

  function summary(source) {
    return {
      id: source.id, name: source.name, kind: source.kind, kind_name: SOURCE_KINDS[source.kind], base_url: source.baseUrl, host: hostOf(source.baseUrl),
      builtin: source.builtin, enabled: source.enabled, has_key: Boolean(source.key), key_tail: tail(source.key),
      ready: Boolean(source.upstream) && source.enabled, prefix: source.builtin ? '' : `${source.id}/`,
      models: source.catalog.loaded ? source.catalog.count : null,
      fetched_at: source.catalog.fetchedAt ? new Date(source.catalog.fetchedAt).toISOString() : null,
      error: source.catalog.error || '',
    };
  }

  const usable = () => [...sources.values()].filter((source) => source.enabled && source.upstream);
  /** Entries that describe themselves, from every usable source's last read: what guesses are made from. */
  const references = () => usable().flatMap((source) => source.catalog.cached.filter(describes));

  /** A catalog entry as the phone sees it: prefixed for other sources, with a guess where it says nothing. */
  function present(source, entry, reference) {
    const host = hostOf(source.baseUrl);
    if (source.builtin) return { ...entry, pocket_source: { id: source.id, name: source.name, host } };
    const name = `${source.name}/${displayName(entry, entry.id)}`;
    const shown = { ...entry, id: `${source.id}/${entry.id}`, name, pocket_source: { id: source.id, name: source.name, host } };
    // The phone prefers model_spec.name; a copy carries the prefixed one, the source's own stays as it was.
    if (entry.model_spec) shown.model_spec = { ...entry.model_spec, name };
    if (!describes(entry)) {
      const key = normalizedModel(entry.id);
      const twin = reference.find((candidate) => candidate !== entry && sameModel(key, normalizedModel(candidate.id)));
      if (twin) {
        const context = contextOf(twin);
        Object.assign(shown, {
          ...(context ? { context_length: context } : {}),
          supports_tools: capability(twin, 'supportsFunctionCalling', 'supportsTools') ?? twin.supports_tools ?? false,
          supports_vision: capability(twin, 'supportsVision') ?? twin.supports_vision ?? false,
          supports_reasoning: capability(twin, 'supportsReasoning') ?? twin.supports_reasoning ?? false,
          pocket_tavern_guessed: { from: twin.id },
        });
      }
    }
    return shown;
  }

  const api = {
    /** True when at least one source can be asked. */
    configured: () => usable().length > 0,
    builtin: () => first,
    list: () => [...sources.values()].map(summary),
    warm() { for (const source of usable()) source.catalog.warm(); },

    /** Every model of every usable source, as the phone lists them; errors per source. */
    async models({ refresh = false } = {}) {
      const results = await Promise.all(usable().map(async (source) => ({ source, ...(await source.catalog.list({ refresh })) })));
      const reference = results.flatMap(({ models }) => models.filter(describes));
      return {
        models: results.flatMap(({ source, models }) => models.map((entry) => present(source, entry, reference))),
        errors: results.filter(({ error }) => error).map(({ source, error }) => `${source.name}：${error}`),
        loaded: results.some(({ fetchedAt }) => fetchedAt > 0),
      };
    },

    /** Which source a model id belongs to, and the id that source knows it by. */
    resolve(model) {
      const id = String(model ?? '');
      const slash = id.indexOf('/');
      const named = slash > 0 ? sources.get(id.slice(0, slash)) : null;
      const source = named && !named.builtin ? named : first;
      const raw = source === first ? id : id.slice(slash + 1);
      if (!source.enabled) throw new SourceError(`来源“${source.name}”已停用；换一个模型，或在电脑管理页启用它`, 400, 'source_disabled');
      if (!source.upstream) throw new SourceError(source.builtin ? '写作服务未配置上游 API Key' : `来源“${source.name}”没有密钥`, 503, 'not_ready');
      const entry = source.catalog.find(raw);
      return { source, model: raw, upstream: source.upstream, entry: entry ? present(source, entry, references()) : null };
    },

    /** The listed entry for a model id from what has been read already; null when unknown. */
    find(model) {
      try { return api.resolve(model).entry; } catch { return null; }
    },

    /** Reads a not-yet-saved source's catalog once: how many models, how many call tools, a few names. */
    async test({ kind, baseUrl, key }) {
      if (!SOURCE_KINDS[kind]) throw new SourceError('来源类型无效', 400, 'invalid_source');
      const url = checkBaseUrl(kind, kind === 'ollama' && !String(baseUrl ?? '').trim() ? OLLAMA_URL : baseUrl);
      const secret = checkKey(kind, key, { required: true });
      let list;
      try { list = await client(kind, url, secret).models(); }
      catch (error) { throw new SourceError(`读不到模型列表：${error.message || '连接失败'}`, 502, 'source_unreachable'); }
      if (!list.length) throw new SourceError('连上了，但对方没有列出任何模型', 502, 'source_empty');
      const tools = list.filter((entry) => capability(entry, 'supportsFunctionCalling', 'supportsTools') === true || entry.supports_tools === true).length;
      return { base_url: url, count: list.length, tools, sample: list.slice(0, 8).map((entry) => entry.id) };
    },

    /** Adds a source after reading its catalog once; nothing is saved when that fails. */
    async add({ kind, name, baseUrl, key }) {
      const label = checkName(name);
      if ([...sources.values()].some((source) => source.name === label)) throw new SourceError(`已经有一个叫“${label}”的来源`, 400, 'invalid_source');
      const tested = await api.test({ kind, baseUrl, key });
      const secret = checkKey(kind, key, { required: true });
      const id = nextId(kind);
      if (secret) writePrivate(path.join(keyDir, `${id}.txt`), secret);
      const source = attach({ id, name: label, kind, baseUrl: tested.base_url, enabled: true, builtin: false, createdAt: Date.now() }, secret);
      sources.set(id, source);
      persist();
      await source.catalog.list({ refresh: true });
      log.log?.(`模型来源已添加：${label}（${tested.count} 个模型）`);
      return { ...summary(source), tested };
    },

    /** Renames, re-points, re-keys or switches a source on/off; a new address or key is tried first. */
    async update(id, { name, baseUrl, key, enabled: on } = {}) {
      const source = sources.get(id);
      if (!source) throw new SourceError('这个来源不存在', 404, 'source_not_found');
      const next = { name: source.name, baseUrl: source.baseUrl, key: source.key, enabled: source.enabled };
      if (name !== undefined) {
        next.name = checkName(name);
        if ([...sources.values()].some((other) => other !== source && other.name === next.name)) throw new SourceError(`已经有一个叫“${next.name}”的来源`, 400, 'invalid_source');
      }
      if (baseUrl !== undefined && !source.builtin) next.baseUrl = checkBaseUrl(source.kind, baseUrl);
      if (key !== undefined && String(key).trim()) next.key = checkKey(source.kind, key, { required: false });
      if (on !== undefined) {
        if (typeof on !== 'boolean') throw new SourceError('enabled 应为 true 或 false', 400, 'invalid_source');
        if (source.builtin && !on) throw new SourceError('内置的来源不能停用', 400, 'invalid_source');
        if (!on && String(currentModel()).startsWith(`${id}/`)) throw new SourceError('默认模型在这个来源里；先把默认模型换到别的来源', 400, 'source_in_use');
        next.enabled = on;
      }
      const rebuild = next.baseUrl !== source.baseUrl || next.key !== source.key;
      if (rebuild) await api.test({ kind: source.kind, baseUrl: next.baseUrl, key: next.key });
      if (next.key !== source.key) writePrivate(source.builtin ? path.join(dataDir, 'venice-key.txt') : path.join(keyDir, `${id}.txt`), next.key);
      Object.assign(source, { name: next.name, baseUrl: next.baseUrl, enabled: next.enabled });
      if (rebuild) attach(source, next.key);
      persist();
      if (rebuild) await source.catalog.list({ refresh: true });
      return summary(source);
    },

    async remove(id) {
      const source = sources.get(id);
      if (!source) throw new SourceError('这个来源不存在', 404, 'source_not_found');
      if (source.builtin) throw new SourceError('内置的来源不能删除', 400, 'invalid_source');
      if (String(currentModel()).startsWith(`${id}/`)) throw new SourceError('默认模型在这个来源里；先把默认模型换到别的来源', 400, 'source_in_use');
      sources.delete(id);
      rmSync(path.join(keyDir, `${id}.txt`), { force: true });
      persist();
      log.log?.(`模型来源已删除：${source.name}`);
      return { deleted: id };
    },

    async refresh(id) {
      const source = sources.get(id);
      if (!source) throw new SourceError('这个来源不存在', 404, 'source_not_found');
      await source.catalog.list({ refresh: true });
      return summary(source);
    },

    /** For tests: whether a key file exists (the key itself is never handed out). */
    hasKeyFile: (id) => existsSync(path.join(keyDir, `${id}.txt`)),
  };
  return api;
}
