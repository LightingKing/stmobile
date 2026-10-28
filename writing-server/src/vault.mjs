import crypto from 'node:crypto';
import fs from 'node:fs/promises';
import path from 'node:path';

const SECRET_PATTERN = /(?:sk-[A-Za-z0-9_-]{8,}|Bearer\s+[A-Za-z0-9._~+/=-]{12,}|(?:api[-_ ]?key|authorization|token)\s*[:=]\s*[^\s,}]+)/gi;

function redactText(value) {
  return String(value ?? '').replace(SECRET_PATTERN, '[REDACTED]');
}

function redact(value) {
  if (typeof value === 'string') return redactText(value);
  if (Array.isArray(value)) return value.map(redact);
  if (!value || typeof value !== 'object') return value;
  const result = {};
  for (const [key, child] of Object.entries(value)) {
    if (/^(authorization|api[-_ ]?key|x-api-key|token|secret|password)$/i.test(key)) result[key] = '[REDACTED]';
    else result[key] = redact(child);
  }
  return result;
}

function digest(value) {
  return crypto.createHash('sha256').update(String(value)).digest('hex').slice(0, 40);
}

function runId() {
  return `run-${Date.now().toString(36)}-${crypto.randomBytes(8).toString('hex')}`;
}

async function atomicWrite(file, text) {
  // Windows packaged-app paths may redirect C: AppData to another volume.
  // Resolve the directory before both operations so rename remains same-volume.
  const destination = path.join(await fs.realpath(path.dirname(file)), path.basename(file));
  const temp = `${destination}.${process.pid}.${crypto.randomBytes(4).toString('hex')}.tmp`;
  await fs.writeFile(temp, text, 'utf8');
  await fs.rename(temp, destination);
}

function markdown(run) {
  const lines = [
    `# Pocket Tavern run ${run.run_id}`,
    '',
    `- 状态：${run.status}`,
    `- 会话哈希：${run.chat_hash}`,
    `- 模型：${run.model}`,
    `- 用途：${run.purpose}`,
    `- 创建：${run.created_at}`,
    `- 更新：${run.updated_at}`,
    '',
    `- [完整 JSON 记录](./${run.run_id}.json)`,
    '',
    '## 来源',
    '',
    `[查看请求原文与 metadata](./${run.run_id}.json#request)`,
    '',
    ...(run.request?.messages || []).map((message) => `**${message.role || 'message'}**：${typeof message.content === 'string' ? message.content : JSON.stringify(message.content || '')}`),
    '',
    '## 草稿',
    '',
    run.draft?.content || '（无正文）',
    '',
    run.draft?.reasoning ? `> 草稿 reasoning：${run.draft.reasoning}` : '',
    '',
    '## 审校',
    '',
    run.audit_response?.content || (run.audit ? `\`${JSON.stringify(run.audit)}\`` : '（未执行）'),
    '',
    `[查看审校原始记录](./${run.run_id}.json#audit_response)`,
    '',
    '## 最终',
    '',
    run.final?.content || '（未生成通过审校的最终正文）',
    '',
    run.final?.reasoning ? `> 最终 reasoning：${run.final.reasoning}` : '',
    '',
    '## 阶段',
    '',
  ];
  for (const stage of run.stages ?? []) {
    lines.push(`### ${stage.name}`, '', stage.status ?? '', '');
  }
  if (run.error) lines.push('## 错误', '', redactText(run.error), '');
  if (run.warning) lines.push('## 警告', '', redactText(run.warning), '');
  return `${lines.join('\n')}\n`;
}

export function createVault({ dataDir }) {
  if (!dataDir) throw new Error('vault dataDir is required');
  const root = path.resolve(dataDir);

  function chatHash(chatId) { return digest(chatId); }
  function chatDir(chatId) { return path.join(root, 'chats', chatHash(chatId), 'runs'); }
  function fileFor(chatId, id, extension = 'json') { return path.join(chatDir(chatId), `${id}.${extension}`); }

  async function save(run) {
    run.updated_at = new Date().toISOString();
    const dir = chatDir(run.chat_id);
    await fs.mkdir(dir, { recursive: true });
    const persisted = redact(run);
    await atomicWrite(fileFor(run.chat_id, run.run_id), `${JSON.stringify(persisted, null, 2)}\n`);
    await atomicWrite(fileFor(run.chat_id, run.run_id, 'md'), markdown(persisted));
    return persisted;
  }

  return {
    root,
    chatHash,
    async startRun({ chatId, model, purpose, request }) {
      if (!String(chatId || '').trim()) throw new Error('chatId is required for a vault run');
      const now = new Date().toISOString();
      const run = {
        run_id: runId(),
        chat_id: String(chatId),
        chat_hash: chatHash(chatId),
        model: String(model || ''),
        purpose: String(purpose || 'chat'),
        status: 'running',
        created_at: now,
        updated_at: now,
        request: redact(request),
        stages: [],
      };
      return save(run);
    },
    async updateRun(run, patch) {
      return save({ ...run, ...redact(patch) });
    },
    async finishRun(run, result) {
      return save({ ...run, status: 'complete', final: redact(result), error: undefined });
    },
    async failRun(run, error, partial = undefined) {
      return save({ ...run, status: 'failed', error: redactText(error?.message || error), partial: redact(partial) });
    },
    async listRuns(chatId) {
      const dir = chatDir(chatId);
      const entries = await fs.readdir(dir, { withFileTypes: true }).catch(() => []);
      const result = [];
      for (const entry of entries) {
        if (!entry.isFile() || !entry.name.endsWith('.json')) continue;
        try { result.push(JSON.parse(await fs.readFile(path.join(dir, entry.name), 'utf8'))); } catch { /* ignore corrupt diagnostics */ }
      }
      return result.sort((a, b) => String(b.created_at).localeCompare(String(a.created_at)));
    },
    async getRun(chatId, id) {
      if (!/^[A-Za-z0-9._-]{1,120}$/.test(id)) return null;
      try { return JSON.parse(await fs.readFile(fileFor(chatId, id), 'utf8')); } catch { return null; }
    },
  };
}

export { redact };
