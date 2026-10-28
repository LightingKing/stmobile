import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createVault } from '../src/vault.mjs';

test('vault hashes chat paths and redacts secrets while retaining explicit content', async () => {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'pocket-vault-'));
  const vault = createVault({ dataDir });
  const run = await vault.startRun({ chatId: '../not-a-path', model: 'pocket-writer', purpose: 'chat', request: { authorization: 'Bearer secret-value', messages: [{ role: 'user', content: 'hello' }] } });
  await vault.finishRun(run, { content: 'answer', usage: { total_tokens: 1 } });
  const files = await fs.readdir(path.join(dataDir, 'chats', vault.chatHash('../not-a-path'), 'runs'));
  assert.ok(files.some((name) => name.endsWith('.json')));
  assert.ok(files.some((name) => name.endsWith('.md')));
  const stored = await vault.getRun('../not-a-path', run.run_id);
  assert.equal(stored.request.authorization, '[REDACTED]');
  assert.equal(stored.final.content, 'answer');
});

test('vault rejects unknown run path ids', async () => {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'pocket-vault-'));
  const vault = createVault({ dataDir });
  assert.equal(await vault.getRun('chat', '../escape'), null);
});
