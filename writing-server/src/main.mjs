import { writeFile } from 'node:fs/promises';
import path from 'node:path';
import { loadConfig } from './config.mjs';
import { createServer } from './server.mjs';

try {
  const config = await loadConfig();
  const server = createServer({ config });
  server.requestTimeout = 0;
  server.timeout = 0;
  server.headersTimeout = 60_000;
  server.on('error', error => {
    console.error(error.code === 'EADDRINUSE' ? '写作服务端口已被使用。' : '写作服务启动失败，请检查本机配置。');
    process.exitCode = 1;
  });
  server.listen(config.port, config.host, async () => {
    await writeFile(path.join(config.dataDir, 'server.pid'), String(process.pid), { mode: 0o600 });
    console.log(`Pocket writing server listening on ${config.host}:${config.port}`);
    console.log(config.upstreamKey ? 'Upstream credential configured; writing quality is not yet verified.' : 'Upstream credential missing. Models and diagnostics available; generation disabled.');
  });
  for (const event of ['SIGINT', 'SIGTERM']) process.once(event, () => {
    server.close(() => { process.exitCode = 0; });
    server.closeIdleConnections();
  });
} catch {
  console.error('服务启动失败。请检查私密目录中的 config.json、Token 文件和已固定版本的审校 Skill；没有输出凭据。');
  process.exitCode = 1;
}
