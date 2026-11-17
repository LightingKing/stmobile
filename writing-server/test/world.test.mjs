import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { readFileSync } from 'node:fs';
import {
  DIARY_RULES, WORLD_DEFAULTS, WorldError, createWorldEngine, directorMessages, extractJson, inQuiet, intervalMs,
  parseDirector, validatePack, worldTime,
} from '../src/world.mjs';
import { createServer } from '../src/server.mjs';
import { createVault } from '../src/vault.mjs';

const EXAMPLE = JSON.parse(readFileSync(new URL('../examples/world-302.json', import.meta.url), 'utf8'));
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const at = (iso) => Date.parse(iso);

/** A model that answers like the director, so tests can see what was asked. */
function fakeModel({ fail = () => false } = {}) {
  const calls = [];
  const run = async ({ messages, model, maxTokens }) => {
    const system = messages[0].content;
    const ask = messages.at(-1).content;
    calls.push({ system, ask, model, maxTokens });
    if (fail(ask)) throw new Error('上游 HTTP 500');
    let answer;
    if (system === DIARY_RULES) answer = { chapter: '这一天林晚赶稿，苏糖在群里欢迎新室友。', notes: [{ name: '林晚', text: '新室友帮忙喂过团子。' }] };
    else if (ask.includes('用户刚刚私聊了')) answer = { dms: [{ from: '林晚', text: '嗯嗯 我在' }, { from: '林晚', text: '怎么啦 (｡•ᴗ•｡)' }, { from: '苏糖', text: '我偷看到了！' }], residents: [{ name: '林晚', mood: '有点开心' }] };
    else if (ask.includes('用户刚刚在群里')) answer = { group: [{ from: '苏糖', text: '欢迎欢迎！！' }] };
    else answer = {
      residents: [{ name: '周屿', location: '公司', activity: '修 bug' }, { name: '不存在的人', location: '月球' }],
      events: [{ text: '团子打翻了水碗。', who: ['林晚'] }],
      group: [{ from: '苏糖', text: '今晚吃火锅吗' }, { from: '周屿（程序员）', text: '加班。' }],
      dms: [],
    };
    return { content: `<think>想一想</think>\n\`\`\`json\n${JSON.stringify(answer)}\n\`\`\``, usage: { prompt_tokens: 1000, completion_tokens: 100 } };
  };
  return { run, calls };
}

async function engineAt(start, options = {}) {
  const dataDir = options.dataDir || await fs.mkdtemp(path.join(os.tmpdir(), 'pocket-world-'));
  const clock = { now: start };
  const model = options.model || fakeModel();
  const engine = createWorldEngine({ dataDir, run: model.run, now: () => clock.now, log: { log() {}, warn() {}, error() {} }, checkEveryMs: 0 });
  return { engine, clock, model, dataDir };
}

/** Lets the scheduler look every 5 minutes for [hours], the way the real one looks every 30 seconds. */
async function live(engine, clock, hours, step = 5 * MINUTE) {
  const end = clock.now + hours * HOUR;
  while (clock.now < end) { clock.now += step; await engine.runDue(); }
}

test('the example world is a valid pack with the default settings', () => {
  const def = validatePack(EXAMPLE);
  assert.equal(def.residents.length, 4);
  assert.deepEqual(def.residents.map((r) => r.id), ['r1', 'r2', 'r3', 'r4']);
  assert.deepEqual(def.settings, WORLD_DEFAULTS);
  assert.equal(def.model, 'pocket-direct');
  assert.equal(def.groupName, '302 合租群');
});

test('packs outside the limits are refused with a reason', () => {
  const resident = { name: 'A', card: '设定' };
  const cases = [
    [{ ...EXAMPLE, residents: Array.from({ length: 9 }, (_, i) => ({ name: `R${i}`, card: '设定' })) }, /最多 8 位居民/],
    [{ ...EXAMPLE, residents: [resident, { ...resident }] }, /重复/],
    [{ ...EXAMPLE, residents: [] }, /至少要有一位居民/],
    [{ ...EXAMPLE, model: 'pocket-writer' }, /审校/],
    [{ ...EXAMPLE, settings: { tickMinutes: 5 } }, /15–120/],
    [{ ...EXAMPLE, settings: { quietStart: '25:00' } }, /HH:MM/],
    [{ ...EXAMPLE, settings: { surprise: true } }, /未知设置/],
    [{ ...EXAMPLE, setting: '' }, /缺少设定/],
  ];
  for (const [pack, message] of cases) assert.throws(() => validatePack(pack), (error) => error instanceof WorldError && message.test(error.message));
});

test('world time runs with real time and stands still while paused', () => {
  const state = { paused: false, anchorReal: 1000, anchorWorld: 50_000 };
  assert.equal(worldTime(state, 4000), 53_000);
  assert.equal(worldTime({ ...state, paused: true, pausedWorldAt: 51_000 }, 999_999), 51_000);
});

test('quiet hours, including ones across midnight', () => {
  const night = { quietStart: '01:00', quietEnd: '08:00' };
  assert.equal(inQuiet(at('2026-11-17T03:00:00+08:00'), night, 'Asia/Shanghai'), true);
  assert.equal(inQuiet(at('2026-11-17T08:00:00+08:00'), night, 'Asia/Shanghai'), false);
  const wrap = { quietStart: '23:00', quietEnd: '07:00' };
  assert.equal(inQuiet(at('2026-11-17T23:30:00+08:00'), wrap, 'Asia/Shanghai'), true);
  assert.equal(inQuiet(at('2026-11-17T06:59:00+08:00'), wrap, 'Asia/Shanghai'), true);
  assert.equal(inQuiet(at('2026-11-17T12:00:00+08:00'), wrap, 'Asia/Shanghai'), false);
  assert.equal(inQuiet(at('2026-11-17T03:00:00+08:00'), { quietStart: '00:00', quietEnd: '00:00' }, 'Asia/Shanghai'), false);
});

test('the pace slows after a day without the user looking, and again after two', () => {
  const settings = { ...WORLD_DEFAULTS };
  const now = 10 * 24 * HOUR;
  assert.equal(intervalMs(settings, { lastSeenAt: now - HOUR }, now), 30 * MINUTE);
  assert.equal(intervalMs(settings, { lastSeenAt: now - 25 * HOUR }, now), 60 * MINUTE);
  assert.equal(intervalMs(settings, { lastSeenAt: now - 49 * HOUR }, now), 120 * MINUTE);
  assert.equal(intervalMs({ ...settings, slowWhenAway: false }, { lastSeenAt: 0 }, now), 30 * MINUTE);
});

test('director replies are read through fences and thinking, kept to known residents and capped', () => {
  const def = validatePack(EXAMPLE);
  assert.deepEqual(extractJson('前言 {"a":1} 后记'), { a: 1 });
  assert.equal(parseDirector('不是 JSON', def), null);
  const many = { group: Array.from({ length: 20 }, () => ({ from: '苏糖', text: '啊' })), events: ['猫跑了'], residents: [{ name: '【林晚】', mood: '开心' }, { name: '路人', mood: '?' }] };
  const parsed = parseDirector(`<think>x</think>\`\`\`json\n${JSON.stringify(many)}\n\`\`\``, def);
  assert.equal(parsed.group.length, 8);
  assert.deepEqual(parsed.events, [{ text: '猫跑了', who: [] }]);
  assert.deepEqual(parsed.residents, [{ id: 'r1', mood: '开心' }]);
});

test('private messages are labelled as known only to the two people, and the user is never spoken for', () => {
  const def = validatePack(EXAMPLE);
  const state = { residents: { r1: { location: '卧室', notes: '新室友喜欢猫' } } };
  const now = at('2026-11-17T15:00:00+08:00');
  const timeline = [
    { seq: 1, kind: 'message', channel: 'dm:r1', from: 'user', text: '在吗', worldAt: now - MINUTE },
    { seq: 2, kind: 'message', channel: 'group', from: 'r3', name: '苏糖', text: '晚上吃啥', worldAt: now - 2 * MINUTE },
  ];
  const [system, user] = directorMessages({ def, state, timeline, diary: [], timeZone: 'Asia/Shanghai' }, { mode: 'dm', worldNow: now, target: def.residents[0] });
  assert.match(system.content, /不要替用户说话/);
  assert.match(system.content, /### 林晚/);
  assert.match(user.content, /与 林晚 的私信（只有林晚和用户知道）/);
  assert.match(user.content, /新室友（用户）：在吗/);
  assert.match(user.content, /林晚记得的事：新室友喜欢猫/);
  assert.match(user.content, /用户刚刚私聊了 林晚/);
  // The stable part carries no time, so the upstream can reuse it between calls.
  assert.doesNotMatch(system.content, /2026-11-17/);
});

test('a new world ticks about a minute after it is made, stores what happened and survives a restart', async () => {
  const { engine, clock, model, dataDir } = await engineAt(at('2026-11-17T10:00:00+08:00'));
  const created = await engine.create(EXAMPLE);
  assert.equal(created.residents.length, 4);
  assert.equal((await engine.messages(created.id, { tail: 10 })).items[0].kind, 'event');
  clock.now += 30_000;
  assert.equal(await engine.runDue(), 0);
  clock.now += 40_000;
  assert.equal(await engine.runDue(), 1);
  assert.equal(model.calls.length, 1);
  assert.match(model.calls[0].ask, /请推进世界：从 2026-11-17 周二 10:00 到 2026-11-17 周二 10:01/);
  const after = await engine.get(created.id);
  assert.equal(after.residents.find((r) => r.name === '周屿').activity, '修 bug');
  assert.equal(after.usage.ticks, 1);
  assert.equal(after.usage.calls, 1);
  assert.equal(after.usage.prompt_tokens, 1000);
  const { items } = await engine.messages(created.id, { since: 1 });
  assert.deepEqual(items.map((item) => [item.kind, item.name, item.text]), [
    ['event', '', '团子打翻了水碗。'], ['message', '苏糖', '今晚吃火锅吗'], ['message', '周屿', '加班。'],
  ]);
  assert.deepEqual(items[0].who, ['r1']);
  await engine.flush();
  const again = createWorldEngine({ dataDir, run: model.run, now: () => clock.now, log: { log() {}, warn() {}, error() {} }, checkEveryMs: 0 });
  const reloaded = await again.messages(created.id, { since: 0 });
  assert.equal(reloaded.items.length, 4);
  assert.equal((await again.get(created.id)).residents.find((r) => r.name === '周屿').location, '公司');
});

test('a world made paused waits for resume before its first tick', async () => {
  const { engine, clock, model } = await engineAt(at('2026-11-17T10:00:00+08:00'));
  const { id, paused, hold } = await engine.create({ ...EXAMPLE, startPaused: true });
  assert.equal(paused, true);
  assert.equal(hold, 'paused');
  await live(engine, clock, 3);
  assert.equal(model.calls.length, 0);
  assert.equal((await engine.get(id)).world_time, at('2026-11-17T10:00:00+08:00'));
  await engine.resume(id);
  await live(engine, clock, 0.5);
  assert.equal(model.calls.length, 1);
  assert.throws(() => validatePack({ ...EXAMPLE, startPaused: 'yes' }), /startPaused/);
});

test('a paused world asks nothing and its clock carries on from the pause', async () => {
  const { engine, clock, model } = await engineAt(at('2026-11-17T10:00:00+08:00'));
  const { id } = await engine.create(EXAMPLE);
  await engine.pause(id);
  const frozen = (await engine.get(id)).world_time;
  await live(engine, clock, 6);
  assert.equal(model.calls.length, 0);
  assert.equal((await engine.get(id)).world_time, frozen);
  await assert.rejects(engine.say(id, { channel: 'group', text: '有人吗' }), (error) => error.status === 409);
  await assert.rejects(engine.tick(id), (error) => error.status === 409);
  await engine.resume(id);
  clock.now += 10 * MINUTE;
  assert.equal((await engine.get(id)).world_time, frozen + 10 * MINUTE);
  await live(engine, clock, 1);
  assert.equal(model.calls.length, 2);
  // The first tick after resuming covers only world time, not the six paused hours.
  assert.match(model.calls[0].ask, /过去了 (30|35) 分钟/);
});

test('a simulated day stays inside quiet hours and the daily limits, and the limits reset the next day', async () => {
  const { engine, clock, model } = await engineAt(at('2026-11-17T09:00:00+08:00'));
  const { id } = await engine.create({ ...EXAMPLE, settings: { tickMinutes: 15, dailyTicks: 20, dailyCalls: 30 } });
  const ticks = () => model.calls.filter((call) => call.ask.includes('请推进世界'));
  // Keep looking, so the pace never slows. 09:00 to 23:55 on the first day:
  for (let hour = 0; hour < 14; hour += 1) { await live(engine, clock, 1); await engine.messages(id, { seen: true }); }
  await live(engine, clock, 55 / 60);
  const first = await engine.get(id);
  assert.equal(first.usage.ticks, 20, 'ticks stop at the daily limit');
  assert.equal(ticks().length, 20);
  assert.ok(first.usage.calls <= 30);
  // Through the night to about 10:00 on the second day.
  await live(engine, clock, 5 / 60);
  for (let hour = 0; hour < 10; hour += 1) { await live(engine, clock, 1); await engine.messages(id, { seen: true }); }
  const second = await engine.get(id);
  assert.equal(second.usage.day, '2026-11-18');
  assert.ok(second.usage.ticks > 0, 'counters start again on the new day');
  const quiet = ticks().filter((call) => /到 2026-11-18 周三 0[1-7]:/.test(call.ask));
  assert.equal(quiet.length, 0, 'nothing happens while everyone sleeps');
  const morning = ticks().filter((call) => /到 2026-11-18 周三 0[89]:/.test(call.ask));
  assert.ok(morning.length > 0, 'the world wakes up again after the quiet hours');
  assert.match(morning[0].ask, /从 2026-11-18 周三 00:\d\d 到 2026-11-18 周三 08:0\d/, 'the first morning tick covers the night');
  assert.equal(second.usage.ticks, ticks().filter((call) => /到 2026-11-18/.test(call.ask)).length);
});

test('the calls limit stops replies too', async () => {
  const { engine, model } = await engineAt(at('2026-11-17T10:00:00+08:00'));
  const { id } = await engine.create({ ...EXAMPLE, settings: { dailyCalls: 1 } });
  await engine.say(id, { channel: 'group', text: '大家好' });
  await assert.rejects(engine.say(id, { channel: 'group', text: '还在吗' }), (error) => error.status === 429);
  assert.equal(model.calls.length, 1);
});

test('a private message gets an answer from that person only; the others cannot reply privately', async () => {
  const { engine } = await engineAt(at('2026-11-17T14:00:00+08:00'));
  const { id } = await engine.create(EXAMPLE);
  const { items } = await engine.say(id, { channel: 'dm:r1', text: '你画的封面好好看' });
  assert.deepEqual(items.map((item) => [item.channel, item.name, item.text]), [
    ['dm:r1', '新室友', '你画的封面好好看'], ['dm:r1', '林晚', '嗯嗯 我在'], ['dm:r1', '林晚', '怎么啦 (｡•ᴗ•｡)'],
  ]);
  assert.equal((await engine.get(id)).residents[0].mood, '有点开心');
  assert.equal((await engine.get(id)).unread, 0);
  await assert.rejects(engine.say(id, { channel: 'dm:r9', text: '?' }), (error) => error.status === 400);
  await assert.rejects(engine.say(id, { channel: 'group', text: '   ' }), (error) => error.status === 400);
});

test('a failed reply keeps the user message for the next tick to answer', async () => {
  const model = fakeModel({ fail: (ask) => ask.includes('用户刚刚') });
  const { engine, clock } = await engineAt(at('2026-11-17T14:00:00+08:00'), { model });
  const { id } = await engine.create(EXAMPLE);
  await assert.rejects(engine.say(id, { channel: 'group', text: '谁拿了我的快递' }), (error) => error.status === 502 && /已保存/.test(error.message));
  const { items } = await engine.messages(id, { tail: 1 });
  assert.equal(items[0].text, '谁拿了我的快递');
  clock.now += 31 * MINUTE;
  await engine.runDue();
  assert.match(model.calls.at(-1).ask, /用户的消息还没有人回/);
});

test('a new world day gets a diary chapter and the residents remember', async () => {
  const { engine, clock, model } = await engineAt(at('2026-11-17T22:00:00+08:00'));
  const { id } = await engine.create(EXAMPLE);
  await live(engine, clock, 12);
  const diaryCalls = model.calls.filter((call) => call.system === DIARY_RULES);
  assert.equal(diaryCalls.length, 1);
  assert.match(diaryCalls[0].ask, /【日期】2026-11-17/);
  const diary = await engine.diary(id);
  assert.equal(diary.entries.length, 1);
  assert.equal(diary.residents[0].notes, '新室友帮忙喂过团子。');
  assert.match(model.calls.at(-1).ask, /【日记】\n2026-11-17：/);
});

test('after the PC was off for days, one tick about two minutes after start sums the gap up', async () => {
  const { engine, clock, model, dataDir } = await engineAt(at('2026-11-17T10:00:00+08:00'));
  const { id } = await engine.create(EXAMPLE);
  clock.now += 2 * MINUTE;
  await engine.runDue();
  await engine.messages(id, { seen: true });
  await engine.flush();
  clock.now = at('2026-11-20T12:00:00+08:00');
  const restarted = createWorldEngine({ dataDir, run: model.run, now: () => clock.now, log: { log() {}, warn() {}, error() {} }, checkEveryMs: 0 });
  await restarted.start();
  assert.equal(await restarted.runDue(), 0);
  clock.now += 2 * MINUTE + 1000;
  assert.equal(await restarted.runDue(), 1);
  const gap = model.calls.filter((call) => call.ask.includes('请推进世界')).at(-1);
  assert.match(gap.ask, /过去了 3 天/);
  assert.match(gap.ask, /只概括最重要的变化/);
});

test('settings and texts can be changed, unknown fields cannot', async () => {
  const { engine } = await engineAt(at('2026-11-17T10:00:00+08:00'));
  const { id } = await engine.create(EXAMPLE);
  const changed = await engine.update(id, { settings: { tickMinutes: 60 }, groupName: '302 大家庭', model: 'some-model' });
  assert.equal(changed.settings.tickMinutes, 60);
  assert.equal(changed.settings.dailyCalls, 80);
  assert.equal(changed.definition.groupName, '302 大家庭');
  assert.equal(changed.model, 'some-model');
  await assert.rejects(engine.update(id, { residents: [] }), /不能修改/);
  await engine.remove(id);
  await assert.rejects(engine.get(id), (error) => error.status === 404);
});

test('the server routes 小世界 through the real pipeline and keeps the token check', async () => {
  const dataDir = await fs.mkdtemp(path.join(os.tmpdir(), 'pocket-world-server-'));
  const bodies = [];
  const server = createServer({
    config: { token: 'test-token', dataDir },
    vault: createVault({ dataDir }),
    upstream: { complete: async ({ body }) => {
      bodies.push(body);
      const content = JSON.stringify({ group: [{ from: '苏糖', text: '收到！' }], residents: [], events: [], dms: [] });
      return { id: 'x', content, reasoning: '', tool_calls: [], finish_reason: 'stop', usage: { prompt_tokens: 10, completion_tokens: 5 } };
    } },
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const base = `http://127.0.0.1:${server.address().port}/v1/pocket/worlds`;
  const call = (url, init = {}) => fetch(url, { ...init, headers: { authorization: 'Bearer test-token', 'content-type': 'application/json', ...(init.headers || {}) } });
  try {
    assert.equal((await fetch(base)).status, 401);
    const created = await (await call(base, { method: 'POST', body: JSON.stringify(EXAMPLE) })).json();
    assert.match(created.id, /^w[0-9a-f]{12}$/);
    assert.equal((await (await call(base)).json()).data.length, 1);
    const said = await (await call(`${base}/${created.id}/messages`, { method: 'POST', body: JSON.stringify({ channel: 'group', text: '大家好' }) })).json();
    assert.deepEqual(said.items.map((item) => item.text), ['大家好', '收到！']);
    assert.equal(bodies[0].model, 'deepseek-v4-1-flash-1017');
    assert.equal(bodies[0].max_tokens, 4000);
    assert.match(bodies[0].messages[0].content, /小世界的导演/);
    const page = await (await call(`${base}/${created.id}/messages?since=0&seen=1`)).json();
    assert.equal(page.items.length, 3);
    assert.equal(page.world.unread, 0);
    assert.equal((await (await call(`${base}/${created.id}/pause`, { method: 'POST' })).json()).paused, true);
    assert.equal((await call(`${base}/${created.id}/tick`, { method: 'POST' })).status, 409);
    assert.equal((await call(`${base}/${created.id}`, { method: 'PATCH', body: JSON.stringify({ settings: { tickMinutes: 1 } }) })).status, 400);
    assert.equal((await call(`${base}/w000000000000`)).status, 404);
    const status = await (await call(base.replace('/worlds', '/status'))).json();
    assert.deepEqual(status.worlds, { count: 1, running: 0 });
    assert.ok(status.features.includes('worlds'));
    assert.equal((await call(`${base}/${created.id}`, { method: 'DELETE' })).status, 200);
  } finally { await new Promise((resolve) => server.close(resolve)); }
});
