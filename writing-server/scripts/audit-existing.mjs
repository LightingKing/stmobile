// One remaining audit probe; shares the original evaluation's durable six-call ledger.
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { loadConfig } from '../src/config.mjs';
import { createVault } from '../src/vault.mjs';
import { createUpstream } from '../src/upstream.mjs';
import { buildAuditRequest, parseAudit } from '../src/pipeline.mjs';

const config = await loadConfig();
const folder = path.resolve(process.argv[2] || '');
if (!folder.startsWith(path.resolve(config.dataDir, 'evaluations') + path.sep)) throw new Error('必须指定既有 evaluations 目录');
const ledgerFile = path.join(folder, 'ledger.json');
const ledger = JSON.parse(await readFile(ledgerFile, 'utf8'));
if (ledger.attempted >= 6 || ledger.limit !== 6 || ledger.model !== config.upstreamModel) throw new Error('模型不匹配或6次预算已用完');
const mapping = JSON.parse(await readFile(path.join(folder, 'mapping.json'), 'utf8'));
const source = await createVault({ dataDir: folder }).getRun(`${path.basename(folder)}-writer`, mapping.writerRun);
if (!source?.draft?.content) throw new Error('没有原草稿');
const entry = { call: ++ledger.attempted, stage: 'audit-correctness-probe', status: 'started', startedAt: new Date().toISOString(), requestedMaxCompletionTokens: 10000 };
ledger.calls.push(entry);
await writeFile(ledgerFile, JSON.stringify(ledger, null, 2));
try {
  const body = { ...buildAuditRequest(source.request, source.draft, config), max_completion_tokens: 10000 };
  const result = await createUpstream({ baseUrl: config.upstreamBaseUrl, key: config.upstreamKey }).complete({ body });
  const parsed = parseAudit(result.content, source.draft, source.request.messages);
  await writeFile(path.join(folder, 'audit-final-probe.json'), JSON.stringify({ result, parsed, didRevise: false, sourceRun: source.run_id }, null, 2));
  Object.assign(entry, { status: 'completed', completedAt: new Date().toISOString(), usage: result.usage, finishReason: result.finish_reason });
  console.log(JSON.stringify({ totalAttempted: ledger.attempted, validEvidence: parsed.valid, issues: parsed.issues.length, didRevise: false }));
} catch (error) {
  Object.assign(entry, { status: 'failed', completedAt: new Date().toISOString(), code: error.code });
  process.exitCode = 1;
  console.error('审校探测未完成，未重试。');
} finally {
  await writeFile(ledgerFile, JSON.stringify(ledger, null, 2));
}
