import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { createServer } from '../src/server.mjs';
import { createVault } from '../src/vault.mjs';
import { runCompletion } from '../src/pipeline.mjs';
import { REPAIR_LIMITS, REPAIR_SYSTEM, applyRepair, damagedSpots, parseRepair, repairMessages, withoutDamage } from '../src/repair.mjs';

const X = '\ufffd';
// As replies have arrived: one character replaced by two or three marks.
const BROKEN = `家里并不宽裕，父母都${X}${X}${X}，她放学后要去打工。\n过了好${X}${X}会儿才斜眼瞥过来。`;
const WHOLE = '家里并不宽裕，父母都忙，她放学后要去打工。\n过了好一会儿才斜眼瞥过来。';

test('damaged places are found and each gets a line of context with the place marked', () => {
  const spots = damagedSpots(BROKEN);
  assert.deepEqual(spots.map((spot) => spot.length), [3, 2]);
  assert.deepEqual(damagedSpots('没有损坏'), []);
  const [system, user] = repairMessages(BROKEN, spots);
  assert.equal(system.content, REPAIR_SYSTEM);
  const lines = user.content.split('\n');
  assert.equal(lines.length, 2);
  assert.equal(lines[0], '1. 家里并不宽裕，父母都⟦?⟧，她放学后要去打工。 过了好□会儿才斜眼瞥过来。');
  assert.match(lines[1], /^2\. .*过了好⟦\?⟧会儿才斜眼瞥过来。$/);
  assert.doesNotMatch(user.content, new RegExp(X), 'the marks themselves are not sent');
  const long = `${'前'.repeat(200)}${X}${X}${'后'.repeat(200)}`;
  assert.equal(repairMessages(long, damagedSpots(long))[1].content.length, '1. '.length + REPAIR_LIMITS.context * 2 + '⟦?⟧'.length);
});

test('an answer is used only when it is one lost character for each place', () => {
  const spots = damagedSpots(BROKEN);
  assert.deepEqual(parseRepair('好的：\n```json\n["忙","一"]\n```', 2), ['忙', '一']);
  assert.equal(parseRepair('["忙"]', 2), null, 'one answer short');
  assert.equal(parseRepair('我不知道', 2), null);
  assert.equal(parseRepair('[忙,一]', 2), null);
  assert.deepEqual(applyRepair(BROKEN, spots, ['忙', '一']), { text: WHOLE, repaired: 2, left: 0 });
  // A place whose answer is not a single non-ASCII character stays as it came; the others are still filled.
  for (const bad of ['忙碌', 'a', '', ' ', X, null, 7, '\n']) {
    const result = applyRepair(BROKEN, spots, [bad, '一']);
    assert.equal(result.repaired, 1, JSON.stringify(bad));
    assert.ok(result.text.includes(`父母都${X}${X}${X}，`) && result.text.includes('好一会儿'));
  }
  assert.equal(applyRepair(BROKEN, spots, null).text, BROKEN);
  assert.equal(applyRepair(BROKEN, spots, ['忙']).repaired, 0);
  const emoji = `开心${X}${X}${X}${X}！`;
  assert.equal(applyRepair(emoji, damagedSpots(emoji), ['🫠']).text, '开心🫠！', 'a four-byte character leaves up to four marks');
  const wide = `坏了${X.repeat(5)}一片`;
  assert.equal(applyRepair(wide, damagedSpots(wide), ['字']).repaired, 0, 'a longer run is not one cut character');
});

test('marks in earlier replies are left out of what goes upstream; other messages are untouched', () => {
  const messages = [
    { role: 'system', content: `规则${X}` },
    { role: 'user', content: `我发的${X}${X}` },
    { role: 'assistant', content: BROKEN },
    { role: 'assistant', content: [{ type: 'text', text: `好${X}${X}会儿` }, { type: 'image_url', image_url: { url: 'data:x' } }] },
    { role: 'assistant', content: '完好的回复' },
    { role: 'tool', content: `网页${X}` },
  ];
  const clean = withoutDamage(messages);
  assert.equal(clean[0], messages[0]);
  assert.equal(clean[1], messages[1]);
  assert.equal(clean[2].content, WHOLE.replace('忙', '').replace('一', ''));
  assert.equal(clean[3].content[0].text, '好会儿');
  assert.deepEqual(clean[3].content[1], messages[3].content[1]);
  assert.equal(clean[4], messages[4]);
  assert.equal(clean[5], messages[5]);
  assert.equal(messages[2].content, BROKEN, 'the request itself is not changed');
  assert.equal(withoutDamage(undefined), undefined);
});

async function fixture() {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'pocket-repair-'));
  return { dataDir, vault: createVault({ dataDir }), config: { upstreamBaseUrl: 'https://api.venice.ai/api/v1', upstreamModel: 'deepseek', contextTokens: 1_000_000, reasoningEffort: 'high' } };
}

/** An upstream whose reply arrives damaged and whose second call is the repair request. */
function damagedUpstream(repairAnswer, calls = []) {
  return { calls, complete: async ({ body, onProgress }) => {
    calls.push(body);
    if (body.messages[0]?.content === REPAIR_SYSTEM) {
      if (repairAnswer instanceof Error) throw repairAnswer;
      return { id: 'r', content: repairAnswer, reasoning: '', finish_reason: 'stop', tool_calls: [], usage: { prompt_tokens: 80, completion_tokens: 6, total_tokens: 86 } };
    }
    const result = { id: 'd', content: '', reasoning: '想了想', finish_reason: 'stop', tool_calls: [], usage: { prompt_tokens: 100, completion_tokens: 40, total_tokens: 140 } };
    for (const piece of [BROKEN.slice(0, 12), BROKEN.slice(12)]) { result.content += piece; onProgress?.({ ...result }); }
    return result;
  } };
}

test('a damaged reply is repaired with one small request that does not stream or think', async () => {
  const { vault, config } = await fixture();
  const upstream = damagedUpstream('["忙","一"]');
  const result = await runCompletion({ request: { model: 'pocket-direct', temperature: 0.9, tools: [{ type: 'function' }],
    messages: [{ role: 'assistant', content: `上一条${X}${X}也坏过` }, { role: 'user', content: '继续' }] }, config, vault, upstream });
  assert.equal(result.content, WHOLE);
  assert.equal(result.repaired_characters, 2);
  assert.equal(upstream.calls.length, 2);
  const [first, repair] = upstream.calls;
  assert.equal(first.stream, true);
  assert.equal(first.messages[0].content, '上一条也坏过', 'old marks are not sent back to the model');
  assert.equal(repair.stream, false);
  assert.equal(repair.temperature, 0);
  assert.equal(repair.model, 'deepseek');
  assert.equal(repair.reasoning_effort, 'none');
  assert.equal(repair.venice_parameters.disable_thinking, true);
  assert.equal('tools' in repair, false);
  assert.equal(repair.messages.length, 2);
  assert.deepEqual(result.pocket_tavern_stages.map((stage) => [stage.name, stage.status]), [['direct', 'complete'], ['repair', 'complete']]);
  assert.equal(result.usage.total_tokens, 226, 'the repair request is counted');
});

test('a reply stays as it came when the repair is refused, wrong, fails or is switched off', async () => {
  for (const [answer, extra, expectedCalls] of [['我不确定', {}, 2], ['["忙"]', {}, 2], [new Error('network down'), {}, 2], ['["忙","一"]', { repairDamagedText: false }, 1]]) {
    const { vault, config } = await fixture();
    const upstream = damagedUpstream(answer);
    const result = await runCompletion({ request: { model: 'pocket-direct', messages: [{ role: 'user', content: '继续' }] }, config: { ...config, ...extra }, vault, upstream });
    assert.equal(result.content, BROKEN, String(answer));
    assert.equal(upstream.calls.length, expectedCalls, String(answer));
    assert.equal(result.finish_reason, 'stop');
  }
  // An undamaged reply costs nothing extra.
  const { vault, config } = await fixture();
  let count = 0;
  const result = await runCompletion({ request: { model: 'pocket-direct', messages: [{ role: 'user', content: '继续' }] }, config, vault,
    upstream: { complete: async () => { count++; return { id: 'x', content: WHOLE, reasoning: '', finish_reason: 'stop', tool_calls: [] }; } } });
  assert.equal(count, 1);
  assert.equal(result.content, WHOLE);
  // A cancelled request is not swallowed.
  const cancelled = await fixture();
  await assert.rejects(runCompletion({ request: { model: 'pocket-direct', messages: [{ role: 'user', content: '继续' }] }, config: cancelled.config, vault: cancelled.vault,
    upstream: damagedUpstream(Object.assign(new Error('请求已取消'), { name: 'AbortError' })) }), (error) => error.name === 'AbortError');
});

test('the phone sees the stream as it came, then the corrected reply as a whole message', async () => {
  const { dataDir, vault } = await fixture();
  const server = createServer({ config: { token: 'tok', dataDir, upstreamBaseUrl: 'https://api.venice.ai/api/v1', upstreamModel: 'deepseek' }, vault, upstream: damagedUpstream('["忙","一"]'),
    reach: { run: async () => ({ ok: true }), status: async () => ({ available: false }) } });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  const post = (stream) => fetch(`${base}/v1/chat/completions`, { method: 'POST', headers: { authorization: 'Bearer tok', 'content-type': 'application/json' },
    body: JSON.stringify({ model: 'pocket-direct', stream, messages: [{ role: 'user', content: '继续' }] }) });
  try {
    const events = (await (await post(true)).text()).split('\n').filter((line) => line.startsWith('data: {')).map((line) => JSON.parse(line.slice(6)));
    const streamed = events.map((event) => event.choices?.[0]?.delta?.content || '').join('');
    assert.equal(streamed, BROKEN, 'the live text is what the upstream sent');
    const whole = events.filter((event) => event.choices?.[0]?.message);
    assert.equal(whole.length, 1);
    assert.deepEqual(whole[0].choices[0].message, { role: 'assistant', content: WHOLE });
    const finish = events.findIndex((event) => event.choices?.[0]?.finish_reason === 'stop');
    assert.ok(events.indexOf(whole[0]) < finish, 'the corrected reply comes before the end of the stream');
    const plain = await (await post(false)).json();
    assert.equal(plain.choices[0].message.content, WHOLE);
    const status = await (await fetch(`${base}/v1/pocket/status`, { headers: { authorization: 'Bearer tok' } })).json();
    assert.ok(status.features.includes('text_repair'));
  } finally {
    await new Promise((resolve) => server.close(resolve));
    await fs.rm(dataDir, { recursive: true, force: true });
  }
});
