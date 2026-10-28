import { mkdir, readFile, writeFile } from 'node:fs/promises';
import { randomBytes, createHash } from 'node:crypto';
import { homedir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { isIP } from 'node:net';

export function isPrivateIpv4(host) {
  if (isIP(host) !== 4) return false;
  const [a, b] = host.split('.').map(Number);
  return a === 10 || (a === 172 && b >= 16 && b <= 31) || (a === 192 && b === 168);
}

export const defaultDataDir = () => process.env.POCKET_WRITING_DATA_DIR || path.join(
  process.env.LOCALAPPDATA || path.join(homedir(), '.local', 'share'), 'PocketTavernServer');

export async function readPrivateText(filename) {
  try { return (await readFile(filename, 'utf8')).replace(/^\uFEFF/, '').trim(); }
  catch (error) { if (error.code === 'ENOENT') return ''; throw new Error('无法读取服务端私密配置文件'); }
}

export async function loadConfig(dataDir = defaultDataDir()) {
  dataDir = path.resolve(dataDir);
  await mkdir(dataDir, { recursive: true, mode: 0o700 });
  const configFile = path.join(dataDir, 'config.json');
  const template = fileURLToPath(new URL('../config.example.json', import.meta.url));
  try { await writeFile(configFile, await readFile(template), { flag: 'wx', mode: 0o600 }); }
  catch (error) { if (error.code !== 'EEXIST') throw error; }
  let raw;
  try { raw = JSON.parse(await readPrivateText(configFile)); }
  catch { throw new Error('config.json 不是有效 JSON'); }
  const host = raw.host || '127.0.0.1';
  if (!['127.0.0.1', '::1'].includes(host) && !(raw.lanMode === true && isPrivateIpv4(host))) {
    throw new Error('服务只能监听本机，或显式启用 lanMode 后的内网 IPv4 地址');
  }
  const port = raw.port ?? 8791;
  if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error('服务端口无效');
  let base;
  try { base = new URL(raw.upstreamBaseUrl); } catch { throw new Error('上游地址无效'); }
  if (base.protocol !== 'https:' || base.username || base.password || base.search || base.hash) throw new Error('上游地址必须是无凭据的 HTTPS Base URL');
  if (typeof raw.upstreamModel !== 'string' || !raw.upstreamModel.trim()) throw new Error('未设置上游模型');
  const contextTokens = raw.contextTokens;
  if (!Number.isInteger(contextTokens) || contextTokens < 4096 || contextTokens > 4_194_304) throw new Error('模型窗口配置无效');
  const tokenFile = path.join(dataDir, 'gateway-token.txt');
  try { await writeFile(tokenFile, randomBytes(32).toString('base64url'), { flag: 'wx', mode: 0o600 }); }
  catch (error) { if (error.code !== 'EEXIST') throw error; }
  const token = await readPrivateText(tokenFile);
  if (token.length < 32 || /\s/.test(token)) throw new Error('网关 Token 无效');
  const upstreamKey = (process.env.VENICE_API_KEY || await readPrivateText(path.join(dataDir, 'venice-key.txt'))).trim();
  if (upstreamKey && /\s/.test(upstreamKey)) throw new Error('Venice Key 必须为单行');
  const allowedSkills = new Set(['none', 'sh-shuorenhua', 'ss-stop-slop']);
  const reviewSkill = raw.reviewSkill ?? 'sh-shuorenhua';
  if (!allowedSkills.has(reviewSkill)) throw new Error('审校 Skill 配置无效');
  let reviewSkillText = '';
  let reviewSkillSource = null;
  if (reviewSkill !== 'none') {
    const assets = fileURLToPath(new URL('../../app/src/main/assets/writing-skills/', import.meta.url));
    const catalog = JSON.parse(await readFile(path.join(assets, 'catalog.json'), 'utf8'));
    const entry = catalog.skills.find(skill => skill.id === reviewSkill);
    if (!entry) throw new Error('未找到已固定版本的审校 Skill');
    const bytes = await readFile(path.join(assets, reviewSkill, 'SKILL.md'));
    if (createHash('sha256').update(bytes).digest('hex') !== entry.sha256) throw new Error('审校 Skill 原文校验失败');
    reviewSkillText = bytes.toString('utf8');
    reviewSkillSource = { id: entry.id, commit: entry.commit, sha256: entry.sha256, sourceUrl: entry.sourceUrl };
  }
  return {
    host, port, dataDir, token, upstreamKey, upstreamBaseUrl: base.href.replace(/\/$/, ''),
    upstreamModel: raw.upstreamModel, contextTokens, reviewSkillText, reviewSkillSource,
    reasoningEffort: raw.reasoningEffort ?? 'default',
    reviewReasoningEffort: raw.reviewReasoningEffort ?? 'none',
    autoRevise: raw.autoRevise === true,
    includeVeniceSystemPrompt: raw.includeVeniceSystemPrompt === true,
  };
}
