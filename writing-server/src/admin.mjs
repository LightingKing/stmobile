import http from 'node:http';
import { open, readFile, stat } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { saveSettings } from './config.mjs';

const UI = fileURLToPath(new URL('../dashboard/', import.meta.url));
const FILES = {
  '/': ['index.html', 'text/html; charset=utf-8'],
  '/app.js': ['app.js', 'text/javascript; charset=utf-8'],
  '/style.css': ['style.css', 'text/css; charset=utf-8'],
};
const RUN_PATH = /^\/api\/admin\/runs\/([0-9a-f]{16,128})\/([A-Za-z0-9._-]{1,120})$/;

function send(res, status, body, type = 'application/json; charset=utf-8') {
  res.writeHead(status, {
    'content-type': type, 'cache-control': 'no-store', 'x-content-type-options': 'nosniff', 'x-frame-options': 'DENY',
    'content-security-policy': "default-src 'self'; img-src 'self'; style-src 'self'; script-src 'self'; frame-ancestors 'none'",
  });
  res.end(typeof body === 'string' || Buffer.isBuffer(body) ? body : JSON.stringify(body));
}

/** The last [lines] lines of a log file, read from its end. */
async function tail(file, lines) {
  let handle;
  try {
    handle = await open(file, 'r');
    const { size } = await handle.stat();
    const length = Math.min(size, 256 * 1024);
    const buffer = Buffer.alloc(length);
    await handle.read(buffer, 0, length, size - length);
    return buffer.toString('utf8').split(/\r?\n/).filter(Boolean).slice(-lines);
  } catch { return []; } finally { await handle?.close(); }
}

function runSummary(run) {
  const created = Date.parse(run.created_at);
  const updated = Date.parse(run.updated_at);
  const usage = run.final?.usage || run.partial?.usage || null;
  return {
    run_id: run.run_id, chat_hash: run.chat_hash, model: run.model, purpose: run.purpose, status: run.status,
    created_at: run.created_at, duration_ms: Number.isFinite(created) && Number.isFinite(updated) ? updated - created : null,
    usage, warning: run.warning || run.final?.warning || undefined, error: run.error || undefined,
    stages: (run.stages || []).map((stage) => ({ name: stage.name, status: stage.status })),
  };
}

/**
 * The PC dashboard on 127.0.0.1 only; the internet tunnel forwards a different port. A page in some
 * other site cannot drive it: a rebinding DNS name fails the Host check, and every write needs a
 * custom header, which forces a CORS preflight that this server never answers.
 */
export function createAdminServer({ requestListener, config, vault, dataDir }) {
  return http.createServer(async (req, res) => {
    try {
      const port = req.socket.localPort;
      const hosts = new Set([`127.0.0.1:${port}`, `localhost:${port}`]);
      const origins = new Set([...hosts].map((host) => `http://${host}`));
      if (!hosts.has(String(req.headers.host))) return send(res, 403, { error: { message: '只能在这台电脑上用 127.0.0.1 打开' } });
      if (req.headers.origin && !origins.has(req.headers.origin)) return send(res, 403, { error: { message: '拒绝其他网页的请求' } });
      if (req.method !== 'GET' && req.method !== 'HEAD' && req.headers['x-pocket-admin'] !== '1') return send(res, 403, { error: { message: '缺少管理页请求头' } });
      const url = new URL(req.url, `http://127.0.0.1:${port}`);
      const file = FILES[url.pathname];
      if (file && req.method === 'GET') return send(res, 200, await readFile(path.join(UI, file[0])), file[1]);
      if (url.pathname.startsWith('/api/v1/')) {
        // Same endpoints the phone uses, with this PC's own token.
        req.url = req.url.slice('/api'.length);
        req.headers.authorization = `Bearer ${config.token}`;
        return requestListener(req, res);
      }
      if (url.pathname === '/api/admin/runs' && req.method === 'GET') {
        const limit = Math.min(Math.max(Number(url.searchParams.get('limit')) || 50, 1), 200);
        return send(res, 200, { runs: (await vault.recentRuns(limit)).map(runSummary) });
      }
      const runMatch = url.pathname.match(RUN_PATH);
      if (runMatch && req.method === 'GET') {
        const text = await vault.runMarkdown(runMatch[1], runMatch[2]);
        return text === null ? send(res, 404, { error: { message: '记录不存在' } }) : send(res, 200, text, 'text/plain; charset=utf-8');
      }
      if (url.pathname === '/api/admin/logs' && req.method === 'GET') {
        const lines = Math.min(Math.max(Number(url.searchParams.get('lines')) || 200, 10), 1000);
        return send(res, 200, { server: await tail(path.join(dataDir, 'server.log'), lines), supervisor: await tail(path.join(dataDir, 'supervisor.log'), 50) });
      }
      if (url.pathname === '/api/admin/connection') {
        if (req.method === 'PATCH') {
          let body = '';
          for await (const chunk of req) { body += chunk; if (body.length > 4096) return send(res, 413, { error: { message: '内容过长' } }); }
          const value = String(JSON.parse(body || '{}').publicBaseUrl ?? '').trim();
          if (value && !/^https:\/\/[^\s/?#@]+(:\d+)?\/v1\/?$/.test(value)) return send(res, 400, { error: { message: '远程地址应为 https://域名/v1' } });
          await saveSettings(dataDir, { publicBaseUrl: value });
          config.publicBaseUrl = value;
        } else if (req.method !== 'GET') return send(res, 405, { error: { message: '只支持 GET 和 PATCH' } });
        const info = await stat(path.join(dataDir, 'gateway-token.txt')).catch(() => null);
        return send(res, 200, {
          lan_url: config.lanMode === true ? `http://${config.host}:${config.port}/v1` : null,
          local_url: `http://127.0.0.1:${config.port}/v1`,
          public_url: config.publicBaseUrl || null,
          token: config.token,
          token_created_at: info ? new Date(info.birthtimeMs || info.mtimeMs).toISOString() : null,
          data_dir: dataDir,
        });
      }
      return send(res, 404, { error: { message: '路径不存在' } });
    } catch (error) {
      if (!res.headersSent) send(res, 500, { error: { message: error?.message || '管理页出错' } });
    }
  });
}
