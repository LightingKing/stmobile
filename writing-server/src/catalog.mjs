/**
 * The upstream's text models, cached for ten minutes. A failed refresh keeps the last good list so
 * the phone's model picker does not empty out when the upstream hiccups.
 */
export function createCatalog({ upstream, ttlMs = 10 * 60_000, now = Date.now } = {}) {
  let models = [];
  let fetchedAt = 0;
  let error = '';
  let pending = null;

  function refresh() {
    if (!upstream?.models) return Promise.resolve();
    pending ??= (async () => {
      try {
        models = await upstream.models();
        fetchedAt = now();
        error = '';
      } catch (failure) {
        error = failure?.message || '读取上游模型列表失败';
      } finally {
        pending = null;
      }
    })();
    return pending;
  }

  const stale = () => !fetchedAt || now() - fetchedAt >= ttlMs;

  return {
    async list({ refresh: force = false } = {}) {
      if (force || stale()) await refresh();
      return { models, fetchedAt, error };
    },
    /** Starts a refresh without waiting, for callers that only report counts. */
    warm() { if (stale()) refresh(); },
    find(id) { return models.find((model) => model.id === id) || null; },
    get loaded() { return fetchedAt > 0; },
    get count() { return models.length; },
    get error() { return error; },
  };
}

const spec = (entry) => entry?.model_spec || {};

export function displayName(entry, fallback = '') { return spec(entry).name || entry?.name || fallback; }

export function contextOf(entry) {
  const value = Number(spec(entry).availableContextTokens ?? entry?.context_length);
  return Number.isInteger(value) && value > 0 ? value : null;
}

/** true / false when the upstream says so, null when it does not (non-Venice catalogs). */
export function capability(entry, ...names) {
  const caps = spec(entry).capabilities || entry?.capabilities || {};
  for (const name of names) if (typeof caps[name] === 'boolean') return caps[name];
  return null;
}
