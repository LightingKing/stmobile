import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createVault } from '../src/vault.mjs';
import { runCompletion, buildAuditRequest, repeatsPreviousOpening } from '../src/pipeline.mjs';

test('audit extracts evidence without changing creative thinking mode or adding budgets', () => {
  const body = buildAuditRequest({ messages: [{ role: 'user', content: '不要加新道具' }] }, { content: '草稿' },
    { upstreamBaseUrl: 'https://api.venice.ai/api/v1', upstreamModel: 'deepseek', reasoningEffort: 'high' });
  assert.equal(body.reasoning_effort, 'none');
  assert.equal(body.venice_parameters.disable_thinking, true);
  assert.equal('max_completion_tokens' in body, false);
  assert.equal('max_tokens' in body, false);
});

test('revision cannot newly replay a previous complete assistant opening', () => {
  const previous = '已经写过的上一轮正文。'.repeat(5);
  const messages = [{ role: 'assistant', content: previous }, { role: 'user', content: '继续' }];
  assert.equal(repeatsPreviousOpening(messages, '本轮草稿', previous + '\n\n修订'), true);
  assert.equal(repeatsPreviousOpening(messages, '本轮草稿', '继续推进的文字'), false);
  assert.equal(repeatsPreviousOpening(messages, previous + '原本如此', previous + '保留'), false);
});

async function fixture() {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'pocket-writing-'));
  return { vault: createVault({ dataDir }), config: { upstreamModel: 'deepseek-v4-1-flash-1017', contextTokens: 1_000_000, autoRevise: true } };
}

test('default audit mode never automatically replaces the paid draft', async () => {
  const { vault, config } = await fixture();
  let count = 0;
  const result = await runCompletion({ request: { model: 'pocket-writer', messages: [{ role: 'user', content: '继续' }], metadata: { pocket_tavern_chat_id: 'audit-only' } },
    config: { ...config, autoRevise: false }, vault,
    upstream: { complete: async () => {
      count++;
      return { id: 'x', content: count === 1 ? '原始草稿' : '{"issues":[{"problem":"可能误判","draft_quote":"原始草稿","input_evidence":"继续","fix":"不要自动采用"}]}', reasoning: '', finish_reason: 'stop', tool_calls: [] };
    } } });
  assert.equal(count, 2);
  assert.equal(result.content, '原始草稿');
  assert.match(result.reasoning, /可能误判/);
  assert.equal(result.warning, 'experimental_audit_only');
});

test('writer performs draft audit and one revision for actionable issues', async () => {
  const { vault, config } = await fixture();
  const calls = [];
  const upstream = { complete: async ({ body }) => {
    calls.push(body);
    if (calls.length === 1) return { id: 'draft', content: '草稿', reasoning: 'draft-thought', tool_calls: [], finish_reason: 'stop', usage: { total_tokens: 3 } };
    if (calls.length === 2) return { id: 'audit', content: '{"issues":[{"problem":"因果跳跃","draft_quote":"草稿","input_evidence":"继续","fix":"补足过渡"}]}', reasoning: 'audit-thought', tool_calls: [], finish_reason: 'stop', usage: { total_tokens: 4 } };
    return { id: 'revision', content: '修订正文', reasoning: 'revision-thought', tool_calls: [], finish_reason: 'stop', usage: { total_tokens: 5 } };
  } };
  const result = await runCompletion({
    request: { model: 'pocket-writer', stream: true, tools: [{ type: 'function', function: { name: 'history_search' } }], messages: [{ role: 'user', content: '继续' }], metadata: { pocket_tavern_chat_id: 'chat/1', pocket_tavern_purpose: 'chat' } },
    config, vault, upstream,
  });
  assert.equal(result.content, '修订正文');
  assert.equal(calls.length, 3);
  assert.equal(calls[0].model, config.upstreamModel);
  assert.equal(calls[0].stream, true);
  assert.equal(calls[1].messages[0].role, 'system');
  assert.equal(calls[1].stream, true);
  assert.equal('tools' in calls[1], false);
  assert.equal('tools' in calls[2], false);
  assert.equal(calls[2].messages.at(-1).role, 'user');
  assert.equal(result.usage.total_tokens, 12);
  assert.match(result.reasoning, /draft-thought/);
  assert.match(result.reasoning, /revision-thought/);
  const runs = await vault.listRuns('chat/1');
  assert.equal(runs[0].status, 'complete');
  assert.equal(runs[0].stages.length, 3);
});

test('summary and tool-call drafts bypass audit and revision', async () => {
  const { vault, config } = await fixture();
  let count = 0;
  const upstream = { complete: async () => { count++; return { id: 'direct', content: '', reasoning: '', tool_calls: [{ id: 'c1' }], finish_reason: 'tool_calls', usage: null }; } };
  const result = await runCompletion({
    request: { model: 'pocket-writer', messages: [{ role: 'user', content: '摘要' }], tools: [{ type: 'function' }], metadata: { pocket_tavern_chat_id: 'c', pocket_tavern_purpose: 'compact' } },
    config, vault, upstream,
  });
  assert.equal(count, 1);
  assert.equal(result.finish_reason, 'tool_calls');
});

test('writer requires a chat id and never injects historical run output', async () => {
  const { vault, config } = await fixture();
  await assert.rejects(() => runCompletion({ request: { model: 'pocket-writer', messages: [] }, config, vault, upstream: { complete: async () => { throw new Error('must not call'); } } }), /会话 ID/);
});

test('failed revision archives the paid draft and does not return an empty answer', async () => {
  const { vault, config } = await fixture();
  let count = 0;
  const upstream = { complete: async () => {
    count++;
    if (count === 1) return { id: 'draft', content: '已付费草稿', reasoning: '', tool_calls: [], finish_reason: 'stop', usage: { total_tokens: 1 } };
    if (count === 2) return { id: 'audit', content: '{"issues":[{"problem":"问题","draft_quote":"已付费草稿","input_evidence":"继续","fix":"修复"}]}', reasoning: '', tool_calls: [], finish_reason: 'stop', usage: { total_tokens: 1 } };
    throw new Error('revision unavailable');
  } };
  const result = await runCompletion({
    request: { model: 'pocket-writer', messages: [{ role: 'user', content: '继续' }], metadata: { pocket_tavern_chat_id: 'failed-chat' } },
    config, vault, upstream,
  });
  assert.equal(result.content, '已付费草稿');
  assert.match(result.warning, /修订阶段失败/);
  const failed = (await vault.listRuns('failed-chat'))[0];
  assert.equal(failed.status, 'failed');
  assert.equal(failed.partial.draft.content, '已付费草稿');
  assert.match(failed.partial.warning, /草稿仅归档/);
});

test('audit failure returns draft with visible warning and failed archive', async () => {
  const { vault, config } = await fixture();
  let count = 0;
  const upstream = { complete: async () => {
    count++;
    if (count === 1) return { id: 'draft', content: '可见草稿', reasoning: '', tool_calls: [], finish_reason: 'stop', usage: { total_tokens: 1 } };
    throw new Error('audit down');
  } };
  const result = await runCompletion({
    request: { model: 'pocket-writer', messages: [{ role: 'user', content: '继续' }], metadata: { pocket_tavern_chat_id: 'audit-fail' } }, config, vault, upstream,
  });
  assert.equal(result.content, '可见草稿');
  assert.match(result.warning, /审核阶段失败/);
  assert.match(result.service_notice, /不是正文/);
  assert.equal((await vault.listRuns('audit-fail'))[0].status, 'failed');
});

test('audit issues without exact source evidence cannot trigger revision', async () => {
  const { vault, config } = await fixture();
  let count = 0;
  const upstream = { complete: async () => {
    count++;
    if (count === 1) return { id: 'draft', content: '草稿正文', reasoning: '', tool_calls: [], finish_reason: 'stop', usage: null };
    return { id: 'audit', content: '{"issues":[{"problem":"凭空推断","draft_quote":"不存在","input_evidence":"不存在","fix":"改写"}]}', reasoning: '', tool_calls: [], finish_reason: 'stop', usage: null };
  } };
  const result = await runCompletion({
    request: { model: 'pocket-writer', messages: [{ role: 'user', content: '输入证据' }], metadata: { pocket_tavern_chat_id: 'bad-evidence' } }, config, vault, upstream,
  });
  assert.equal(count, 2);
  assert.equal(result.content, '草稿正文');
  assert.match(result.warning, /无可验证证据/);
});

test('review stages preserve Venice settings but non-Venice upstreams do not receive them', async () => {
  const { vault } = await fixture();
  const calls = [];
  const upstream = { complete: async ({ body }) => { calls.push(body); return { id: String(calls.length), content: calls.length === 1 ? '草稿' : '{"issues":[]}', reasoning: '', tool_calls: [], finish_reason: 'stop', usage: null }; } };
  await runCompletion({
    request: { model: 'pocket-direct', messages: [{ role: 'user', content: 'hi' }], reasoning_effort: 'high', max_tokens: 99, metadata: { pocket_tavern_chat_id: 'venice' } },
    config: { upstreamBaseUrl: 'https://api.venice.ai/api/v1', upstreamModel: 'deepseek', reasoningEffort: 'high', includeVeniceSystemPrompt: true }, vault, upstream,
  });
  assert.equal(calls[0].reasoning_effort, 'high');
  assert.equal(calls[0].venice_parameters.include_venice_system_prompt, true);
  assert.equal(calls[0].max_tokens, 99);

  const otherCalls = [];
  await runCompletion({
    request: { model: 'pocket-direct', messages: [{ role: 'user', content: 'hi' }], reasoning_effort: 'high', venice_parameters: { include_venice_system_prompt: true } },
    config: { upstreamBaseUrl: 'https://example.test/v1', upstreamModel: 'model', reasoningEffort: 'high', includeVeniceSystemPrompt: true }, vault, upstream: { complete: async ({ body }) => { otherCalls.push(body); return { id: 'x', content: 'ok', reasoning: '', tool_calls: [], finish_reason: 'stop', usage: null }; } },
  });
  assert.equal('reasoning_effort' in otherCalls[0], false);
  assert.equal('venice_parameters' in otherCalls[0], false);
});

test('small context estimate returns draft without paying for audit', async () => {
  const { vault } = await fixture();
  let count = 0;
  const result = await runCompletion({
    request: { model: 'pocket-writer', messages: [{ role: 'user', content: 'long input' }], metadata: { pocket_tavern_chat_id: 'small-window' } },
    config: { upstreamModel: 'model', contextTokens: 10 }, vault, upstream: { complete: async () => { count++; return { id: 'draft', content: '草稿', reasoning: '', tool_calls: [], finish_reason: 'stop', usage: null }; } },
  });
  assert.equal(count, 1);
  assert.match(result.warning, /容量估算/);
});

test('content-filter revision falls back to original draft', async () => {
  const { vault, config } = await fixture();
  let count = 0;
  const result = await runCompletion({
    request: { model: 'pocket-writer', messages: [{ role: 'user', content: '继续' }], metadata: { pocket_tavern_chat_id: 'filtered-revision' } }, config, vault,
    upstream: { complete: async () => {
      count++;
      if (count === 1) return { id: 'draft', content: '原始草稿', reasoning: '', tool_calls: [], finish_reason: 'stop', usage: null };
      if (count === 2) return { id: 'audit', content: '{"issues":[{"problem":"问题","draft_quote":"原始草稿","input_evidence":"继续","fix":"修复"}]}', reasoning: '', tool_calls: [], finish_reason: 'stop', usage: null };
      return { id: 'revision', content: '部分修订', reasoning: 'partial thought', tool_calls: [], finish_reason: 'content_filter', usage: null };
    } },
  });
  assert.equal(count, 3);
  assert.equal(result.content, '原始草稿');
  assert.match(result.warning, /content_filter/);
  assert.match(result.reasoning, /partial thought/);
});
