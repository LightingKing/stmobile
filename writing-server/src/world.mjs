/**
 * 小世界: a world that keeps living on this PC while the phone is away. A world is a scenario with a
 * clock: its residents (the world's own copies, not the phone's character cards) have a state each,
 * and every so often one model request, the "director", moves the world forward: who went where,
 * what happened, what was said in the group chat and who messaged the user. When the user speaks,
 * the people concerned answer at once. Pausing freezes the world's clock; it carries on from that
 * moment on resume. Daily limits, quiet hours and a slower pace while the user is away keep the
 * spending bounded. The phone only reads the timeline and posts the user's messages.
 *
 * Everything for one world lives in dataDir/worlds/<id>/: world.json (the definition), state.json
 * (clock, resident states, usage), timeline.jsonl (messages and events) and diary.jsonl.
 */
import { randomBytes } from 'node:crypto';
import { appendFile, mkdir, readFile, readdir, rename, rm, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { MODEL_ID } from './config.mjs';

export const WORLD_LIMITS = {
  residents: 8,
  name: 40,
  summary: 500,
  setting: 8000,
  rules: 4000,
  userRole: 2000,
  userName: 20,
  groupName: 30,
  opening: 4000,
  residentName: 30,
  aliases: 6,
  card: 6000,
  stateField: 120,
  notes: 600,
  bookEntries: 80,
  bookName: 60,
  bookContent: 6000,
  keywords: 12,
  keyword: 30,
  userText: 2000,
  groupText: 500,
  dmText: 1000,
  eventText: 300,
  perTick: { group: 8, dms: 4, events: 6 },
  chapter: 1500,
  facts: 30,
  fact: 300,
};

export const WORLD_DEFAULTS = {
  tickMinutes: 30,
  quietStart: '01:00',
  quietEnd: '08:00',
  dailyTicks: 40,
  dailyCalls: 80,
  slowWhenAway: true,
};

const SETTING_RANGES = { tickMinutes: [15, 120], dailyTicks: [0, 200], dailyCalls: [1, 400] };
const HHMM = /^([01]\d|2[0-3]):([0-5]\d)$/;
const WORLD_ID = /^w[0-9a-f]{12}$/;
const RESIDENT_ID = /^r\d{1,4}$/;
const DAY_ID = /^\d{4}-\d{2}-\d{2}$/;
const MINUTE = 60_000;
const HOUR = 60 * MINUTE;
const DAY = 24 * HOUR;
/** What the director sees of the recent past. */
const CONTEXT = { card: 1200, events: 30, eventDays: 2, group: 30, dm: 8, diary: 3, book: 6000, scan: 6000, dayText: 30000 };
const TOKENS = { tick: 8000, reply: 4000, diary: 4000 };

export class WorldError extends Error {
  constructor(message, status = 400, code = 'world_error') {
    super(message);
    this.name = 'WorldError';
    this.status = status;
    this.code = code;
  }
}

// ---------------------------------------------------------------- time

const formatters = new Map();
function partsOf(ms, timeZone) {
  let format = formatters.get(timeZone);
  if (!format) {
    format = new Intl.DateTimeFormat('en-US', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', weekday: 'short', hourCycle: 'h23' });
    formatters.set(timeZone, format);
  }
  const parts = Object.fromEntries(format.formatToParts(new Date(ms)).map((part) => [part.type, part.value]));
  return { year: parts.year, month: parts.month, day: parts.day, hour: Number(parts.hour), minute: Number(parts.minute), weekday: parts.weekday };
}
const WEEKDAYS = { Mon: '周一', Tue: '周二', Wed: '周三', Thu: '周四', Fri: '周五', Sat: '周六', Sun: '周日' };
const pad = (value) => String(value).padStart(2, '0');

/** The calendar day of [ms] in [timeZone], as YYYY-MM-DD. */
export function dayOf(ms, timeZone) { const p = partsOf(ms, timeZone); return `${p.year}-${p.month}-${p.day}`; }
export function minuteOfDay(ms, timeZone) { const p = partsOf(ms, timeZone); return p.hour * 60 + p.minute; }
export function timeLabel(ms, timeZone) { const p = partsOf(ms, timeZone); return `${p.year}-${p.month}-${p.day} ${WEEKDAYS[p.weekday] || ''} ${pad(p.hour)}:${pad(p.minute)}`; }
function shortLabel(ms, timeZone) { const p = partsOf(ms, timeZone); return `${p.month}-${p.day} ${pad(p.hour)}:${pad(p.minute)}`; }
function nextDay(day) { const [y, m, d] = day.split('-').map(Number); const next = new Date(Date.UTC(y, m - 1, d + 1)); return `${next.getUTCFullYear()}-${pad(next.getUTCMonth() + 1)}-${pad(next.getUTCDate())}`; }
function previousDay(day) { const [y, m, d] = day.split('-').map(Number); const prev = new Date(Date.UTC(y, m - 1, d - 1)); return `${prev.getUTCFullYear()}-${pad(prev.getUTCMonth() + 1)}-${pad(prev.getUTCDate())}`; }
const hhmm = (value) => { const match = HHMM.exec(value); return Number(match[1]) * 60 + Number(match[2]); };

/** The world's own time: it runs with real time and stands still while the world is paused. */
export function worldTime(state, now) {
  if (state.paused) return state.pausedWorldAt;
  return state.anchorWorld + (now - state.anchorReal);
}

/** Whether the residents are asleep at [worldMs]; equal start and end means no quiet hours. */
export function inQuiet(worldMs, settings, timeZone) {
  const start = hhmm(settings.quietStart);
  const end = hhmm(settings.quietEnd);
  if (start === end) return false;
  const minute = minuteOfDay(worldMs, timeZone);
  return start < end ? minute >= start && minute < end : minute >= start || minute < end;
}

/** Minutes of world time from [worldMs] until the quiet hours end (0 when not quiet). */
function minutesUntilAwake(worldMs, settings, timeZone) {
  if (!inQuiet(worldMs, settings, timeZone)) return 0;
  const minute = minuteOfDay(worldMs, timeZone);
  const end = hhmm(settings.quietEnd);
  return (end - minute + 1440) % 1440;
}

/** The pace: the set interval, doubled after a day without the user looking and doubled again after two. */
export function intervalMs(settings, state, now) {
  const base = settings.tickMinutes * MINUTE;
  if (!settings.slowWhenAway) return base;
  const away = now - (state.lastSeenAt ?? state.createdAt);
  return away > 2 * DAY ? base * 4 : away > DAY ? base * 2 : base;
}

function elapsedText(ms) {
  const minutes = Math.max(0, Math.round(ms / MINUTE));
  if (minutes < 60) return `${minutes} 分钟`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return minutes % 60 ? `${hours} 小时 ${minutes % 60} 分钟` : `${hours} 小时`;
  return hours % 24 ? `${Math.floor(hours / 24)} 天 ${hours % 24} 小时` : `${Math.floor(hours / 24)} 天`;
}

// ---------------------------------------------------------------- validation

const text = (value) => (typeof value === 'string' ? value : value == null ? '' : String(value)).replace(/\r\n?/g, '\n').trim();

function field(source, key, limit, label, { required = false } = {}) {
  const value = text(source?.[key]);
  if (required && !value) throw new WorldError(`缺少${label}`, 400, 'invalid_world');
  if (value.length > limit) throw new WorldError(`${label}超过 ${limit} 字`, 400, 'invalid_world');
  return value;
}

function validateModel(value) {
  const model = text(value) || 'pocket-direct';
  if (model === 'pocket-writer') throw new WorldError('小世界不能用“默认模型 + 审校”，请选默认模型或具体模型', 400, 'invalid_world');
  if (!MODEL_ID.test(model)) throw new WorldError('模型 ID 无效', 400, 'invalid_world');
  return model;
}

/** Settings from a patch, checked against their ranges; unknown keys are refused. */
export function validateSettings(patch, base = WORLD_DEFAULTS) {
  if (patch == null) return { ...base };
  if (typeof patch !== 'object' || Array.isArray(patch)) throw new WorldError('settings 必须是对象', 400, 'invalid_world');
  const result = { ...base };
  for (const [key, value] of Object.entries(patch)) {
    if (key in SETTING_RANGES) {
      const [min, max] = SETTING_RANGES[key];
      if (!Number.isInteger(value) || value < min || value > max) throw new WorldError(`${key} 应为 ${min}–${max} 的整数`, 400, 'invalid_world');
      result[key] = value;
    } else if (key === 'quietStart' || key === 'quietEnd') {
      if (typeof value !== 'string' || !HHMM.test(value)) throw new WorldError(`${key} 应为 HH:MM`, 400, 'invalid_world');
      result[key] = value;
    } else if (key === 'slowWhenAway') {
      if (typeof value !== 'boolean') throw new WorldError('slowWhenAway 应为 true 或 false', 400, 'invalid_world');
      result[key] = value;
    } else throw new WorldError(`未知设置：${key}`, 400, 'invalid_world');
  }
  return result;
}

function validateBook(entries) {
  if (entries == null) return [];
  if (!Array.isArray(entries)) throw new WorldError('worldBook 必须是数组', 400, 'invalid_world');
  if (entries.length > WORLD_LIMITS.bookEntries) throw new WorldError(`世界书最多 ${WORLD_LIMITS.bookEntries} 条`, 400, 'invalid_world');
  return entries.map((entry) => {
    const keywords = Array.isArray(entry?.keywords) ? entry.keywords.map(text).filter(Boolean) : [];
    if (keywords.length > WORLD_LIMITS.keywords || keywords.some((word) => word.length > WORLD_LIMITS.keyword)) throw new WorldError(`世界书关键词每条最多 ${WORLD_LIMITS.keywords} 个、每个 ${WORLD_LIMITS.keyword} 字`, 400, 'invalid_world');
    return {
      name: field(entry, 'name', WORLD_LIMITS.bookName, '世界书条目名', { required: true }),
      content: field(entry, 'content', WORLD_LIMITS.bookContent, '世界书内容', { required: true }),
      keywords, always: entry?.always === true,
    };
  });
}

/** One resident from the phone or the dashboard; [seen] holds names and aliases already taken. */
function validateResident(source, id, seen) {
  if (!source || typeof source !== 'object' || Array.isArray(source)) throw new WorldError('居民内容必须是对象', 400, 'invalid_world');
  const name = field(source, 'name', WORLD_LIMITS.residentName, '居民名字', { required: true });
  const aliases = Array.isArray(source.aliases) ? [...new Set(source.aliases.map(text).filter((alias) => alias && alias !== name))] : [];
  if (aliases.length > WORLD_LIMITS.aliases || aliases.some((alias) => alias.length > WORLD_LIMITS.residentName)) throw new WorldError(`别名最多 ${WORLD_LIMITS.aliases} 个，每个 ${WORLD_LIMITS.residentName} 字以内`, 400, 'invalid_world');
  for (const key of [name, ...aliases]) {
    if (seen.has(key)) throw new WorldError(`居民名字或别名重复：${key}`, 400, 'invalid_world');
    seen.add(key);
  }
  return {
    id, name, aliases,
    card: field(source, 'card', WORLD_LIMITS.card, `${name}的设定`, { required: true }),
    start: {
      location: field(source, 'location', WORLD_LIMITS.stateField, '位置'),
      activity: field(source, 'activity', WORLD_LIMITS.stateField, '活动'),
      mood: field(source, 'mood', WORLD_LIMITS.stateField, '心情'),
      plan: field(source, 'plan', WORLD_LIMITS.stateField, '打算'),
      notes: field(source, 'notes', WORLD_LIMITS.notes, '记忆'),
    },
  };
}

function validateResidents(list) {
  if (!Array.isArray(list) || list.length === 0) throw new WorldError('世界里至少要有一位居民', 400, 'invalid_world');
  if (list.length > WORLD_LIMITS.residents) throw new WorldError(`一个世界最多 ${WORLD_LIMITS.residents} 位居民`, 400, 'invalid_world');
  const seen = new Set();
  return list.map((source, index) => validateResident(source, `r${index + 1}`, seen));
}

/** Things the user wants every request to remember (要记住的事), one short line each. */
export function validateFacts(list) {
  if (list == null) return [];
  if (!Array.isArray(list)) throw new WorldError('facts 必须是数组', 400, 'invalid_world');
  const facts = list.map(text).filter(Boolean);
  if (facts.length > WORLD_LIMITS.facts) throw new WorldError(`要记住的事最多 ${WORLD_LIMITS.facts} 条`, 400, 'invalid_world');
  if (facts.some((fact) => fact.length > WORLD_LIMITS.fact)) throw new WorldError(`要记住的事每条最多 ${WORLD_LIMITS.fact} 字`, 400, 'invalid_world');
  return facts;
}

/** A world definition from what the phone (or the dashboard) sends; throws WorldError on anything off. */
export function validatePack(pack) {
  if (!pack || typeof pack !== 'object' || Array.isArray(pack)) throw new WorldError('世界内容必须是 JSON 对象', 400, 'invalid_world');
  const name = field(pack, 'name', WORLD_LIMITS.name, '世界名', { required: true });
  const startAt = pack.startAt == null || pack.startAt === '' ? null : Date.parse(pack.startAt);
  if (Number.isNaN(startAt)) throw new WorldError('startAt 不是有效时间', 400, 'invalid_world');
  if (pack.startPaused !== undefined && typeof pack.startPaused !== 'boolean') throw new WorldError('startPaused 应为 true 或 false', 400, 'invalid_world');
  return {
    name,
    summary: field(pack, 'summary', WORLD_LIMITS.summary, '简介'),
    setting: field(pack, 'setting', WORLD_LIMITS.setting, '设定', { required: true }),
    rules: field(pack, 'rules', WORLD_LIMITS.rules, '叙事规则'),
    userRole: field(pack, 'userRole', WORLD_LIMITS.userRole, '你的身份'),
    userName: field(pack, 'userName', WORLD_LIMITS.userName, '你的称呼') || '用户',
    groupName: field(pack, 'groupName', WORLD_LIMITS.groupName, '群名') || `${name}群`.slice(0, WORLD_LIMITS.groupName),
    opening: field(pack, 'opening', WORLD_LIMITS.opening, '开场'),
    startAt,
    startPaused: pack.startPaused === true,
    worldBook: validateBook(pack.worldBook),
    facts: validateFacts(pack.facts),
    residents: validateResidents(pack.residents),
    model: validateModel(pack.model),
    settings: validateSettings(pack.settings),
  };
}

// ---------------------------------------------------------------- the director

export const DIRECTOR_RULES = `你是一个持续运行的小世界的导演。世界里的居民各自过自己的日子，不围着用户转，也不等用户开口。每次调用时，你根据世界设定、居民档案、当前状态和最近发生的事，决定这一段时间里世界发生了什么。

怎么写：
- 居民按自己的性格、作息、工作和关系行动。大多数时候大家在忙自己的事；群里有话才说，没话就不说。深夜大家多半在睡觉。
- 群聊和私信要像真实的手机聊天：短句、口语，可以分几条发，可以用表情符号或颜文字，符合各自的说话习惯；不写成小说旁白，不加动作描写，不加引号。
- 私信只在有理由时发：想到用户、有事要说、分享见闻、回应用户。不要每次都发。
- “事件”是这段时间里发生的、值得记下的事，第三人称一两句话，比如谁去了哪、谁和谁吵了一架、谁收到了快递。没有就留空。
- 用户在这个世界里的身份见下方。用户的言行只由用户自己决定：不要替用户说话、行动或表态，也不要假装用户回复了。
- 谁知道什么：群里的话群里所有人都看得到；用户和某位居民的私信只有这两个人知道；事件只有在场的人知道，除非有人说出去。居民不能说出自己不该知道的事。
- 前后一致：已经发生的事不改口，状态变化要有原因。
- 恋爱和亲密内容只发生在成年人之间。以真实人物为原型的居民，不写性内容，不编造其隐私。

只输出一个 JSON 对象，不要输出别的文字：
{"residents":[{"name":"居民名字","location":"现在在哪","activity":"在做什么","mood":"心情","plan":"接下来的打算"}],"events":[{"text":"发生的事","who":["在场的居民名字"]}],"group":[{"from":"居民名字","text":"群里的一条消息"}],"dms":[{"from":"居民名字","text":"发给用户的一条私信"}]}
- residents 只列状态有变化的人；events、group、dms 都可以是空数组。
- group 和 dms 按发送先后排列，一项是一条消息；同一个人连发几条就写几项。
- 名字必须用居民列表里的名字。`;

export const DIARY_RULES = `你在为一个持续运行的小世界整理记录。根据下面这一天的全部事件和聊天，写两样东西：
1. chapter：这一天的日记式记录，第三人称，按时间顺序，只写实际发生的事，300 到 600 字。
2. notes：每位居民需要长期记住的事——对用户的印象和关系、答应过谁的事、和别人关系的变化、重要经历。在旧内容的基础上更新，每人不超过 300 字；没有变化就原样返回旧内容。
私信里的内容只写进那位居民自己的记忆，不要写成别人也知道。
只输出 JSON：{"chapter":"……","notes":[{"name":"居民名字","text":"……"}]}`;

function clip(value, limit) { const s = text(value); return s.length > limit ? `${s.slice(0, limit)}…` : s; }

function residentByName(def, name) {
  const wanted = text(name).replace(/[（(].*?[）)]$/, '').replace(/^[【[]|[】\]]$/g, '').trim();
  return def.residents.find((r) => r.name === wanted || r.aliases.includes(wanted)) || null;
}

function speakerName(def, item) {
  if (item.from === 'user') return `${def.userName}（用户）`;
  return def.residents.find((r) => r.id === item.from)?.name || item.name || '某人';
}

function itemLine(def, item, timeZone) {
  const when = shortLabel(item.worldAt, timeZone);
  if (item.kind === 'event') {
    const who = Array.isArray(item.who) && item.who.length ? `（在场：${item.who.map((id) => def.residents.find((r) => r.id === id)?.name || id).join('、')}）` : '';
    return `[${when}] ${item.text}${who}`;
  }
  return `[${when}] ${speakerName(def, item)}：${item.text}`;
}

/** The part of the prompt that stays the same between calls, so the upstream can reuse its cache. */
function stablePrompt(def) {
  const residents = def.residents.map((r) => `### ${r.name}${r.aliases.length ? `（也叫：${r.aliases.join('、')}）` : ''}\n${clip(r.card, CONTEXT.card)}`).join('\n\n');
  return [
    DIRECTOR_RULES,
    `【世界】${def.name}${def.summary ? `\n${def.summary}` : ''}`,
    `【设定】\n${def.setting}`,
    def.rules ? `【叙事规则】\n${def.rules}` : '',
    def.facts?.length ? `【要记住的事】（用户写下的，始终成立）\n${def.facts.map((fact) => `- ${fact}`).join('\n')}` : '',
    `【用户的身份】\n居民们叫用户「${def.userName}」。${def.userRole || '用户是这个世界里的一员。'}`,
    `【群聊】大家（包括用户）都在群「${def.groupName}」里。`,
    `【居民】\n${residents}`,
  ].filter(Boolean).join('\n\n');
}

function stateLines(def, state) {
  return def.residents.map((r) => {
    const s = state.residents[r.id] || {};
    const parts = [s.location && `在 ${s.location}`, s.activity && `在做 ${s.activity}`, s.mood && `心情 ${s.mood}`, s.plan && `打算 ${s.plan}`].filter(Boolean).join('；') || '（还没有记录）';
    return `- ${r.name}：${parts}${s.notes ? `\n  ${r.name}记得的事：${s.notes}` : ''}`;
  }).join('\n');
}

function matchedBook(def, scan) {
  const chosen = [];
  let used = 0;
  const entries = [...def.worldBook.filter((e) => e.always), ...def.worldBook.filter((e) => !e.always && e.keywords.some((word) => scan.includes(word)))];
  for (const entry of entries) {
    const block = `## ${entry.name}\n${entry.content}`;
    if (used + block.length > CONTEXT.book) continue;
    chosen.push(block);
    used += block.length;
  }
  return chosen.join('\n\n');
}

/**
 * The messages for one director request. [mode] is 'tick' (move the world from fromWorld to
 * worldNow), 'group' (the user just spoke in the group) or 'dm' with [target] (the user just wrote
 * to one resident privately).
 */
export function directorMessages({ def, state, timeline, diary, timeZone }, { mode, worldNow, fromWorld = worldNow, target = null }) {
  const since = worldNow - CONTEXT.eventDays * DAY;
  const events = timeline.filter((item) => item.kind === 'event' && item.worldAt >= since).slice(-CONTEXT.events);
  const group = timeline.filter((item) => item.kind === 'message' && item.channel === 'group').slice(-CONTEXT.group);
  const dmBlocks = def.residents.map((r) => {
    const lines = timeline.filter((item) => item.kind === 'message' && item.channel === `dm:${r.id}`).slice(-CONTEXT.dm);
    return lines.length ? `与 ${r.name} 的私信（只有${r.name}和用户知道）：\n${lines.map((item) => itemLine(def, item, timeZone)).join('\n')}` : '';
  }).filter(Boolean);
  const scan = [...events, ...group].map((item) => item.text).join('\n').slice(-CONTEXT.scan) + stateLines(def, state);
  const book = matchedBook(def, scan);
  const sections = [
    `【现在】世界时间 ${timeLabel(worldNow, timeZone)}`,
    `【居民现在的状态】\n${stateLines(def, state)}`,
    diary.length ? `【日记】\n${diary.slice(-CONTEXT.diary).map((entry) => `${entry.day}：${entry.text}`).join('\n\n')}` : '',
    `【最近发生的事】\n${events.length ? events.map((item) => itemLine(def, item, timeZone)).join('\n') : '（暂无）'}`,
    `【群「${def.groupName}」最近的消息】\n${group.length ? group.map((item) => itemLine(def, item, timeZone)).join('\n') : '（暂无）'}`,
    dmBlocks.length ? `【私信】\n${dmBlocks.join('\n\n')}` : '',
    book ? `【相关的世界书】\n${book}` : '',
  ].filter(Boolean);
  let ask;
  if (mode === 'tick') {
    const elapsed = worldNow - fromWorld;
    ask = `请推进世界：从 ${timeLabel(fromWorld, timeZone)} 到 ${timeLabel(worldNow, timeZone)}，过去了 ${elapsedText(elapsed)}。`;
    if (elapsed > DAY) ask += `这段时间比较长，只概括最重要的变化，群聊和私信只写最近的几条。`;
    else ask += '这段时间里群聊一般 0 到 6 条，私信一般 0 到 1 条，按实际情况来，没有就留空。';
    const lastUser = [...timeline].reverse().find((item) => item.kind === 'message');
    if (lastUser?.from === 'user') ask += '用户的消息还没有人回，先自然地回应。';
  } else if (mode === 'group') {
    ask = `用户刚刚在群里说了话（见群消息最后一条）。请让相关的居民自然地回应：可能一两个人接话，也可能没人回，比如都在忙或在睡觉。此刻是 ${timeLabel(worldNow, timeZone)}，不推进时间；residents 只写因此产生的变化，dms 一般为空。`;
  } else {
    ask = `用户刚刚私聊了 ${target.name}（见和${target.name}的私信最后一条）。请写 ${target.name} 的回复，放在 dms 里，至少一条，可以连发几条；如果${target.name}在睡觉或在忙，可以回得晚、回得短，但要回。此刻是 ${timeLabel(worldNow, timeZone)}，不推进时间。别人看不到这段私聊，group 一般为空。`;
  }
  return [
    { role: 'system', content: stablePrompt(def) },
    { role: 'user', content: `${sections.join('\n\n')}\n\n${ask}` },
  ];
}

/** The first JSON object in a reply, tolerating code fences, inline thinking and text around it. */
export function extractJson(content) {
  let raw = String(content ?? '').replace(/<think>[\s\S]*?<\/think>/gi, '').trim();
  raw = raw.replace(/^```(?:json)?\s*/i, '').replace(/\s*```\s*$/, '');
  try { return JSON.parse(raw); } catch { /* look for the object inside */ }
  const start = raw.indexOf('{');
  const end = raw.lastIndexOf('}');
  if (start >= 0 && end > start) { try { return JSON.parse(raw.slice(start, end + 1)); } catch { /* not JSON */ } }
  return null;
}

/**
 * What the director decided, kept to residents of this world and within the per-call limits.
 * Returns null when the reply is not the JSON asked for.
 */
export function parseDirector(content, def) {
  const json = extractJson(content);
  if (!json || typeof json !== 'object' || Array.isArray(json)) return null;
  const list = (value) => (Array.isArray(value) ? value : []);
  const residents = [];
  for (const entry of list(json.residents)) {
    const resident = residentByName(def, entry?.name);
    if (!resident) continue;
    const change = {};
    for (const key of ['location', 'activity', 'mood', 'plan']) { const value = clip(entry?.[key], WORLD_LIMITS.stateField); if (value) change[key] = value; }
    if (Object.keys(change).length) residents.push({ id: resident.id, ...change });
  }
  const events = list(json.events).map((entry) => {
    const value = clip(typeof entry === 'string' ? entry : entry?.text, WORLD_LIMITS.eventText);
    const who = list(entry?.who).map((name) => residentByName(def, name)?.id).filter(Boolean);
    return value ? { text: value, who: [...new Set(who)] } : null;
  }).filter(Boolean).slice(0, WORLD_LIMITS.perTick.events);
  const lines = (value, limit, max) => list(value).map((entry) => {
    const resident = residentByName(def, entry?.from);
    const body = clip(entry?.text, limit);
    return resident && body ? { from: resident.id, text: body } : null;
  }).filter(Boolean).slice(0, max);
  return {
    residents, events,
    group: lines(json.group, WORLD_LIMITS.groupText, WORLD_LIMITS.perTick.group),
    dms: lines(json.dms, WORLD_LIMITS.dmText, WORLD_LIMITS.perTick.dms),
  };
}

export function diaryMessages({ def, state, timeZone }, day, items) {
  let record = items.map((item) => itemLine(def, item, timeZone).replace(/^\[\d\d-\d\d /, '[')).join('\n');
  if (record.length > CONTEXT.dayText) record = `……（更早的部分略）\n${record.slice(-CONTEXT.dayText)}`;
  const notes = def.residents.map((r) => `- ${r.name}：${state.residents[r.id]?.notes || '（还没有）'}`).join('\n');
  return [
    { role: 'system', content: DIARY_RULES },
    { role: 'user', content: `【世界】${def.name}\n【居民】${def.residents.map((r) => r.name).join('、')}；用户叫「${def.userName}」\n【日期】${day}\n\n【居民旧的记忆】\n${notes}\n\n【这一天的记录】\n${record}` },
  ];
}

export function parseDiary(content, def) {
  const json = extractJson(content);
  if (!json || typeof json !== 'object') return null;
  const chapter = clip(json.chapter, WORLD_LIMITS.chapter);
  if (!chapter) return null;
  const notes = {};
  for (const entry of Array.isArray(json.notes) ? json.notes : []) {
    const resident = residentByName(def, entry?.name);
    const value = clip(entry?.text, WORLD_LIMITS.notes);
    if (resident && value) notes[resident.id] = value;
  }
  return { chapter, notes };
}

// ---------------------------------------------------------------- storage

async function readJson(file) { return JSON.parse(await readFile(file, 'utf8')); }

async function writeJson(file, value) {
  const temp = `${file}.${process.pid}.tmp`;
  await writeFile(temp, JSON.stringify(value, null, 2), { mode: 0o600 });
  try { await rename(temp, file); }
  catch (error) {
    // A virus scanner holding the old file for a moment is the usual cause on Windows.
    if (error.code !== 'EPERM' && error.code !== 'EBUSY') throw error;
    await new Promise((resolve) => setTimeout(resolve, 200));
    await rename(temp, file);
  }
}

async function readLines(file) {
  let raw = '';
  try { raw = await readFile(file, 'utf8'); } catch (error) { if (error.code === 'ENOENT') return []; throw error; }
  const items = [];
  for (const line of raw.split('\n')) {
    if (!line.trim()) continue;
    try { items.push(JSON.parse(line)); } catch { /* a line cut by a crash is skipped */ }
  }
  return items;
}

// ---------------------------------------------------------------- the engine

/**
 * [run]({ worldId, model, messages, maxTokens, temperature, signal }) makes one model request and
 * returns { content, usage }; the server passes its own pipeline, tests a fake.
 */
export function createWorldEngine({ dataDir, run, now = Date.now, timeZone = 'Asia/Shanghai', log = console, checkEveryMs = 30_000 }) {
  const root = path.join(dataDir, 'worlds');
  const worlds = new Map();
  const inflight = new Set();
  let loading = null;
  let timer = null;
  let sweeping = false;

  function load() {
    if (!loading) loading = (async () => {
      await mkdir(root, { recursive: true, mode: 0o700 });
      for (const entry of await readdir(root, { withFileTypes: true })) {
        if (!entry.isDirectory() || !WORLD_ID.test(entry.name)) continue;
        const dir = path.join(root, entry.name);
        try {
          const def = await readJson(path.join(dir, 'world.json'));
          const state = await readJson(path.join(dir, 'state.json'));
          worlds.set(def.id, { def, state, dir, timeline: await readLines(path.join(dir, 'timeline.jsonl')), diary: await readLines(path.join(dir, 'diary.jsonl')), queue: Promise.resolve(), writes: Promise.resolve(), busy: 0 });
        } catch (error) { log.warn?.(`小世界 ${entry.name} 读不出来，已跳过：${error.message}`); }
      }
    })();
    return loading;
  }

  async function need(id) {
    await load();
    const world = WORLD_ID.test(String(id)) ? worlds.get(id) : null;
    if (!world) throw new WorldError('这个世界不存在', 404, 'world_not_found');
    return world;
  }

  /** Writes for one world happen in order; each serialises the state as it is when its turn comes. */
  function persist(world, task) {
    const write = world.writes.then(task);
    world.writes = write.catch((error) => log.error?.(`小世界 ${world.def.id} 保存失败：${error.message}`));
    return write;
  }
  const saveState = (world) => persist(world, () => writeJson(path.join(world.dir, 'state.json'), world.state));
  const saveDef = (world) => persist(world, () => writeJson(path.join(world.dir, 'world.json'), world.def));
  const saveDiary = (world) => persist(world, () => writeFile(path.join(world.dir, 'diary.jsonl'), world.diary.map((entry) => `${JSON.stringify(entry)}\n`).join(''), { mode: 0o600 }));

  /** The next resident id, never one an earlier resident had, so old private chats stay theirs. */
  function nextResidentId(world) {
    const used = [...world.def.residents.map((r) => r.id), ...world.timeline.map((item) => item.from), ...world.timeline.map((item) => String(item.channel || '').replace(/^dm:/, '')), ...(world.def.retiredResidents || [])];
    const top = used.filter((id) => RESIDENT_ID.test(String(id))).reduce((max, id) => Math.max(max, Number(String(id).slice(1))), 0);
    return `r${top + 1}`;
  }

  function needResident(world, rid) {
    const resident = RESIDENT_ID.test(String(rid)) ? world.def.residents.find((r) => r.id === rid) : null;
    if (!resident) throw new WorldError('这位居民不在这个世界里', 404, 'resident_not_found');
    return resident;
  }

  function takenNames(world, except = null) {
    return new Set(world.def.residents.filter((r) => r.id !== except).flatMap((r) => [r.name, ...r.aliases]));
  }

  /** One request or one tick at a time per world; the user's message waits for a running tick. */
  function locked(world, task) {
    const result = world.queue.then(task, task);
    world.queue = result.catch(() => {});
    return result;
  }

  function rollDay(world, at) {
    const day = dayOf(at, timeZone);
    if (world.state.usage?.day !== day) world.state.usage = { day, ticks: 0, calls: 0, prompt_tokens: 0, completion_tokens: 0 };
  }

  function append(world, items) {
    const stamped = items.map((item) => ({ seq: ++world.state.seq, ...item }));
    world.timeline.push(...stamped);
    persist(world, () => appendFile(path.join(world.dir, 'timeline.jsonl'), stamped.map((item) => `${JSON.stringify(item)}\n`).join(''), { mode: 0o600 }));
    return stamped;
  }

  async function callModel(world, messages, maxTokens) {
    const controller = new AbortController();
    inflight.add(controller);
    world.busy += 1;
    const at = now();
    rollDay(world, at);
    world.state.usage.calls += 1;
    world.state.totals.calls += 1;
    try {
      const result = await run({ worldId: world.def.id, model: world.def.model, messages, maxTokens, temperature: 0.8, signal: controller.signal });
      for (const key of ['prompt_tokens', 'completion_tokens']) {
        const value = Number(result?.usage?.[key]) || 0;
        world.state.usage[key] += value;
        world.state.totals[key] += value;
      }
      return String(result?.content ?? '');
    } finally {
      world.busy -= 1;
      inflight.delete(controller);
    }
  }

  /** Stores what the director decided; messages of a tick come as a burst ending at the tick's moment. */
  function applyDecision(world, decision, { worldNow, fromWorld = worldNow, burst = true, tick }) {
    const at = now();
    for (const change of decision.residents) {
      const { id, ...fields } = change;
      world.state.residents[id] = { ...(world.state.residents[id] || {}), ...fields };
    }
    const span = Math.max(0, worldNow - fromWorld);
    const events = decision.events.map((event, index) => ({
      kind: 'event', at, worldAt: Math.round(fromWorld + span * ((index + 1) / (decision.events.length + 1))), text: event.text, who: event.who, tick,
    }));
    const lines = [...decision.group.map((line) => ({ ...line, channel: 'group' })), ...decision.dms.map((line) => ({ ...line, channel: `dm:${line.from}` }))];
    const gap = burst ? Math.min(MINUTE, span / Math.max(1, lines.length + 1)) : 0;
    const messages = lines.map((line, index) => ({
      kind: 'message', at, worldAt: Math.round(worldNow - gap * (lines.length - 1 - index)), channel: line.channel, from: line.from,
      name: world.def.residents.find((r) => r.id === line.from)?.name || '', text: line.text, tick,
    }));
    return append(world, [...events, ...messages]);
  }

  function holdOf(world, at) {
    rollDay(world, at);
    const { settings } = world.def;
    if (world.state.paused) return 'paused';
    if (world.state.usage.calls >= settings.dailyCalls) return 'calls';
    if (world.state.usage.ticks >= settings.dailyTicks) return 'ticks';
    if (inQuiet(worldTime(world.state, at), settings, timeZone)) return 'quiet';
    return null;
  }

  function isDue(world, at) {
    return !world.busy && !holdOf(world, at) && at - world.state.dueFrom >= intervalMs(world.def.settings, world.state, at);
  }

  /** The next diary chapter, at most one request per tick; days with nothing in them are skipped. */
  async function writeDiary(world, worldNow) {
    const today = dayOf(worldNow, timeZone);
    for (let guard = 0; guard < 400; guard += 1) {
      const day = nextDay(world.state.diaryDay);
      if (day >= today) return;
      const items = world.timeline.filter((item) => dayOf(item.worldAt, timeZone) === day);
      if (!items.length) { world.state.diaryDay = day; continue; }
      if (world.state.usage.calls >= world.def.settings.dailyCalls) return;
      let content;
      try { content = await callModel(world, diaryMessages({ def: world.def, state: world.state, timeZone }, day, items), TOKENS.diary); }
      catch (error) { if (error?.name !== 'AbortError') log.warn?.(`小世界 ${world.def.id} 写 ${day} 的日记失败：${error.message}`); return; }
      const parsed = parseDiary(content, world.def);
      if (!parsed) { log.warn?.(`小世界 ${world.def.id} ${day} 的日记不是有效 JSON`); return; }
      for (const [id, notes] of Object.entries(parsed.notes)) world.state.residents[id] = { ...(world.state.residents[id] || {}), notes };
      const entry = { day, text: parsed.chapter, at: now() };
      world.diary.push(entry);
      persist(world, () => appendFile(path.join(world.dir, 'diary.jsonl'), `${JSON.stringify(entry)}\n`, { mode: 0o600 }));
      world.state.diaryDay = day;
      return;
    }
  }

  function snapshot(world) {
    return { def: world.def, state: world.state, timeline: world.timeline, diary: world.diary, timeZone };
  }

  /** Moves the world forward once. Scheduled ticks re-check that they are still due; manual ones only need budget. */
  function tick(id, { manual = false } = {}) {
    return need(id).then((world) => locked(world, async () => {
      const at = now();
      const hold = holdOf(world, at);
      if (manual) {
        if (hold === 'paused') throw new WorldError('世界已暂停，恢复后才能推进', 409, 'world_paused');
        if (hold === 'calls') throw new WorldError('今天的请求次数已经用完', 429, 'world_budget');
      } else if (!isDue(world, at)) return { items: [], skipped: true };
      const worldNow = worldTime(world.state, at);
      const fromWorld = Math.min(world.state.lastTickWorldAt ?? world.state.createdWorldAt, worldNow);
      const tickId = `t${world.state.totals.ticks + 1}`;
      world.state.usage.ticks += 1;
      world.state.totals.ticks += 1;
      world.state.dueFrom = at;
      let content;
      try {
        content = await callModel(world, directorMessages(snapshot(world), { mode: 'tick', worldNow, fromWorld }), TOKENS.tick);
      } catch (error) {
        world.state.lastError = { at, message: error?.message || '请求失败' };
        await saveState(world);
        throw error;
      }
      const decision = parseDirector(content, world.def);
      if (!decision) {
        world.state.lastError = { at, message: '导演的回复不是要求的 JSON，这次推进作废' };
        await saveState(world);
        return { items: [], error: world.state.lastError.message };
      }
      const items = applyDecision(world, decision, { worldNow, fromWorld, tick: tickId });
      world.state.lastTickAt = at;
      world.state.lastTickWorldAt = worldNow;
      world.state.lastError = null;
      await writeDiary(world, worldNow);
      await saveState(world);
      return { items };
    }));
  }

  function publicItem(world, item) {
    const name = item.kind === 'event' ? '' : item.from === 'user' ? world.def.userName : item.name || speakerName(world.def, item);
    return { ...item, name, time: shortLabel(item.worldAt, timeZone) };
  }

  function view(world) {
    const at = now();
    const hold = holdOf(world, at);
    const worldNow = worldTime(world.state, at);
    const interval = intervalMs(world.def.settings, world.state, at);
    let nextTickAt = null;
    if (!hold) nextTickAt = Math.max(at, world.state.dueFrom + interval);
    else if (hold === 'quiet') nextTickAt = at + minutesUntilAwake(worldNow, world.def.settings, timeZone) * MINUTE;
    const unread = world.timeline.filter((item) => item.seq > (world.state.lastSeenSeq || 0) && item.kind === 'message' && item.from !== 'user').length;
    const last = [...world.timeline].reverse().find((item) => item.kind === 'message');
    return {
      id: world.def.id, name: world.def.name, summary: world.def.summary,
      residents: world.def.residents.map((r) => ({ id: r.id, name: r.name, ...(world.state.residents[r.id] || {}) })),
      paused: world.state.paused === true, hold, busy: world.busy > 0,
      world_time: worldNow, world_time_label: timeLabel(worldNow, timeZone),
      next_tick_at: nextTickAt, interval_minutes: Math.round(interval / MINUTE),
      usage: world.state.usage, totals: world.state.totals, settings: world.def.settings, model: world.def.model,
      seq: world.state.seq, unread, last_message: last ? publicItem(world, last) : null,
      last_error: world.state.lastError || null, created_at: world.state.createdAt,
    };
  }

  return {
    async list() { await load(); return [...worlds.values()].map(view).sort((a, b) => (b.last_message?.at || b.created_at) - (a.last_message?.at || a.created_at)); },

    async get(id) {
      const world = await need(id);
      const { residents, ...rest } = world.def;
      return { ...view(world), definition: { ...rest, residents: residents.map(({ start, ...resident }) => resident) } };
    },

    async create(pack) {
      await load();
      const def = validatePack(pack);
      const at = now();
      const id = `w${randomBytes(6).toString('hex')}`;
      const createdWorldAt = def.startAt ?? at;
      const { startAt, startPaused, opening, ...stored } = def;
      const world = {
        def: { id, ...stored, opening, createdAt: at },
        state: {
          // A world made paused costs nothing until someone presses resume.
          version: 1, paused: startPaused, pausedWorldAt: startPaused ? createdWorldAt : null, anchorReal: at, anchorWorld: createdWorldAt, createdAt: at, createdWorldAt,
          seq: 0, residents: Object.fromEntries(def.residents.map((r) => [r.id, Object.fromEntries(Object.entries(r.start).filter(([, value]) => value))])),
          // The first tick comes about a minute after the world is made.
          dueFrom: at - def.settings.tickMinutes * MINUTE + MINUTE, lastTickAt: null, lastTickWorldAt: null,
          lastSeenAt: at, lastSeenSeq: 0, diaryDay: previousDay(dayOf(createdWorldAt, timeZone)),
          usage: { day: dayOf(at, timeZone), ticks: 0, calls: 0, prompt_tokens: 0, completion_tokens: 0 },
          totals: { ticks: 0, calls: 0, prompt_tokens: 0, completion_tokens: 0 }, lastError: null,
        },
        dir: path.join(root, id), timeline: [], diary: [], queue: Promise.resolve(), writes: Promise.resolve(), busy: 0,
      };
      await mkdir(world.dir, { recursive: true, mode: 0o700 });
      worlds.set(id, world);
      if (opening) world.state.lastSeenSeq = append(world, [{ kind: 'event', at, worldAt: createdWorldAt, text: opening, who: [], from: 'narrator' }]).at(-1).seq;
      await saveDef(world);
      await saveState(world);
      log.log?.(`小世界已创建：${def.name}（${def.residents.length} 位居民）`);
      return this.get(id);
    },

    async update(id, patch) {
      const world = await need(id);
      if (!patch || typeof patch !== 'object' || Array.isArray(patch)) throw new WorldError('修改内容必须是对象', 400, 'invalid_world');
      return locked(world, async () => {
        const next = { ...world.def };
        const texts = { name: [WORLD_LIMITS.name, '世界名'], summary: [WORLD_LIMITS.summary, '简介'], setting: [WORLD_LIMITS.setting, '设定'], rules: [WORLD_LIMITS.rules, '叙事规则'], userRole: [WORLD_LIMITS.userRole, '你的身份'], userName: [WORLD_LIMITS.userName, '你的称呼'], groupName: [WORLD_LIMITS.groupName, '群名'] };
        for (const [key, value] of Object.entries(patch)) {
          if (key in texts) {
            next[key] = field(patch, key, texts[key][0], texts[key][1], { required: ['name', 'setting', 'userName', 'groupName'].includes(key) });
          } else if (key === 'model') next.model = validateModel(value);
          else if (key === 'settings') next.settings = validateSettings(value, world.def.settings);
          else if (key === 'worldBook') next.worldBook = validateBook(value);
          else if (key === 'facts') next.facts = validateFacts(value);
          else throw new WorldError(`不能修改：${key}`, 400, 'invalid_world');
        }
        world.def = next;
        await saveDef(world);
        return this.get(id);
      });
    },

    async pause(id) {
      const world = await need(id);
      return locked(world, async () => {
        if (!world.state.paused) {
          world.state.pausedWorldAt = worldTime(world.state, now());
          world.state.paused = true;
          await saveState(world);
        }
        return view(world);
      });
    },

    async resume(id) {
      const world = await need(id);
      return locked(world, async () => {
        if (world.state.paused) {
          const at = now();
          world.state.anchorReal = at;
          world.state.anchorWorld = world.state.pausedWorldAt;
          world.state.paused = false;
          world.state.pausedWorldAt = null;
          world.state.dueFrom = at;
          await saveState(world);
        }
        return view(world);
      });
    },

    tick: (id) => tick(id, { manual: true }),

    /** Timeline items after [since], or the last [tail] items. [seen] marks them read and resets the slow-down. */
    async messages(id, { since = 0, tail = 0, limit = 200, seen = false } = {}) {
      const world = await need(id);
      const max = Math.min(Math.max(Number(limit) || 200, 1), 500);
      let items;
      let more = false;
      if (Number(tail) > 0) items = world.timeline.slice(-Math.min(Number(tail), 500));
      else {
        const after = world.timeline.filter((item) => item.seq > (Number(since) || 0));
        more = after.length > max;
        items = after.slice(0, max);
      }
      if (seen) {
        world.state.lastSeenAt = now();
        world.state.lastSeenSeq = Math.max(world.state.lastSeenSeq || 0, items.at(-1)?.seq || 0);
        await saveState(world);
      }
      return { items: items.map((item) => publicItem(world, item)), next: items.at(-1)?.seq ?? (Number(since) || 0), more, world: view(world) };
    },

    /** The user speaks in the group ('group') or to one resident ('dm:r1'); the answer comes back with it. */
    async say(id, { channel, text: body } = {}) {
      const world = await need(id);
      const message = text(body);
      if (!message) throw new WorldError('消息是空的', 400, 'invalid_message');
      if (message.length > WORLD_LIMITS.userText) throw new WorldError(`一条消息最多 ${WORLD_LIMITS.userText} 字`, 400, 'invalid_message');
      const target = channel === 'group' ? null : world.def.residents.find((r) => channel === `dm:${r.id}`);
      if (channel !== 'group' && !target) throw new WorldError('channel 应为 group 或 dm:居民编号', 400, 'invalid_message');
      return locked(world, async () => {
        const at = now();
        const hold = holdOf(world, at);
        if (hold === 'paused') throw new WorldError('世界已暂停，恢复后再说话', 409, 'world_paused');
        if (hold === 'calls') throw new WorldError('今天的请求次数已经用完，明天再来', 429, 'world_budget');
        const worldNow = worldTime(world.state, at);
        const [mine] = append(world, [{ kind: 'message', at, worldAt: worldNow, channel, from: 'user', name: world.def.userName, text: message }]);
        world.state.lastSeenAt = at;
        world.state.lastSeenSeq = mine.seq;
        let content;
        try {
          content = await callModel(world, directorMessages(snapshot(world), { mode: target ? 'dm' : 'group', worldNow, target }), TOKENS.reply);
        } catch (error) {
          await saveState(world);
          if (error?.name === 'AbortError') throw error;
          throw new WorldError(`这次没有收到回应（${error?.message || '请求失败'}）。你的消息已保存，下次推进时她们会看到。`, 502, 'world_reply_failed');
        }
        const decision = parseDirector(content, world.def);
        if (!decision) {
          await saveState(world);
          throw new WorldError('回应的格式不对，没有收到回复。你的消息已保存，下次推进时她们会看到。', 502, 'world_reply_invalid');
        }
        // In a private chat only the person written to answers privately; the others cannot know.
        if (target) decision.dms = decision.dms.filter((line) => line.from === target.id);
        const replies = applyDecision(world, decision, { worldNow, burst: false, tick: `u${mine.seq}` });
        world.state.lastSeenSeq = Math.max(world.state.lastSeenSeq, replies.at(-1)?.seq || 0);
        await saveState(world);
        return { items: [mine, ...replies].map((item) => publicItem(world, item)), world: view(world) };
      });
    },

    async diary(id) {
      const world = await need(id);
      return { entries: world.diary, facts: world.def.facts || [], residents: world.def.residents.map((r) => ({ id: r.id, name: r.name, notes: world.state.residents[r.id]?.notes || '' })) };
    },

    /** Rewrites one day's chapter in the user's words; the diary file is written again in full. */
    async editDiary(id, day, body) {
      const world = await need(id);
      if (!DAY_ID.test(String(day))) throw new WorldError('日期格式应为 YYYY-MM-DD', 400, 'invalid_day');
      const value = text(body);
      if (!value) throw new WorldError('日记内容是空的', 400, 'invalid_day');
      if (value.length > WORLD_LIMITS.chapter) throw new WorldError(`一章日记最多 ${WORLD_LIMITS.chapter} 字`, 400, 'invalid_day');
      return locked(world, async () => {
        const entry = world.diary.find((item) => item.day === day);
        if (!entry) throw new WorldError('这一天没有日记', 404, 'day_not_found');
        entry.text = value;
        entry.edited = true;
        await saveDiary(world);
        return this.diary(id);
      });
    },

    async removeDiary(id, day) {
      const world = await need(id);
      return locked(world, async () => {
        const before = world.diary.length;
        world.diary = world.diary.filter((item) => item.day !== day);
        if (world.diary.length === before) throw new WorldError('这一天没有日记', 404, 'day_not_found');
        await saveDiary(world);
        return this.diary(id);
      });
    },

    /** A new resident (from the phone, often a character card copied in); it joins at the world's current moment. */
    async addResident(id, body) {
      const world = await need(id);
      return locked(world, async () => {
        if (world.def.residents.length >= WORLD_LIMITS.residents) throw new WorldError(`一个世界最多 ${WORLD_LIMITS.residents} 位居民`, 400, 'invalid_world');
        const resident = validateResident(body, nextResidentId(world), takenNames(world));
        world.def = { ...world.def, residents: [...world.def.residents, resident] };
        world.state.residents[resident.id] = Object.fromEntries(Object.entries(resident.start).filter(([, value]) => value));
        await saveDef(world);
        await saveState(world);
        log.log?.(`小世界 ${world.def.name} 加入居民：${resident.name}`);
        return this.get(id);
      });
    },

    /** Edits a resident's card, names or what they remember; fields not sent stay as they are. */
    async updateResident(id, rid, body) {
      const world = await need(id);
      if (!body || typeof body !== 'object' || Array.isArray(body)) throw new WorldError('居民内容必须是对象', 400, 'invalid_world');
      return locked(world, async () => {
        const current = needResident(world, rid);
        const state = world.state.residents[rid] || {};
        const merged = validateResident({
          name: body.name ?? current.name, aliases: body.aliases ?? current.aliases, card: body.card ?? current.card,
          location: body.location ?? state.location, activity: body.activity ?? state.activity, mood: body.mood ?? state.mood,
          plan: body.plan ?? state.plan, notes: body.notes ?? state.notes,
        }, rid, takenNames(world, rid));
        world.def = { ...world.def, residents: world.def.residents.map((r) => (r.id === rid ? { ...merged, start: current.start } : r)) };
        world.state.residents[rid] = Object.fromEntries(Object.entries(merged.start).filter(([, value]) => value));
        await saveDef(world);
        await saveState(world);
        return this.get(id);
      });
    },

    /** Moves a resident out. What they said stays in the timeline; their id is never given to anyone else. */
    async removeResident(id, rid) {
      const world = await need(id);
      return locked(world, async () => {
        const resident = needResident(world, rid);
        if (world.def.residents.length <= 1) throw new WorldError('世界里至少要留一位居民', 400, 'invalid_world');
        world.def = { ...world.def, residents: world.def.residents.filter((r) => r.id !== rid), retiredResidents: [...(world.def.retiredResidents || []), rid] };
        delete world.state.residents[rid];
        await saveDef(world);
        await saveState(world);
        log.log?.(`小世界 ${world.def.name} 搬走居民：${resident.name}`);
        return this.get(id);
      });
    },

    async remove(id) {
      const world = await need(id);
      // A request still running for this world finishes first, so nothing is written after the files go.
      await locked(world, async () => { worlds.delete(id); });
      await world.writes;
      await rm(world.dir, { recursive: true, force: true });
      log.log?.(`小世界已删除：${world.def.name}`);
      return { deleted: id };
    },

    /** Runs every world that is due, one after another. */
    async runDue() {
      if (sweeping) return 0;
      sweeping = true;
      let ran = 0;
      try {
        await load();
        for (const world of worlds.values()) {
          if (!isDue(world, now())) continue;
          ran += 1;
          try { await tick(world.def.id); }
          catch (error) { if (error?.name !== 'AbortError') log.warn?.(`小世界 ${world.def.name} 推进失败：${error.message}`); }
        }
      } finally { sweeping = false; }
      return ran;
    },

    /** Starts the clock. A world that missed ticks while the PC was off catches up once, about two minutes later. */
    async start() {
      await load();
      const at = now();
      for (const world of worlds.values()) {
        const interval = intervalMs(world.def.settings, world.state, at);
        if (!world.state.paused && at - world.state.dueFrom > interval) {
          world.state.dueFrom = at - interval + 2 * MINUTE;
          await saveState(world);
        }
      }
      if (!timer && checkEveryMs > 0) {
        timer = setInterval(() => { this.runDue().catch((error) => log.warn?.(`小世界检查失败：${error.message}`)); }, checkEveryMs);
        timer.unref?.();
      }
      return worlds.size;
    },

    stop() { if (timer) clearInterval(timer); timer = null; },
    busy: () => inflight.size,
    abortAll() { for (const controller of inflight) controller.abort(); },
    async summary() {
      await load();
      const all = [...worlds.values()];
      return { count: all.length, running: all.filter((world) => !world.state.paused).length };
    },
    async flush() { await Promise.all([...worlds.values()].map((world) => world.writes)); },
  };
}
