// Continue a bounded evaluation without paying to regenerate its draft.
import { readFile, writeFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { loadConfig } from '../src/config.mjs';
import { createVault } from '../src/vault.mjs';
import { createUpstream } from '../src/upstream.mjs';
import { runCompletion } from '../src/pipeline.mjs';

const config = await loadConfig();
const folder = path.resolve(process.argv[2] || '');
const evaluationRoot = path.resolve(config.dataDir, 'evaluations');
if (!folder.startsWith(evaluationRoot + path.sep)) throw new Error('只允许继续本机 evaluations 中的明确记录目录');
const ledgerPath = path.join(folder, 'ledger.json');
const ledger = JSON.parse(await readFile(ledgerPath, 'utf8'));
if (ledger.model !== config.upstreamModel || ledger.limit !== 6 || ledger.attempted >= 6) throw new Error('对照模型不一致或已达到总请求上限');
const mapping = JSON.parse(await readFile(path.join(folder, 'mapping.json'), 'utf8'));
const oldVault = createVault({ dataDir: folder });
const oldId = path.basename(folder);
const original = await oldVault.getRun(`${oldId}-writer`, mapping.writerRun);
if (!original?.draft?.content) throw new Error('原草稿不存在，未发起付费请求');
const dest = path.join(folder, 'review-retry');
await mkdir(dest, { recursive: true });
const remote = createUpstream({ baseUrl: config.upstreamBaseUrl, key: config.upstreamKey });
let reused = false;
const upstream = { complete: async args => {
  if (!reused) {
    reused = true;
    return { ...original.draft, usage: null };
  }
  if (ledger.attempted >= 6) throw new Error('包含此前尝试已达到6次上限，不再请求');
  ledger.attempted++;
  const entry = { call: ledger.attempted, status: 'started', startedAt: new Date().toISOString(), requestedMaxCompletionTokens: 10000,
    stage: ledger.attempted === 4 ? 'audit-retry' : 'revision', reasoningEffort: args.body.reasoning_effort ?? 'default' };
  ledger.calls.push(entry);
  await writeFile(ledgerPath, JSON.stringify(ledger, null, 2));
  try {
    const result = await remote.complete({ ...args, body: { ...args.body, max_completion_tokens: 10000 } });
    Object.assign(entry, { status: 'completed', completedAt: new Date().toISOString(), usage: result.usage, finishReason: result.finish_reason });
    await writeFile(ledgerPath, JSON.stringify(ledger, null, 2));
    return result;
  } catch (error) {
    Object.assign(entry, { status: 'failed', completedAt: new Date().toISOString(), code: error.code });
    await writeFile(ledgerPath, JSON.stringify(ledger, null, 2));
    throw error;
  }
} };
const result = await runCompletion({ request: original.request, config, vault: createVault({ dataDir: dest }), upstream });
await writeFile(path.join(dest, 'before.md'), original.draft.content);
await writeFile(path.join(dest, 'after.md'), result.content);
await writeFile(path.join(dest, 'result.json'), JSON.stringify({ run_id: result.run_id, warning: result.warning, sourceRun: original.run_id,
  reusedDraft: true, additionalUsage: result.usage, totalAttempted: ledger.attempted }, null, 2));
console.log(JSON.stringify({ saved: dest, totalAttempted: ledger.attempted, warning: result.warning || null }));
