import { isIP } from 'node:net';

const LOOPBACK = new Set(['127.0.0.1', '::1', '::ffff:127.0.0.1']);

/**
 * The address a request came from. A tunnel client on this PC (NPS npc, Tailscale serve) connects
 * from loopback or from the listening address itself; only then is the last X-Forwarded-For hop,
 * the one the tunnel appended, taken as the client.
 */
export function clientAddress(req, selfHosts = []) {
  const remote = req.socket?.remoteAddress || '';
  if (!LOOPBACK.has(remote) && !selfHosts.includes(remote.replace(/^::ffff:/, ''))) return remote;
  const forwarded = String(req.headers['x-forwarded-for'] || '').split(',').map((part) => part.trim()).filter(Boolean).at(-1);
  return forwarded && isIP(forwarded) ? forwarded : remote;
}

/**
 * Throttles token guessing. Only requests that present a wrong Bearer token count; a source that
 * reaches `limit` failures inside `windowMs` is refused without checking its token for `blockMs`.
 * Requests with no Authorization header are plain 401s and never block anyone.
 */
export function createAuthGuard({ limit = 10, windowMs = 10 * 60_000, blockMs = 15 * 60_000, maxSources = 5000, now = Date.now } = {}) {
  const sources = new Map();

  function entry(source) {
    const time = now();
    let value = sources.get(source);
    if (value && value.blockedUntil <= time && value.windowStart + windowMs <= time) { sources.delete(source); value = undefined; }
    return value;
  }

  return {
    /** Seconds until the source may try again, or 0 when it is not blocked. */
    retryAfter(source) {
      const value = entry(source);
      return value && value.blockedUntil > now() ? Math.ceil((value.blockedUntil - now()) / 1000) : 0;
    },
    /** Records a wrong token; returns true when this failure starts a block. */
    fail(source) {
      const time = now();
      let value = entry(source);
      if (!value || (value.windowStart + windowMs <= time && value.blockedUntil <= time)) {
        if (!sources.has(source) && sources.size >= maxSources) sources.delete(sources.keys().next().value);
        value = { windowStart: time, failures: 0, blockedUntil: 0 };
        sources.set(source, value);
      }
      value.failures += 1;
      if (value.failures >= limit && value.blockedUntil <= time) { value.blockedUntil = time + blockMs; return true; }
      return false;
    },
    succeed(source) { sources.delete(source); },
    get size() { return sources.size; },
  };
}
