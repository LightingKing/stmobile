/**
 * Replies sometimes arrive from the upstream with a character replaced by two to four U+FFFD
 * marks in the middle of a sentence. The bytes of one character were cut apart and decoded separately
 * somewhere before the text reached this PC: this server's own reading is byte-exact, and the
 * marks are already inside the upstream's JSON. The character itself is gone, but its place is
 * known and it is always a single one, so the model is asked, in one small request without
 * streaming, which character belongs at each place. An answer is only taken when it is exactly
 * one non-ASCII character; anything else leaves that place as it came.
 */
const DAMAGE = /\ufffd+/g;
const MARK = '⟦?⟧';

export const REPAIR_LIMITS = {
  /** More damaged places than this in one reply is not the one-character fault this handles. */
  spots: 24,
  /** Characters of text shown on each side of a damaged place. */
  context: 40,
  /** One cut character leaves at most four marks (a four-byte character cut after its first byte). */
  run: 4,
  /** Room for the answer; thinking is switched off where the settings allow it. */
  maxTokens: 1200,
};

export const REPAIR_SYSTEM = `下面每一行是一段文字的片段，其中有一处在传输中损坏、丢了一个字符（一个汉字或一个全角标点），位置用 ${MARK} 标出。` +
  '根据前后文判断每一处丢的是哪一个字符：选让这句话最通顺、最常见的那个字，标点成对出现时补上缺的那一半。只返回一个 JSON 数组，按行的顺序，每项就是那一个字符本身，例如 ["忙","一","（"]。不要解释，不要输出别的内容。';

/** Where a reply is damaged: every run of U+FFFD, in order. */
export function damagedSpots(text) {
  return [...String(text ?? '').matchAll(DAMAGE)].map((match) => ({ index: match.index, length: match[0].length }));
}

const flat = (text) => text.replace(DAMAGE, '□').replace(/\s+/g, ' ');

/** The request that asks for the missing characters: one numbered line of context per damaged place. */
export function repairMessages(text, spots) {
  const lines = spots.map((spot, number) => {
    const before = flat(text.slice(Math.max(0, spot.index - REPAIR_LIMITS.context), spot.index));
    const after = flat(text.slice(spot.index + spot.length, spot.index + spot.length + REPAIR_LIMITS.context));
    return `${number + 1}. ${before}${MARK}${after}`;
  });
  return [{ role: 'system', content: REPAIR_SYSTEM }, { role: 'user', content: lines.join('\n') }];
}

/** The model's answer as a list with one entry per damaged place, or null when it is anything else. */
export function parseRepair(content, count) {
  const text = String(content ?? '');
  const start = text.indexOf('[');
  const end = text.lastIndexOf(']');
  if (start < 0 || end <= start) return null;
  let list;
  try { list = JSON.parse(text.slice(start, end + 1)); } catch { return null; }
  return Array.isArray(list) && list.length === count ? list : null;
}

/** One character that can have been cut: not ASCII (a single byte cannot be cut), not a blank, not a mark itself. */
function oneLostCharacter(value) {
  if (typeof value !== 'string') return false;
  const characters = [...value];
  if (characters.length !== 1) return false;
  const code = characters[0].codePointAt(0);
  return code > 0x7f && code !== 0xfffd && !/\s/u.test(characters[0]) && !MARK.includes(characters[0]);
}

/** Puts accepted answers in place; a place whose answer is not one character stays as it came. */
export function applyRepair(text, spots, answers) {
  let result = String(text ?? '');
  let repaired = 0;
  if (!Array.isArray(answers) || answers.length !== spots.length) return { text: result, repaired, left: spots.length };
  for (let index = spots.length - 1; index >= 0; index--) {
    const spot = spots[index];
    if (spot.length > REPAIR_LIMITS.run || !oneLostCharacter(answers[index])) continue;
    result = result.slice(0, spot.index) + answers[index] + result.slice(spot.index + spot.length);
    repaired++;
  }
  return { text: result, repaired, left: spots.length - repaired };
}

/**
 * Earlier replies the phone sends back as history may still carry the marks from before this
 * repair existed. The model should not read them as part of its own writing, so they are left
 * out of what goes upstream; the phone's stored messages are not touched.
 */
export function withoutDamage(messages) {
  if (!Array.isArray(messages)) return messages;
  const clean = (value) => (typeof value === 'string' && value.includes('\ufffd') ? value.replace(DAMAGE, '') : value);
  return messages.map((message) => {
    if (message?.role !== 'assistant') return message;
    if (typeof message.content === 'string') return message.content.includes('\ufffd') ? { ...message, content: clean(message.content) } : message;
    if (!Array.isArray(message.content)) return message;
    return { ...message, content: message.content.map((part) => (part && typeof part.text === 'string' ? { ...part, text: clean(part.text) } : part)) };
  });
}
