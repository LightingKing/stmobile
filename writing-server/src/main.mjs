import http from 'node:http';
import { createWriteStream, existsSync, mkdirSync, renameSync, rmSync, statSync } from 'node:fs';
import { writeFile } from 'node:fs/promises';
import path from 'node:path';
import { format } from 'node:util';
import { loadConfig, defaultDataDir } from './config.mjs';
import { createServer } from './server.mjs';
import { createAdminServer } from './admin.mjs';

const LOG_LIMIT = 5 * 1024 * 1024;
// Generations already paid for get this long to finish when the service is asked to stop.
const STOP_GRACE_MS = 60_000;

/** Appends console output to server.log in the private directory, keeping one older file. */
function logToFile(dataDir) {
  mkdirSync(dataDir, { recursive: true, mode: 0o700 });
  const file = path.join(dataDir, 'server.log');
  try { if (statSync(file).size > LOG_LIMIT) renameSync(file, `${file}.1`); } catch { /* first run */ }
  const out = createWriteStream(file, { flags: 'a', mode: 0o600 });
  out.on('error', () => { /* logging must never stop the service */ });
  for (const level of ['log', 'warn', 'error']) {
    const original = console[level].bind(console);
    console[level] = (...args) => { original(...args); out.write(`${new Date().toISOString()} ${level.toUpperCase()} ${format(...args)}\n`); };
  }
}

const dataDir = defaultDataDir();
try { logToFile(dataDir); } catch { /* the console still works */ }
const stopFile = path.join(dataDir, 'stop.request');
const pidFile = path.join(dataDir, 'server.pid');
rmSync(stopFile, { force: true });

try {
  const config = await loadConfig(dataDir);
  // Loopback is always served so a tunnel on this PC (NPS npc, Tailscale serve) can reach the
  // service; LAN mode adds the private address for phones on the same network.
  const hosts = [...new Set(['127.0.0.1', config.host])];
  const server = createServer({ config: { ...config, listenHosts: hosts } });
  const servers = hosts.map((host, index) => {
    const instance = index === 0 ? server : http.createServer(server.pocket.requestListener);
    if (index > 0) instance.on('connection', (socket) => socket.setKeepAlive(true, 30_000));
    instance.requestTimeout = 0;
    instance.timeout = 0;
    instance.headersTimeout = 60_000;
    return instance;
  });

  let stopping = false;
  const shutdown = async (reason) => {
    if (stopping) return;
    stopping = true;
    console.log(`Stopping: ${reason}.`);
    for (const instance of servers) instance.close();
    const deadline = Date.now() + STOP_GRACE_MS;
    while (server.pocket.activeRequests() > 0 && Date.now() < deadline) await new Promise((resolve) => setTimeout(resolve, 500));
    server.pocket.abortAll();
    for (const instance of servers) instance.closeAllConnections();
    rmSync(pidFile, { force: true });
    process.exit(0);
  };

  await Promise.all(servers.map((instance, index) => new Promise((resolve, reject) => {
    instance.once('error', reject);
    instance.listen(config.port, hosts[index], () => { instance.off('error', reject); resolve(); });
  }))).catch((error) => {
    // A stale LAN address (DHCP moved the PC) also lands here; the supervisor refreshes it and retries.
    console.error(error.code === 'EADDRINUSE' ? '写作服务端口已被使用。' : `写作服务无法监听（${error.code || '未知错误'}），请检查本机地址。`);
    process.exit(1);
  });
  // The dashboard is optional: a busy port is logged, the service runs on.
  const admin = createAdminServer({ requestListener: server.pocket.requestListener, config, vault: server.pocket.vault, dataDir: config.dataDir });
  admin.on('error', (error) => console.warn(`电脑管理页没有启动（${error.code || error.message}）。`));
  admin.listen(config.adminPort, '127.0.0.1', () => console.log(`Dashboard on http://127.0.0.1:${config.adminPort}/`));
  servers.push(admin);
  await writeFile(pidFile, String(process.pid), { mode: 0o600 });
  console.log(`Pocket writing server listening on ${hosts.map((host) => `${host}:${config.port}`).join(', ')}`);
  console.log(config.upstreamKey ? 'Upstream credential configured; writing quality is not yet verified.' : 'Upstream credential missing. Models, status and backups available; generation disabled.');
  for (const event of ['SIGINT', 'SIGTERM']) process.once(event, () => shutdown(event));
  // stop.ps1 asks through a file, so only someone on this PC can stop the service.
  setInterval(() => { if (existsSync(stopFile)) shutdown('stop requested'); }, 1000).unref();
  // Ending the logon task kills run.ps1 but not this process; leave with it so the port is free for the next start.
  const supervisor = Number(process.env.POCKET_SUPERVISOR_PID);
  if (Number.isInteger(supervisor) && supervisor > 0) setInterval(() => {
    try { process.kill(supervisor, 0); } catch (error) { if (error.code === 'ESRCH') shutdown('supervisor ended'); }
  }, 5000).unref();
} catch {
  console.error('服务启动失败。请检查私密目录中的 config.json、Token 文件和已固定版本的审校 Skill；没有输出凭据。');
  process.exitCode = 1;
}
