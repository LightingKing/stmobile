// PC dashboard. Every text from the server goes through esc() before it reaches the page; the CSP
// allows no inline styles or handlers, so sizes and positions are set through element.style.
const $ = (id) => document.getElementById(id);
const esc = (value) => String(value ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const size = (bytes) => bytes >= 1048576 ? `${(bytes / 1048576).toFixed(1)} MB` : bytes >= 1024 ? `${Math.round(bytes / 1024)} KB` : `${bytes || 0} B`;
const pad = (n) => String(n).padStart(2, '0');
const clock = (date) => `${pad(date.getHours())}:${pad(date.getMinutes())}`;
const dayStart = (date) => new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime();
const minutes = (seconds) => seconds === 0 ? '不限' : seconds % 60 === 0 ? `${seconds / 60} 分钟` : `${seconds} 秒`;
const wan = (n) => n >= 10000 ? `${Number((n / 10000).toFixed(n >= 1e7 ? 0 : 1))} 万` : Number(n || 0).toLocaleString('zh-CN');
const took = (ms) => ms < 60000 ? `${(ms / 1000).toFixed(1)} 秒` : ms < 3600000 ? `${Math.floor(ms / 60000)} 分 ${Math.floor((ms % 60000) / 1000)} 秒` : `${Math.floor(ms / 3600000)} 小时 ${Math.floor((ms % 3600000) / 60000)} 分`;
const kilo = (n) => n >= 1000 ? `${(n / 1000).toFixed(1)}k` : String(n);
const initial = (name) => { const first = [...String(name || '?').trim()][0] || '?'; return first.toUpperCase(); };

/** "今天 08:12", "昨天 23:40" or "11 月 15 日 10:02". */
function when(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return '—';
  const days = Math.round((dayStart(new Date()) - dayStart(date)) / 86400000);
  if (days === 0) return `今天 ${clock(date)}`;
  if (days === 1) return `昨天 ${clock(date)}`;
  return `${date.getMonth() + 1} 月 ${date.getDate()} 日 ${clock(date)}`;
}
function ago(value) {
  const seconds = (Date.now() - new Date(value).getTime()) / 1000;
  if (!Number.isFinite(seconds)) return '—';
  if (seconds < 60) return '刚刚';
  if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟前`;
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} 小时前`;
  return when(value);
}
function lasting(seconds) {
  const m = Math.floor(seconds / 60);
  if (m < 60) return `${m} 分钟`;
  const h = Math.floor(m / 60);
  return h < 48 ? `${h} 小时 ${m % 60} 分` : `${Math.floor(h / 24)} 天 ${h % 24} 小时`;
}

const PURPOSES = { chat: '聊天', summary: '摘要', compact: '压缩', rebuild: '重建记忆' };
const KINDS = { chat: '手机聊天', world: '小世界', memory: '整理记忆' };
const STATUS = { complete: '完成', failed: '失败', running: '进行中' };
const REASONS = { manual: '手动备份', auto: '自动备份', 'pre-restore': '恢复前自动保存' };
const EFFORTS = { default: '默认（不传）', none: '关闭', low: '低', medium: '中', high: '高' };
const HOLDS = { paused: '暂停中', calls: '今天的请求次数用完了', ticks: '今天的推进次数用完了', quiet: '安静时段，居民在睡觉' };
const SOURCE_DEFAULTS = {
  openai: { url: '', name: '', urlHint: 'https://api.example.com/v1', nameHint: '例如 中转、DeepSeek' },
  venice: { url: 'https://api.venice.ai/api/v1', name: 'Venice 2', urlHint: 'https://api.venice.ai/api/v1', nameHint: '' },
  ollama: { url: 'http://127.0.0.1:11434/v1', name: 'Ollama', urlHint: 'http://127.0.0.1:11434/v1', nameHint: '' },
};

async function api(path, options = {}) {
  const write = options.method && options.method !== 'GET';
  const response = await fetch(path, { ...options, headers: { ...(options.body ? { 'content-type': 'application/json' } : {}), ...(write ? { 'x-pocket-admin': '1' } : {}), ...(options.headers || {}) } });
  const type = response.headers.get('content-type') || '';
  const body = type.includes('json') ? await response.json() : await response.text();
  if (!response.ok) throw new Error(body?.error?.message || `HTTP ${response.status}`);
  return body;
}
const send = (path, method, body) => api(path, { method, ...(body === undefined ? {} : { body: JSON.stringify(body) }) });
const safely = (load) => Promise.resolve().then(load).catch((error) => console.warn(error));

let toastTimer = null;
function toast(message, bad = false) {
  const box = $('toast');
  box.textContent = message;
  box.className = `toast on${bad ? ' bad' : ''}`;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { box.className = 'toast'; }, bad ? 6000 : 3000);
}
/** Runs [work] with [button] disabled; a failure becomes a red toast. */
async function busy(button, work) {
  if (button) button.disabled = true;
  try { return await work(); } catch (error) { toast(error.message, true); return undefined; } finally { if (button) button.disabled = false; }
}
const row = (state, what, detail, end = '') => `<div class="row"><span class="state ${state}"></span><div><div class="what">${what}</div>${detail ? `<div class="detail">${detail}</div>` : ''}</div><div class="end">${end}</div></div>`;

// ---- shared data
const data = { status: null, config: null, sources: null, models: null, runs: null, worlds: null, connection: null };
const modelIndex = new Map();
const spec = (m) => m?.model_spec || {};
const modelName = (m) => spec(m).name || m?.name || m?.id || '';
function contextOf(m) { const value = Number(spec(m).availableContextTokens ?? m?.context_length); return value > 0 ? value : null; }
function capOf(m, names, plain) {
  const caps = spec(m).capabilities || m?.capabilities || {};
  for (const name of names) if (typeof caps[name] === 'boolean') return caps[name];
  return typeof m?.[plain] === 'boolean' ? m[plain] : null;
}
const canTools = (m) => capOf(m, ['supportsFunctionCalling', 'supportsTools'], 'supports_tools');
const canSee = (m) => capOf(m, ['supportsVision'], 'supports_vision');
const canThink = (m) => capOf(m, ['supportsReasoning'], 'supports_reasoning');
function modelLabel(id) {
  if (id === 'pocket-direct' || id === 'pocket-writer') return '默认模型';
  return modelName(modelIndex.get(id)) || id;
}
const defaultModel = () => data.config?.settings?.upstreamModel || data.status?.default_model?.id || '';
/** Which source a model id lives in: other sources prefix their ids with "<source id>/". */
function sourceOf(id, list = data.sources || data.status?.sources || []) {
  const slash = String(id).indexOf('/');
  const named = slash > 0 ? list.find((s) => s.id === id.slice(0, slash) && !s.builtin) : null;
  return named || list.find((s) => s.builtin) || null;
}

async function loadStatus() {
  try {
    const s = await api('/api/v1/pocket/status');
    data.status = s;
    $('brand-version').textContent = `电脑写作服务 ${s.version}`;
    const foot = [`<div><span class="dot${s.ready ? '' : ' warn'}"></span>${s.ready ? `运行中，已连续 ${esc(lasting(s.uptime_seconds))}` : '运行中，但没有能用的模型来源'}</div>`];
    if (s.active_requests) foot.push(`<div>正在处理 ${esc(s.active_requests)} 个请求</div>`);
    const lan = (s.listen?.hosts || []).find((h) => h !== '127.0.0.1' && h !== 'localhost');
    if (lan) foot.push(`<div>在家走局域网 ${esc(lan)}</div>`);
    if (data.connection?.public_url) foot.push(`<div>外面走 ${esc(new URL(data.connection.public_url).host)}</div>`);
    $('side-foot').innerHTML = foot.join('');
    const usable = (s.sources || []).filter((x) => x.enabled);
    $('nav-models').textContent = usable.length ? `${usable.length} 个来源` : '';
  } catch (error) {
    $('side-foot').innerHTML = `<div><span class="dot bad"></span>读不到服务：${esc(error.message)}</div>`;
  }
}

async function loadConfig() { data.config = await api('/api/v1/pocket/config'); return data.config; }
async function loadSources() { data.sources = (await api('/api/admin/sources')).sources; return data.sources; }
async function loadModels({ refresh = false } = {}) {
  const list = await api(`/api/v1/models${refresh ? '?refresh=true' : ''}`);
  data.models = list.data.filter((m) => !String(m.id).startsWith('pocket-'));
  modelIndex.clear();
  for (const m of data.models) modelIndex.set(m.id, m);
  return data.models;
}
async function loadRuns() { data.runs = (await api('/api/admin/runs?limit=200')).runs; return data.runs; }
async function loadWorldList() {
  data.worlds = (await api('/api/v1/pocket/worlds')).data;
  const unread = data.worlds.reduce((sum, w) => sum + (w.unread || 0), 0);
  $('nav-worlds').textContent = unread ? String(unread) : data.worlds.length ? String(data.worlds.length) : '';
  $('nav-worlds').classList.toggle('unread', unread > 0);
  $('nav-worlds').title = unread ? `${unread} 条没看的消息` : '';
  return data.worlds;
}
async function loadConnection() { data.connection = await api('/api/admin/connection'); return data.connection; }

const tokensOf = (run) => run.usage ? (run.usage.total_tokens ?? ((run.usage.prompt_tokens || 0) + (run.usage.completion_tokens || 0))) : 0;
function runTitle(run) {
  if (run.kind === 'world') return `小世界「${run.world}」`;
  if (run.kind === 'memory') return `整理记忆 · ${PURPOSES[run.purpose] || run.purpose}`;
  return `手机聊天 · ${modelLabel(run.model)}`;
}

// ---- 总览
// The band is the last 24 hours, so it reads the same at 1 a.m. as at 9 p.m.; midnight is marked on it.
const DAY = 86400000;
let ribbonDrawn = false;
function drawToday(runs) {
  const now = Date.now();
  const start = now - DAY;
  const place = (t) => `${((t - start) / DAY) * 100}%`;
  const today = runs.filter((run) => Date.parse(run.created_at) >= start).reverse();
  const failed = today.filter((run) => run.status === 'failed').length;
  const total = today.reduce((sum, run) => sum + tokensOf(run), 0);
  $('fig-runs').textContent = String(today.length);
  $('fig-tokens').textContent = wan(total);
  $('fig-failed').textContent = String(failed);
  $('fig-failed').classList.toggle('bad-text', failed > 0);
  const ribbon = $('ribbon');
  ribbon.classList.toggle('fresh', !ribbonDrawn);
  ribbon.textContent = '';
  const top = Math.max(20000, ...today.map(tokensOf));
  today.forEach((run, i) => {
    const at = new Date(run.created_at);
    const mark = document.createElement('div');
    mark.className = `mark k-${run.kind || 'chat'}${run.status === 'failed' ? ' failed' : ''}`;
    mark.style.left = place(at.getTime());
    mark.style.height = `${Math.max(8, (Math.log1p(tokensOf(run)) / Math.log1p(top)) * 86)}px`;
    mark.style.animationDelay = `${Math.min(i * 8, 600)}ms`;
    mark.title = `${clock(at)} ${runTitle(run)}${tokensOf(run) ? `，${tokensOf(run).toLocaleString()} tokens` : ''}${run.status === 'failed' ? '，失败' : ''}`;
    ribbon.append(mark);
  });
  if (!today.length) ribbon.insertAdjacentHTML('beforeend', '<div class="quiet">这 24 小时还没有请求</div>');
  const midnight = dayStart(new Date(now));
  const line = document.createElement('div');
  line.className = 'midnight';
  line.style.left = place(midnight);
  line.innerHTML = '<span>今天</span>';
  ribbon.append(line);
  // Clock hours every 6 hours along the band, and "现在" at its right end.
  const axis = $('axis');
  axis.textContent = '';
  for (let t = midnight - DAY; t < now; t += 6 * 3600000) {
    if (t <= start + 3600000 || t >= now - 2 * 3600000) continue;
    const label = document.createElement('span');
    label.style.left = place(t);
    label.textContent = `${new Date(t).getHours()} 点`;
    axis.append(label);
  }
  axis.insertAdjacentHTML('beforeend', '<span class="end">现在</span>');
  ribbonDrawn = true;
}

function drawHealth() {
  const s = data.status;
  if (!s) return;
  const rows = [];
  const list = (s.sources || []).filter((x) => x.enabled);
  const broken = list.filter((x) => !x.ready || x.error);
  const total = list.reduce((sum, x) => sum + (x.models || 0), 0);
  const fix = '<a class="btn quiet" href="#models">去看看</a>';
  const defaultLine = `默认模型 ${esc(s.default_model?.name || s.default_model?.id)}${s.default_model?.source ? `（${esc(s.default_model.source)}）` : ''}`;
  if (!s.ready) rows.push(row('bad', '没有能用的模型来源', '在“模型”页添加一个来源，或给内置来源填上密钥', fix));
  else if (broken.length) rows.push(row('warn', `${esc(broken.map((x) => x.name).join('、'))} 读不到模型`, esc(broken.map((x) => x.error).filter(Boolean).join('；') || defaultLine), fix));
  else rows.push(row('', '模型来源都能用', defaultLine, `${list.length} 个来源<br>${total} 个模型`));

  if (s.phone) {
    const via = s.phone.via === 'lan' ? `走家里局域网（${esc(s.phone.address)}）` : '走远程地址';
    rows.push(row('', `手机 ${esc(ago(s.phone.at))}连过`, via, s.backups?.latest?.app_version ? `口袋酒馆 ${esc(s.backups.latest.app_version)}` : ''));
  } else rows.push(row('off', '这次启动后手机还没连过', `服务启动于 ${esc(when(s.started_at))}`, ''));

  const worlds = data.worlds;
  if (worlds) {
    if (!worlds.length) rows.push(row('off', '还没有小世界', '手机上可以把剧本“做成小世界”', ''));
    else {
      const running = worlds.filter((w) => !w.paused);
      const failing = worlds.filter((w) => w.last_error && Date.now() - w.last_error.at < 86400000);
      const parts = [
        ...running.slice(0, 2).map((w) => `「${esc(w.name)}」${w.next_tick_at ? `下次推进 ${esc(clock(new Date(w.next_tick_at)))}` : esc(HOLDS[w.hold] || '运行中')}`),
        ...worlds.filter((w) => w.paused).slice(0, 2).map((w) => `「${esc(w.name)}」暂停中`),
      ];
      const what = failing.length ? `小世界「${esc(failing[0].name)}」上次推进出错` : running.length ? `${running.length} 个小世界在运行` : '小世界都暂停了';
      rows.push(row(failing.length ? 'warn' : running.length ? '' : 'off', what, failing.length ? esc(failing[0].last_error.message) : parts.join('，'), '<a class="btn quiet" href="#worlds">去看看</a>'));
    }
  }

  const b = s.backups;
  if (b?.latest) rows.push(row('', '手机备份', `${esc(when(b.latest.created_at))} ${esc(REASONS[b.latest.reason] || '')}，${esc(size(b.latest.size))}`, `${esc(b.count)} 份，保留 ${esc(b.keep)} 份`));
  else rows.push(row('off', '还没有手机备份', '手机“设置 → 备份”可以备份到这台电脑', ''));

  const social = s.social;
  const login = '<a class="btn quiet" href="#reach">去登录</a>';
  if (s.reach && !s.reach.available) rows.push(row('warn', '联网搜索不能用', esc(s.reach.error || '没有找到 Agent Reach'), '<a class="btn quiet" href="#reach">去看看</a>'));
  if (social?.available) {
    const platforms = Object.values(social.platforms || {});
    const out = platforms.filter((p) => !p.logged_in);
    const blocked = platforms.filter((p) => p.logged_in && p.blocked_at);
    const inside = platforms.filter((p) => p.logged_in).map((p) => p.name);
    if (blocked.length) rows.push(row('warn', `${esc(blocked.map((p) => p.name).join('、'))} 要验证`, '上次查询时平台要求验证', login));
    else if (out.length) rows.push(row('warn', `${esc(out.map((p) => p.name).join('、'))} 没登录`, inside.length ? `${esc(inside.join('、'))} 已登录` : '登录后模型才能查这些平台上的角色资料', login));
    else if (platforms.length) rows.push(row('', '社交平台都已登录', esc(inside.join('、')), social.keepalive ? `${esc(ago(social.keepalive.at))}保活` : ''));
  }
  $('health').innerHTML = rows.join('');
}

function drawRecent(runs) {
  const items = runs.slice(0, 8);
  $('recent').innerHTML = items.length ? items.map((run) => {
    const at = new Date(run.created_at);
    return `<a class="req" href="#records/${esc(run.run_id)}"><time>${esc(clock(at))}</time><i class="k-${esc(run.kind || 'chat')}"></i>` +
      `<span class="t">${esc(runTitle(run))}${run.status === 'failed' ? ' <span class="failed">失败</span>' : ''}</span><span class="n">${tokensOf(run) ? esc(kilo(tokensOf(run))) : '—'}</span></a>`;
  }).join('') : '<p class="empty">还没有请求记录。</p>';
}

async function loadOverview() {
  const now = new Date();
  $('today-label').textContent = `${now.getMonth() + 1} 月 ${now.getDate()} 日，星期${'日一二三四五六'[now.getDay()]}`;
  await Promise.all([loadRuns(), loadWorldList().catch(() => null), data.models ? null : loadModels().catch(() => null)]);
  drawToday(data.runs);
  drawRecent(data.runs);
  drawHealth();
}

// ---- 模型
const modelFilter = { q: '', source: 'all', vision: false, tools: false };

function drawSources() {
  const list = data.sources || [];
  const home = sourceOf(defaultModel(), list);
  $('source-list').innerHTML = list.map((s) => {
    const tags = [
      home?.id === s.id ? '<span class="tag">默认模型在这里</span>' : '',
      `<span class="tag grey">${esc(s.kind_name)}${s.builtin ? '，内置' : ''}</span>`,
      s.enabled ? '' : '<span class="tag grey">已停用</span>',
      s.error ? `<span class="tag red">${s.kind === 'ollama' ? '没有运行' : '读取失败'}</span>` : '',
    ].join('');
    const key = s.has_key ? `密钥 ••••${esc(s.key_tail)}` : s.kind === 'ollama' ? '不需要密钥' : '<span class="bad-text">没有密钥</span>';
    const meta = [esc(s.base_url.replace(/^https?:\/\//, '')), key, s.models === null ? '还没读取' : s.error ? `上次读到 ${esc(s.models)} 个模型` : `${esc(s.models)} 个模型`,
      s.fetched_at ? `${esc(ago(s.fetched_at))}读取` : '', s.builtin ? '' : `手机里显示为“${esc(s.name)}/模型名”`].filter(Boolean).map((m) => `<span>${m}</span>`).join('');
    return `<div class="source${home?.id === s.id ? ' default' : ''}${s.enabled ? '' : ' off'}"><div class="badge ${esc(s.kind)}" aria-hidden="true">${esc(initial(s.name))}</div>` +
      `<div><div class="name">${esc(s.name)} ${tags}</div><div class="meta">${meta}</div>${s.error ? `<div class="meta bad-text">${esc(s.error)}</div>` : ''}</div>` +
      `<div class="actions"><button class="btn quiet" type="button" data-refresh="${esc(s.id)}">重新读取</button><button class="btn" type="button" data-edit="${esc(s.id)}">编辑</button></div></div>`;
  }).join('') || '<p class="empty">还没有来源。</p>';
}

function drawModelChips() {
  const names = [...new Set((data.models || []).map((m) => m.pocket_source?.name).filter(Boolean))];
  const chip = (key, label, on) => `<button class="chip" type="button" data-chip="${esc(key)}" aria-pressed="${on}">${esc(label)}</button>`;
  $('model-chips').innerHTML = [chip('source:all', '全部来源', modelFilter.source === 'all'), ...(names.length > 1 ? names.map((n) => chip(`source:${n}`, n, modelFilter.source === n)) : []),
    chip('vision', '能看图', modelFilter.vision), chip('tools', '能调工具', modelFilter.tools)].join('');
}

function capCell(m) {
  const guess = m.pocket_tavern_guessed ? ` guess" title="平台没写，按 ${esc(m.pocket_tavern_guessed.from)} 推测` : '';
  return [[canTools(m), '工具'], [canSee(m), '看图'], [canThink(m), '思考']].filter(([on]) => on).map(([, label]) => `<span class="cap${guess}">${label}</span>`).join('') || '<span class="sub">没写</span>';
}
function priceCell(m) {
  const p = spec(m).pricing;
  const usd = (v) => Number(v).toFixed(2).replace(/\.?0+$/, '') || '0';
  return p?.input?.usd != null ? `输入 ${esc(usd(p.input.usd))} / 输出 ${esc(usd(p.output?.usd ?? 0))}` : '<span class="sub">平台没给</span>';
}

function drawModelTable() {
  const current = defaultModel();
  const q = modelFilter.q.trim().toLowerCase();
  const order = new Map((data.sources || []).map((s, i) => [s.name, i]));
  const shown = (data.models || []).filter((m) => (modelFilter.source === 'all' || m.pocket_source?.name === modelFilter.source)
    && (!modelFilter.vision || canSee(m)) && (!modelFilter.tools || canTools(m))
    && (!q || `${m.id} ${modelName(m)}`.toLowerCase().includes(q)))
    .sort((a, b) => (b.id === current) - (a.id === current) || (order.get(a.pocket_source?.name) ?? 99) - (order.get(b.pocket_source?.name) ?? 99) || modelName(a).localeCompare(modelName(b), 'zh-CN'));
  $('model-count').textContent = data.models ? `${shown.length} / ${data.models.length} 个` : '';
  if (!data.models) { $('model-table').innerHTML = '<tr><td class="muted">读取中…</td></tr>'; return; }
  $('model-table').innerHTML = '<thead><tr><th>模型</th><th>来源</th><th>上下文</th><th>能力</th><th>价格（每百万 tokens，美元）</th><th></th></tr></thead><tbody>' + (shown.length ? shown.map((m) => {
    const context = contextOf(m);
    const action = m.id === current ? '<span class="tag">默认</span>' : `<button class="btn quiet" type="button" data-default="${esc(m.id)}">设为默认</button>`;
    return `<tr${m.id === current ? ' class="is-default"' : ''}><td><b>${esc(modelName(m))}</b><div class="sub">${esc(m.id)}</div></td><td>${esc(m.pocket_source?.name || '')}</td>` +
      `<td class="num">${context ? esc(wan(context)) : '<span class="sub">没写</span>'}</td><td>${capCell(m)}</td><td class="num">${priceCell(m)}</td><td class="num">${action}</td></tr>`;
  }).join('') : '<tr><td colspan="6" class="muted">没有符合条件的模型。</td></tr>') + '</tbody>';
}

async function loadModelsPage() {
  await Promise.all([loadSources(), loadConfig()]);
  drawSources();
  drawModelTable();
  await loadModels();
  drawModelChips();
  drawModelTable();
}

$('model-search').addEventListener('input', (event) => { modelFilter.q = event.target.value; drawModelTable(); });
$('model-chips').addEventListener('click', (event) => {
  const chip = event.target.closest('[data-chip]');
  if (!chip) return;
  const key = chip.dataset.chip;
  if (key.startsWith('source:')) modelFilter.source = key.slice(7);
  else modelFilter[key] = !modelFilter[key];
  drawModelChips();
  drawModelTable();
});
$('model-table').addEventListener('click', (event) => {
  const button = event.target.closest('[data-default]');
  if (button) busy(button, () => setDefaultModel(button.dataset.default));
});
async function setDefaultModel(id) {
  await send('/api/v1/pocket/config', 'PATCH', { settings: { upstreamModel: id } });
  await loadConfig();
  toast(`默认模型换成了 ${modelLabel(id)}`);
  drawSources();
  drawModelTable();
  safely(loadStatus);
}
$('source-list').addEventListener('click', (event) => {
  const refresh = event.target.closest('[data-refresh]');
  const edit = event.target.closest('[data-edit]');
  if (refresh) busy(refresh, async () => {
    refresh.textContent = '读取中…';
    try {
      const updated = await send(`/api/admin/sources/${refresh.dataset.refresh}/refresh`, 'POST');
      toast(updated.error ? `${updated.name}：${updated.error}` : `${updated.name}：读到 ${updated.models} 个模型`, Boolean(updated.error));
      await Promise.all([loadSources(), loadModels()]);
      drawSources(); drawModelChips(); drawModelTable();
    } finally { refresh.textContent = '重新读取'; }
  });
  if (edit) openSourceDialog(data.sources.find((s) => s.id === edit.dataset.edit));
});

// The add / edit dialog. Keys go to the server and are never shown again, only their last 4 characters.
const dialog = { mode: 'add', kind: 'openai', source: null };
function setTest(text, kind = '') {
  $('source-test').hidden = !text;
  $('source-test').className = `test${kind ? ` ${kind}` : ''}`;
  $('source-test').textContent = text;
}
function pickKind(kind) {
  const before = SOURCE_DEFAULTS[dialog.kind];
  dialog.kind = kind;
  const next = SOURCE_DEFAULTS[kind];
  document.querySelectorAll('#source-kinds [data-kind]').forEach((b) => b.setAttribute('aria-pressed', String(b.dataset.kind === kind)));
  if (!$('source-url').value || $('source-url').value === before.url) $('source-url').value = next.url;
  if (!$('source-name').value || $('source-name').value === before.name) $('source-name').value = next.name;
  $('source-url').placeholder = next.urlHint;
  $('source-name').placeholder = next.nameHint;
  $('source-key-field').hidden = kind === 'ollama';
  setTest('');
}
function openSourceDialog(source = null) {
  dialog.mode = source ? 'edit' : 'add';
  dialog.source = source;
  $('source-title').textContent = source ? `编辑“${source.name}”` : '添加模型来源';
  $('source-lead').textContent = source
    ? (source.builtin ? '内置来源的地址写在 config.json 里；这里可以改名字和换密钥。新密钥会先试连，读得到模型才保存。' : '改了地址或密钥会先试连，读得到模型才保存。密钥不改就留空。')
    : '填好地址和密钥，先试一下能不能读到模型，再保存。密钥只存在这台电脑上，不会发到手机。';
  $('source-kinds').hidden = Boolean(source);
  $('source-try').hidden = Boolean(source);
  $('source-remove').hidden = !source || source.builtin;
  $('source-enabled-field').hidden = !source || source.builtin;
  $('source-url').disabled = Boolean(source?.builtin);
  $('source-name').value = source?.name || '';
  $('source-url').value = source?.base_url || '';
  $('source-key').value = '';
  $('source-key').placeholder = source ? (source.has_key ? `不改就留空（现在是 ••••${source.key_tail}）` : '还没有密钥') : 'sk-…';
  $('source-enabled').checked = source ? source.enabled : true;
  $('source-name-hint').hidden = Boolean(source?.builtin);
  dialog.kind = source?.kind || 'openai';
  if (source) { $('source-key-field').hidden = source.kind === 'ollama'; setTest(''); } else pickKind('openai');
  $('source-save').textContent = source ? '保存' : '保存来源';
  $('source-dialog').showModal();
  (source ? $('source-name') : $('source-url')).focus();
}
function describeTest(tested) {
  const sample = (tested.sample || []).slice(0, 4).join('、');
  return `读到了 ${tested.count} 个模型${tested.tools ? `，其中 ${tested.tools} 个写明能调工具` : ''}${sample ? `。例如 ${sample}` : ''}`;
}
const dialogButtons = () => ['source-try', 'source-save', 'source-remove'].map($);
async function dialogWork(label, work) {
  dialogButtons().forEach((b) => { b.disabled = true; });
  setTest(label, 'wait');
  try { await work(); } catch (error) { setTest(error.message, 'bad'); } finally { dialogButtons().forEach((b) => { b.disabled = false; }); }
}
document.querySelectorAll('#source-kinds [data-kind]').forEach((button) => button.addEventListener('click', () => pickKind(button.dataset.kind)));
$('source-add').addEventListener('click', () => openSourceDialog());
$('source-cancel').addEventListener('click', () => $('source-dialog').close());
$('source-try').addEventListener('click', () => dialogWork('正在连接，读模型列表…', async () => {
  const tested = await send('/api/admin/sources/test', 'POST', { kind: dialog.kind, baseUrl: $('source-url').value, key: $('source-key').value });
  setTest(`✓ ${describeTest(tested)}`);
}));
$('source-form').addEventListener('submit', (event) => {
  event.preventDefault();
  dialogWork(dialog.mode === 'add' ? '正在试连，读得到模型就保存…' : '正在保存…', async () => {
    if (dialog.mode === 'add') {
      const added = await send('/api/admin/sources', 'POST', { kind: dialog.kind, name: $('source-name').value, baseUrl: $('source-url').value, key: $('source-key').value });
      toast(`已添加“${added.name}”：${describeTest(added.tested)}`);
    } else {
      const s = dialog.source;
      const changes = { name: $('source-name').value };
      if (!s.builtin) { changes.baseUrl = $('source-url').value; changes.enabled = $('source-enabled').checked; }
      if ($('source-key').value.trim()) changes.key = $('source-key').value;
      await send(`/api/admin/sources/${s.id}`, 'PATCH', changes);
      toast(`已保存“${changes.name}”`);
    }
    $('source-dialog').close();
    await Promise.all([loadSources(), loadModels()]);
    drawSources(); drawModelChips(); drawModelTable();
    safely(loadStatus);
  });
});
$('source-remove').addEventListener('click', () => {
  const s = dialog.source;
  if (!s || !confirm(`删除来源“${s.name}”？它的密钥会从这台电脑上删掉，手机上也就看不到它的模型了。`)) return;
  dialogWork('正在删除…', async () => {
    await send(`/api/admin/sources/${s.id}`, 'DELETE');
    $('source-dialog').close();
    toast(`已删除“${s.name}”`);
    await Promise.all([loadSources(), loadModels()]);
    drawSources(); drawModelChips(); drawModelTable();
    safely(loadStatus);
  });
});

// ---- 小世界
function worldTag(w) {
  if (w.busy) return ['正在推进…', 'tag'];
  if (w.hold === 'paused') return ['暂停中', 'tag grey'];
  if (w.hold) return [HOLDS[w.hold] || w.hold, 'tag'];
  return ['运行中', 'tag green'];
}
function worldClock(w) {
  if (w.paused) return `世界时间停在 ${w.world_time_label}`;
  const next = w.next_tick_at ? `，下次推进 ${clock(new Date(w.next_tick_at))}` : '';
  return `世界时间 ${w.world_time_label}${next}${w.hold ? `（${HOLDS[w.hold] || w.hold}）` : ''}`;
}
function setWidths(root) { root.querySelectorAll('[data-width]').forEach((el) => { el.style.width = `${el.dataset.width}%`; }); }

function drawWorldList() {
  const list = data.worlds || [];
  $('world-list').innerHTML = list.length ? list.map((w) => {
    const [label, cls] = worldTag(w);
    const pct = Math.min(100, Math.round((w.usage.calls / Math.max(1, w.settings.dailyCalls)) * 100));
    const last = w.last_message ? `<b>${esc(w.last_message.name)}</b>${esc(w.last_message.text)}` : '<span class="muted">还没有消息</span>';
    return `<article class="world" data-world="${esc(w.id)}"><div class="world-top"><h3>${esc(w.name)} <span class="${cls}">${esc(label)}</span>${w.unread ? `<span class="unread" title="没看的消息">${esc(w.unread)}</span>` : ''}</h3>` +
      `<div class="clock">${esc(worldClock(w))}</div><div class="cast">${w.residents.slice(0, 10).map((r, i) => `<span class="avatar tint-${i % 8}" title="${esc(r.name)}">${esc(initial(r.name))}</span>`).join('')}</div></div>` +
      `<div class="lastline">${last}</div><div class="world-foot">今天 ${esc(w.usage.calls)}/${esc(w.settings.dailyCalls)} 次请求<div class="meter"><i data-width="${pct}"></i></div>` +
      `<a class="btn quiet" href="#worlds/${esc(w.id)}">打开</a></div></article>`;
  }).join('') : '<p class="empty">还没有世界。手机 App 可以把剧本“做成小世界”，这里也可以点“新建世界”粘贴世界 JSON。</p>';
  setWidths($('world-list'));
}
$('world-list').addEventListener('click', (event) => {
  const card = event.target.closest('[data-world]');
  if (card && !event.target.closest('a, button')) location.hash = `#worlds/${card.dataset.world}`;
});

const world = { id: null, items: [], next: 0, info: null, tab: 'all', models: null };
const residentIndex = (id) => world.info?.definition.residents.findIndex((r) => r.id === id) ?? -1;
const residentName = (id) => world.info?.definition.residents.find((r) => r.id === id)?.name || id;

function drawWorldInfo() {
  const w = world.info;
  const [label, cls] = worldTag(w);
  $('world-name').textContent = w.name;
  $('world-state').textContent = label;
  $('world-state').className = cls;
  $('world-pause').textContent = w.paused ? '恢复' : '暂停';
  const tokens = (w.usage.prompt_tokens || 0) + (w.usage.completion_tokens || 0);
  const facts = [
    ['世界时间', w.world_time_label],
    ['下次推进', w.paused ? '暂停中' : w.next_tick_at ? when(w.next_tick_at) : HOLDS[w.hold] || '—'],
    ['推进间隔', `${w.interval_minutes} 分钟${w.interval_minutes > w.settings.tickMinutes ? '（你不在，放慢了）' : ''}`],
    ['今天推进', `${w.usage.ticks} / ${w.settings.dailyTicks} 次`],
    ['今天请求', `${w.usage.calls} / ${w.settings.dailyCalls} 次`],
    ['今天 tokens', wan(tokens)],
    ['累计', `${w.totals.calls} 次，${wan((w.totals.prompt_tokens || 0) + (w.totals.completion_tokens || 0))} tokens`],
    ['模型', w.model === 'pocket-direct' ? `默认模型（${modelLabel(defaultModel())}）` : modelLabel(w.model)],
  ];
  $('world-facts').innerHTML = facts.map(([k, v]) => `<div><dt>${esc(k)}</dt><dd>${esc(v)}</dd></div>`).join('');
  $('world-error').hidden = !w.last_error;
  $('world-error').textContent = w.last_error ? `上次出错（${when(w.last_error.at)}）：${w.last_error.message}` : '';
  $('world-residents').innerHTML = w.residents.map((r, i) => `<div class="row person"><span class="avatar tint-${i % 8}" aria-hidden="true">${esc(initial(r.name))}</span>` +
    `<div><div class="what">${esc(r.name)}${r.mood ? ` <span class="muted">· ${esc(r.mood)}</span>` : ''}</div><div class="detail">${esc([r.location && `在${r.location}`, r.activity].filter(Boolean).join('，') || '还没有动静')}${r.plan ? `<br>打算：${esc(r.plan)}` : ''}</div></div></div>`).join('');
}

function drawTabs() {
  const def = world.info.definition;
  const tabs = [['all', '全部'], ['group', `群聊「${def.groupName}」`], ...def.residents.map((r) => [`dm:${r.id}`, `私信 · ${r.name}`]), ['event', '事件']];
  $('world-tabs').innerHTML = tabs.map(([key, label]) => {
    const count = key.startsWith('dm:') ? world.items.filter((item) => item.channel === key).length : 0;
    return `<button class="chip" type="button" data-tab="${esc(key)}" aria-pressed="${world.tab === key}">${esc(label)}${count ? `（${count}）` : ''}</button>`;
  }).join('');
  const channels = [['group', `群聊「${def.groupName}」`], ...def.residents.map((r) => [`dm:${r.id}`, `私信 ${r.name}`])];
  const chosen = world.tab.startsWith('dm:') || world.tab === 'group' ? world.tab : $('world-channel').value || 'group';
  $('world-channel').innerHTML = channels.map(([key, label]) => `<option value="${esc(key)}"${key === chosen ? ' selected' : ''}>${esc(label)}</option>`).join('');
}
$('world-tabs').addEventListener('click', (event) => {
  const tab = event.target.closest('[data-tab]');
  if (!tab) return;
  world.tab = tab.dataset.tab;
  drawTabs(); drawTimeline(true);
});

function drawTimeline(jump = false) {
  const box = $('world-timeline');
  const atBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 40;
  const shown = world.items.filter((item) => world.tab === 'all' || (world.tab === 'event' ? item.kind === 'event' : item.channel === world.tab));
  box.innerHTML = shown.length ? shown.map((item) => {
    if (item.kind === 'event') {
      const who = item.who?.length ? `（${item.who.map(residentName).join('、')}）` : '';
      return `<div class="event">${esc(item.time)} ${esc(item.text)}${esc(who)}</div>`;
    }
    const mine = item.from === 'user';
    const index = residentIndex(item.from);
    const where = world.tab === 'all' && item.channel !== 'group' ? `<span class="where">私信 · ${esc(residentName(item.channel.slice(3)))}</span>` : '';
    return `<div class="line${mine ? ' mine' : ''}"><span class="avatar ${mine ? 'me' : `tint-${(index < 0 ? 7 : index) % 8}`}" aria-hidden="true">${esc(initial(item.name))}</span>` +
      `<div><div><span class="who">${esc(mine ? `${item.name}（你）` : item.name)}</span><span class="meta">${esc(item.time)}</span>${where}</div><div class="say">${esc(item.text)}</div></div></div>`;
  }).join('') : '<p class="empty">这里还没有内容。</p>';
  if (jump || atBottom) box.scrollTop = box.scrollHeight;
}

async function drawWorldSettings() {
  const w = world.info;
  if (!data.models) await loadModels().catch(() => null);
  const models = [['pocket-direct', `默认模型（现在是 ${modelLabel(defaultModel())}）`], ...(data.models || []).map((m) => [m.id, modelName(m)])];
  if (!models.some(([id]) => id === w.model)) models.splice(1, 0, [w.model, w.model]);
  const select = (name, options, value) => `<select name="${name}">${options.map(([v, label]) => `<option value="${esc(v)}"${String(v) === String(value) ? ' selected' : ''}>${esc(label)}</option>`).join('')}</select>`;
  const s = w.settings;
  $('world-settings').innerHTML = [
    ['推进间隔', '世界多久往前走一步', `${select('tickMinutes', [15, 20, 30, 45, 60, 90, 120].map((m) => [m, `${m} 分钟`]), s.tickMinutes)}`],
    ['安静时段', '居民睡觉的时间，不推进；两个时间相同表示没有', `<input type="time" name="quietStart" value="${esc(s.quietStart)}"> 到 <input type="time" name="quietEnd" value="${esc(s.quietEnd)}">`],
    ['每天最多推进', '按现实里的一天算', `<input type="number" name="dailyTicks" min="0" max="200" value="${esc(s.dailyTicks)}"> 次`],
    ['每天最多请求', '推进、回应你、写日记都算', `<input type="number" name="dailyCalls" min="1" max="400" value="${esc(s.dailyCalls)}"> 次`],
    ['每天最多主动私信', '居民先私信你的次数，按世界里的一天算；回你的不算；0 = 从不先私信', `<input type="number" name="dailyDms" min="0" max="30" value="${esc(s.dailyDms ?? 6)}"> 次`],
    ['你不在时放慢', '超过一天没看，推进间隔自动拉长', `<label class="check"><input type="checkbox" name="slowWhenAway"${s.slowWhenAway ? ' checked' : ''}> 放慢</label>`],
    ['模型', '这个世界用哪个模型', select('model', models, w.model)],
  ].map(([label, hint, control]) => `<div class="label"><b>${esc(label)}</b><span>${esc(hint)}</span></div><div class="control">${control}</div>`).join('') +
    '<div class="foot"><button class="btn primary" type="submit">保存</button><span id="world-settings-note" class="muted"></span></div>';
}
$('world-settings').addEventListener('submit', (event) => {
  event.preventDefault();
  const form = event.target;
  const settings = {
    tickMinutes: Number(form.tickMinutes.value), quietStart: form.quietStart.value, quietEnd: form.quietEnd.value,
    dailyTicks: Number(form.dailyTicks.value), dailyCalls: Number(form.dailyCalls.value), slowWhenAway: form.slowWhenAway.checked,
    dailyDms: Number(form.dailyDms.value),
  };
  busy(form.querySelector('button[type="submit"]'), async () => {
    world.info = await send(`/api/v1/pocket/worlds/${world.id}`, 'PATCH', { settings, model: form.model.value });
    drawWorldInfo();
    toast('小世界设置已保存');
  });
});

async function loadDiary() {
  const { entries, residents } = await api(`/api/v1/pocket/worlds/${world.id}/diary`);
  const notes = residents.filter((r) => r.notes).map((r) => `<div class="chapter"><b>${esc(r.name)}记得的事</b>${esc(r.notes)}</div>`).join('');
  const chapters = [...entries].reverse().map((e) => `<div class="chapter"><b>${esc(e.day)}</b>${esc(e.text)}</div>`).join('');
  $('world-diary').innerHTML = notes + chapters || '<p class="empty">世界里每过一天写一章日记，同时更新每个人记得的事。</p>';
}

async function openWorld(id) {
  $('worlds-home').hidden = true;
  $('world-detail').hidden = false;
  if (world.id === id && world.info) return refreshWorld();
  world.id = id; world.items = []; world.next = 0; world.tab = 'all';
  $('world-name').textContent = '读取中…';
  $('world-timeline').innerHTML = '';
  try {
    world.info = await api(`/api/v1/pocket/worlds/${id}`);
  } catch (error) {
    toast(error.message, true);
    location.hash = '#worlds';
    return undefined;
  }
  const first = await api(`/api/v1/pocket/worlds/${id}/messages?tail=300&seen=1`);
  world.items = first.items;
  world.next = first.next;
  drawWorldInfo(); drawTabs(); drawTimeline(true);
  await Promise.all([drawWorldSettings(), loadDiary()]);
  safely(loadWorldList);
  return undefined;
}
function closeWorld() {
  world.id = null; world.info = null;
  $('world-detail').hidden = true;
  $('worlds-home').hidden = false;
}

async function refreshWorld() {
  if (!world.id || !world.info) return;
  const update = await api(`/api/v1/pocket/worlds/${world.id}/messages?since=${world.next}&seen=1`);
  const known = new Set(world.items.map((item) => item.seq));
  const fresh = update.items.filter((item) => !known.has(item.seq));
  world.items.push(...fresh);
  world.next = update.next;
  world.info = { ...world.info, ...update.world };
  drawWorldInfo();
  if (fresh.length) { drawTabs(); drawTimeline(); safely(loadDiary); }
}

$('world-say').addEventListener('submit', (event) => {
  event.preventDefault();
  const text = $('world-text').value.trim();
  if (!text || !world.id) return;
  $('world-say-note').textContent = '等她们回应…（一次付费请求）';
  busy(event.target.querySelector('button'), async () => {
    try {
      await send(`/api/v1/pocket/worlds/${world.id}/messages`, 'POST', { channel: $('world-channel').value, text });
      $('world-text').value = '';
    } finally { $('world-say-note').textContent = ''; }
    await refreshWorld();
  });
});
$('world-pause').addEventListener('click', (event) => busy(event.currentTarget, async () => {
  world.info = { ...world.info, ...await send(`/api/v1/pocket/worlds/${world.id}/${world.info.paused ? 'resume' : 'pause'}`, 'POST') };
  drawWorldInfo();
  toast(world.info.paused ? '已暂停，世界的时间停住了' : '已恢复运行');
  safely(loadWorldList);
}));
$('world-tick').addEventListener('click', (event) => {
  if (!confirm('现在推进一次？这是一次付费请求，计入今天的上限。')) return;
  busy(event.currentTarget, async () => {
    const result = await send(`/api/v1/pocket/worlds/${world.id}/tick`, 'POST');
    if (result.error) toast(result.error, true); else toast('推进了一次');
    await refreshWorld();
    safely(loadWorldList);
  });
});
$('world-delete').addEventListener('click', (event) => {
  if (!confirm(`删除世界「${world.info.name}」？所有聊天、事件和日记都会删掉，无法恢复。`)) return;
  busy(event.currentTarget, async () => {
    await send(`/api/v1/pocket/worlds/${world.id}`, 'DELETE');
    toast(`已删除「${world.info.name}」`);
    location.hash = '#worlds';
  });
});
$('world-new').addEventListener('click', () => { $('world-create-note').hidden = true; $('world-dialog').showModal(); $('world-pack').focus(); });
$('world-cancel').addEventListener('click', () => $('world-dialog').close());
$('world-form').addEventListener('submit', (event) => {
  event.preventDefault();
  const note = $('world-create-note');
  let pack;
  try { pack = JSON.parse($('world-pack').value); } catch { note.hidden = false; note.textContent = '不是有效的 JSON'; return; }
  if (pack && typeof pack === 'object' && pack.startPaused === undefined) pack.startPaused = $('world-start-paused').checked;
  busy(event.target.querySelector('button[type="submit"]'), async () => {
    try {
      const created = await send('/api/v1/pocket/worlds', 'POST', pack);
      $('world-pack').value = '';
      $('world-dialog').close();
      toast(created.paused ? `已创建「${created.name}」，暂停中：点“恢复”后开始推进` : `已创建「${created.name}」，大约一分钟后第一次推进`);
      location.hash = `#worlds/${created.id}`;
    } catch (error) { note.hidden = false; note.textContent = `创建失败：${error.message}`; }
  });
});

async function loadWorldsPage() {
  await loadWorldList();
  drawWorldList();
}

// ---- 手机
let tokenShown = false;
function drawConnection() {
  const c = data.connection;
  const s = data.status;
  const masked = `${c.token.slice(0, 4)}${'•'.repeat(12)}${c.token.slice(-4)}`;
  const copy = (key) => `<button class="btn quiet" type="button" data-copy="${key}">复制</button>`;
  const phone = s?.phone ? `${esc(ago(s.phone.at))}，${s.phone.via === 'lan' ? `走局域网（${esc(s.phone.address)}）` : '走远程地址'}` : '这次启动后还没连过';
  $('connection').innerHTML = [
    ['在家', c.lan_url ? `<span>${esc(c.lan_url)}</span>${copy('lan_url')}` : '<span class="muted">没有开局域网模式，手机只能走远程地址</span>'],
    ['在外面', `<span id="public-view">${c.public_url ? esc(c.public_url) : '<span class="muted">还没填</span>'}</span>${c.public_url ? copy('public_url') : ''}<button class="btn quiet" type="button" id="public-edit">修改</button>` +
      `<form id="public-form" class="say-form" hidden><input id="public-url" type="url" placeholder="https://你的域名/v1" value="${esc(c.public_url || '')}"><button class="btn primary" type="submit">保存</button><button class="btn" type="button" id="public-cancel">取消</button></form>`],
    ['本机', `<span>${esc(c.local_url)}</span>`],
    ['网关 Token', `<span class="secret" id="token">${esc(tokenShown ? c.token : masked)}</span><button class="btn quiet" type="button" id="token-show">${tokenShown ? '隐藏' : '显示'}</button>${copy('token')}`],
    ['上次连接', phone],
    ['私密目录', `<span>${esc(c.data_dir)}</span>`],
  ].map(([k, v]) => `<dt>${esc(k)}</dt><dd>${v}</dd>`).join('');
}
$('connection').addEventListener('click', async (event) => {
  const target = event.target;
  const c = data.connection;
  if (target.dataset.copy) {
    await navigator.clipboard.writeText(c[target.dataset.copy]);
    toast(target.dataset.copy === 'token' ? '网关 Token 已复制' : '地址已复制');
  } else if (target.id === 'token-show') {
    tokenShown = !tokenShown;
    drawConnection();
  } else if (target.id === 'public-edit') {
    $('public-form').hidden = false;
    $('public-edit').hidden = true;
    $('public-url').focus();
  } else if (target.id === 'public-cancel') drawConnection();
});
$('connection').addEventListener('submit', (event) => {
  event.preventDefault();
  busy(event.target.querySelector('button[type="submit"]'), async () => {
    data.connection = await send('/api/admin/connection', 'PATCH', { publicBaseUrl: $('public-url').value });
    drawConnection();
    toast('远程地址已保存');
  });
});

async function loadBackups() {
  const { data: list } = await api('/api/v1/backups');
  const keep = data.config?.settings?.backupKeep ?? data.status?.backups?.keep;
  $('backup-keep').textContent = keep ? `保留最近 ${keep} 份，在“设置”里改` : '';
  $('backup-list').innerHTML = list.length ? list.map((b, i) => row(i === 0 ? '' : 'off', esc(when(b.created_at)),
    esc([REASONS[b.reason] || b.reason, size(b.size), b.app_version && `App ${b.app_version}`, b.device].filter(Boolean).join('，')),
    `<a class="btn quiet" href="/api/v1/backups/${esc(b.id)}" download>下载</a><button class="btn quiet danger" type="button" data-delete="${esc(b.id)}">删除</button>`)).join('')
    : '<p class="empty">还没有手机备份。手机“设置 → 备份”可以备份到这台电脑。</p>';
}
$('backup-list').addEventListener('click', (event) => {
  const button = event.target.closest('[data-delete]');
  if (!button || !confirm('删除这份备份？删除后无法恢复。')) return;
  busy(button, async () => { await send(`/api/v1/backups/${button.dataset.delete}`, 'DELETE'); toast('备份已删除'); await loadBackups(); safely(loadStatus); });
});

async function loadStickers() {
  const { packs } = await api('/api/v1/stickers');
  $('sticker-list').innerHTML = packs.length ? packs.map((p) => `<div class="row"><span class="state${p.enabled ? '' : ' off'}"></span><div><div class="what">${esc(p.name)}</div>` +
    `<div class="detail">${esc(p.stickers.length)} 张，许可：${esc(p.license || '未声明')}</div>` +
    `<div class="thumbs">${p.stickers.slice(0, 12).map((s) => `<img loading="lazy" alt="${esc(s.key)}" title="${esc(s.key)}${s.description ? `：${esc(s.description)}` : ''}" src="/api/v1/stickers/files/${esc(s.file)}">`).join('')}</div></div>` +
    `<div class="end"><label class="check"><input type="checkbox" data-toggle="${esc(p.id)}"${p.enabled ? ' checked' : ''}> 启用</label><button class="btn quiet danger" type="button" data-delete="${esc(p.id)}">删除</button></div></div>`).join('')
    : '<p class="empty">还没有表情包。在手机“设置 → 表情包”导入，图片会存到这台电脑上。</p>';
}
$('sticker-list').addEventListener('change', (event) => {
  const box = event.target.closest('[data-toggle]');
  if (box) busy(box, async () => { await send(`/api/v1/stickers/packs/${box.dataset.toggle}`, 'PATCH', { enabled: box.checked }); await loadStickers(); safely(loadStatus); });
});
$('sticker-list').addEventListener('click', (event) => {
  const button = event.target.closest('[data-delete]');
  if (!button || !confirm('从电脑上删除这个表情包和它的图片？')) return;
  busy(button, async () => { await send(`/api/v1/stickers/packs/${button.dataset.delete}`, 'DELETE'); toast('表情包已删除'); await loadStickers(); safely(loadStatus); });
});

async function loadPhonePage() {
  await Promise.all([loadConnection(), data.config ? null : loadConfig().catch(() => null)]);
  drawConnection();
  await Promise.all([loadBackups(), loadStickers()]);
}

// ---- 联网
function drawReach() {
  const r = data.status?.reach;
  if (!r) return;
  const channels = Object.entries(r.channels || {}).map(([c, a]) => `${c}（${a.join('/')}）`).join('，');
  $('reach-list').innerHTML = row(r.available ? '' : 'bad', r.available ? '联网搜索可以用' : '联网搜索不能用', r.available ? 'Agent Reach 在这台电脑上运行' : esc(r.error || '没有找到 Agent Reach'), r.version ? `版本 ${esc(r.version)}` : '') +
    (channels ? row('off', '能用的渠道', esc(channels)) : '');
}

let biliTimer = null;
function biliState(text, state) { $('bili-state').textContent = text; $('bili-dot').className = `state ${state}`; }
async function loadBili() {
  const s = await api('/api/admin/bili');
  if (s.logged_in) biliState(`已登录${s.name ? `：${s.name}` : ''}${s.valid === null ? '（暂时无法向 B 站确认）' : ''}`, '');
  else biliState(s.valid === false ? '登录已失效，请重新扫码' : '没登录：模型只能看到标题和简介，读不到字幕', s.valid === false ? 'warn' : 'off');
  $('bili-logout').hidden = !s.logged_in;
  $('bili-login').textContent = s.logged_in ? '重新扫码' : '扫码登录';
}
async function pollBili() {
  try {
    const r = await api('/api/admin/bili/poll', { method: 'POST' });
    if (r.state === 'scanned') biliState('已扫码，请在手机上确认登录', 'warn');
    if (r.state === 'waiting' || r.state === 'scanned') { biliTimer = setTimeout(pollBili, 2000); return; }
    $('bili-qr').hidden = true;
    $('bili-login').disabled = false;
    if (r.state === 'expired') biliState('二维码过期了，请重新扫码', 'warn');
    else { await loadBili(); toast('B 站登录好了'); }
  } catch (error) {
    $('bili-qr').hidden = true;
    $('bili-login').disabled = false;
    biliState(`登录失败：${error.message}`, 'bad');
  }
}
$('bili-login').addEventListener('click', async () => {
  clearTimeout(biliTimer);
  $('bili-login').disabled = true;
  try {
    await api('/api/admin/bili/login', { method: 'POST' });
    $('bili-qr').src = `/api/admin/bili/qr.png?t=${Date.now()}`;
    $('bili-qr').hidden = false;
    biliState('用 B 站 App 扫描下面的二维码（3 分钟内有效）', 'warn');
    biliTimer = setTimeout(pollBili, 2000);
  } catch (error) {
    $('bili-login').disabled = false;
    biliState(`生成二维码失败：${error.message}`, 'bad');
  }
});
$('bili-logout').addEventListener('click', (event) => {
  if (!confirm('退出 B 站登录？之后模型读不到字幕。')) return;
  busy(event.currentTarget, async () => { await api('/api/admin/bili/login', { method: 'DELETE' }); await loadBili(); });
});

async function loadSocial() {
  const s = await api('/api/admin/social');
  const rows = [];
  if (!s.available) rows.push(row('off', '还不能用', '没有找到 OpenCLI 或 Chrome'));
  if (s.mode === 'window') rows.push(row('warn', '登录窗口开着', '在窗口里登录，都登录好了点“登录完成”', '<button class="btn primary" type="button" id="social-done">登录完成</button>'));
  for (const [key, p] of Object.entries(s.platforms || {})) {
    const state = !p.logged_in ? 'off' : p.blocked_at ? 'warn' : '';
    const detail = !p.logged_in ? '没登录' : p.blocked_at ? '已登录，但上次查询要求验证' : '已登录';
    const buttons = [p.logged_in && p.blocked_at && p.verify ? `<button class="btn quiet" type="button" data-social-verify="${esc(key)}">去验证</button>` : '',
      `<button class="btn quiet" type="button" data-social-login="${esc(key)}">${p.logged_in ? '重新登录' : '登录'}</button>`,
      p.logged_in ? `<button class="btn quiet danger" type="button" data-social-logout="${esc(key)}">退出</button>` : ''].join('');
    rows.push(row(state, esc(p.name), detail, buttons));
  }
  $('social-list').innerHTML = rows.join('') || '<p class="empty">没有可登录的平台。</p>';
  $('social-state').textContent = s.mode === 'headless' ? '正在后台查询。' : '';
  $('social-kept').textContent = [s.keepalive ? `上次保活：${when(s.keepalive.at)}${s.keepalive.lost?.length ? `，掉线：${s.keepalive.lost.join('、')}` : ''}` : '还没有保活过（服务启动 3 分钟后第一次，之后每 8 小时一次）',
    s.cookies_saved_at ? `登录信息备份于 ${when(s.cookies_saved_at)}` : ''].filter(Boolean).join('；');
}
$('social-list').addEventListener('click', (event) => {
  const login = event.target.closest('[data-social-login]');
  const verify = event.target.closest('[data-social-verify]');
  const logout = event.target.closest('[data-social-logout]');
  const done = event.target.closest('#social-done');
  const button = login || verify || logout || done;
  if (!button) return;
  if (logout && !confirm('退出这个平台的登录？')) return;
  busy(button, async () => {
    if (login) await api(`/api/admin/social/login?platform=${encodeURIComponent(login.dataset.socialLogin)}`, { method: 'POST' });
    if (verify) await api(`/api/admin/social/login?platform=${encodeURIComponent(verify.dataset.socialVerify)}&verify=1`, { method: 'POST' });
    if (logout) await api(`/api/admin/social/login?platform=${encodeURIComponent(logout.dataset.socialLogout)}`, { method: 'DELETE' });
    if (done) { await api('/api/admin/social/done', { method: 'POST' }); toast('登录信息已保存'); }
    await loadSocial();
  });
});
$('social-keep').addEventListener('click', (event) => busy(event.currentTarget, async () => {
  const r = await api('/api/admin/social/keepalive', { method: 'POST' });
  if (r.skipped) toast(r.skipped === 'window' ? '登录窗口开着，先点“登录完成”' : r.skipped === 'busy' ? '有查询正在进行，稍后再试' : '还没有登录任何平台', true);
  else toast('保活完成');
  await loadSocial();
}));

async function loadReachPage() {
  drawReach();
  await Promise.all([loadBili(), loadSocial()]);
}

// ---- 记录
const runFilter = { kind: 'all', failed: false, model: 'all', selected: null };
function drawRunFilters() {
  const runs = data.runs || [];
  const models = [...new Set(runs.map((r) => r.model))];
  const chip = (key, label, on) => `<button class="chip" type="button" data-run-chip="${esc(key)}" aria-pressed="${on}">${esc(label)}</button>`;
  $('run-filters').innerHTML = [chip('kind:all', '全部', runFilter.kind === 'all'), ...Object.entries(KINDS).map(([k, label]) => chip(`kind:${k}`, label, runFilter.kind === k)),
    chip('failed', '只看失败', runFilter.failed),
    `<select id="run-model" aria-label="按模型筛选"><option value="all">全部模型</option>${models.map((m) => `<option value="${esc(m)}"${runFilter.model === m ? ' selected' : ''}>${esc(modelLabel(m))}</option>`).join('')}</select>`].join('');
}
$('run-filters').addEventListener('click', (event) => {
  const chip = event.target.closest('[data-run-chip]');
  if (!chip) return;
  const key = chip.dataset.runChip;
  if (key.startsWith('kind:')) runFilter.kind = key.slice(5); else runFilter.failed = !runFilter.failed;
  drawRunFilters(); drawRuns();
});
$('run-filters').addEventListener('change', (event) => { if (event.target.id === 'run-model') { runFilter.model = event.target.value; drawRuns(); } });

function drawRuns() {
  const runs = (data.runs || []).filter((r) => (runFilter.kind === 'all' || r.kind === runFilter.kind) && (!runFilter.failed || r.status === 'failed') && (runFilter.model === 'all' || r.model === runFilter.model));
  if (!data.runs?.length) { $('runs-table').innerHTML = '<tr><td class="muted">还没有请求记录。</td></tr>'; return; }
  $('runs-table').innerHTML = '<thead><tr><th>时间</th><th>用途</th><th>模型</th><th>状态</th><th class="col-opt">耗时</th><th>tokens</th><th class="col-opt">备注</th></tr></thead><tbody>' + (runs.length ? runs.map((r) =>
    `<tr class="clickable${runFilter.selected === r.run_id ? ' selected' : ''}" data-run="${esc(r.run_id)}"><td class="num">${esc(when(r.created_at))}</td>` +
    `<td class="num"><span class="kind-dot k-${esc(r.kind || 'chat')}"></span>${esc(r.kind === 'world' ? `小世界「${r.world}」` : r.kind === 'memory' ? PURPOSES[r.purpose] || r.purpose : KINDS.chat)}</td>` +
    `<td class="num">${esc(modelLabel(r.model))}</td><td class="num status-${esc(r.status)}">${esc(STATUS[r.status] || r.status)}</td>` +
    `<td class="num col-opt">${r.duration_ms == null ? '—' : esc(took(r.duration_ms))}</td><td class="num">${tokensOf(r) ? esc(tokensOf(r).toLocaleString()) : '—'}</td>` +
    `<td class="col-opt">${esc(r.error || r.warning || '')}</td></tr>`).join('') : '<tr><td colspan="7" class="muted">没有符合条件的记录。</td></tr>') + '</tbody>';
}
$('runs-table').addEventListener('click', (event) => {
  const tr = event.target.closest('[data-run]');
  if (tr) location.hash = `#records/${tr.dataset.run}`;
});
async function showRun(runId) {
  const run = (data.runs || []).find((r) => r.run_id === runId);
  runFilter.selected = run ? runId : null;
  drawRuns();
  $('records').classList.toggle('with-detail', Boolean(run));
  $('run-detail').hidden = !run;
  if (!run) return;
  $('run-title').textContent = `${when(run.created_at)} · ${runTitle(run)}`;
  $('run-text').textContent = '读取中…';
  try { $('run-text').textContent = await api(`/api/admin/runs/${run.chat_hash}/${run.run_id}`); } catch (error) { $('run-text').textContent = error.message; }
}
$('run-close').addEventListener('click', () => { location.hash = '#records'; });
$('runs-refresh').addEventListener('click', (event) => busy(event.currentTarget, async () => { await loadRuns(); drawRunFilters(); drawRuns(); }));

async function loadLogs() {
  const { server, supervisor } = await api('/api/admin/logs?lines=200');
  $('log-view').textContent = [...server, ...(supervisor.length ? ['', '— 崩溃重启记录 —', ...supervisor] : [])].join('\n') || '还没有日志。';
  $('log-view').scrollTop = $('log-view').scrollHeight;
}
$('logs-refresh').addEventListener('click', (event) => busy(event.currentTarget, loadLogs));

async function loadRecordsPage(runId) {
  await Promise.all([loadRuns(), data.models ? null : loadModels().catch(() => null)]);
  drawRunFilters();
  await showRun(runId);
  safely(loadLogs);
}

// ---- 设置
async function loadSettingsPage() {
  await Promise.all([loadConfig(), data.models ? null : loadModels().catch(() => null), data.sources ? null : loadSources().catch(() => null)]);
  const settings = data.config.settings;
  const option = (value, label, chosen) => `<option value="${esc(value)}"${String(value) === String(chosen) ? ' selected' : ''}>${esc(label)}</option>`;
  const select = (name, options, value) => `<select name="${name}">${options.map(([v, label]) => option(v, label, value)).join('')}</select>`;
  const groups = new Map();
  for (const m of data.models || []) {
    const name = m.pocket_source?.name || '模型';
    if (!groups.has(name)) groups.set(name, []);
    groups.get(name).push(m);
  }
  const known = modelIndex.has(settings.upstreamModel);
  const modelSelect = `<select name="upstreamModel">${known ? '' : option(settings.upstreamModel, settings.upstreamModel, settings.upstreamModel)}${[...groups].map(([name, list]) =>
    `<optgroup label="${esc(name)}">${list.map((m) => option(m.id, modelName(m), settings.upstreamModel)).join('')}</optgroup>`).join('')}</select>`;
  const waits = [300, 900, 1800, 3600, 0];
  if (!waits.includes(settings.upstreamIdleTimeoutSec)) waits.push(settings.upstreamIdleTimeoutSec);
  $('settings-form').innerHTML = [
    ['默认模型', '手机选“电脑默认”时用它；小世界默认也用它。也可以在“模型”页点“设为默认”', modelSelect],
    ['思考强度', '模型支持思考时传给它；“默认”就是不传', select('reasoningEffort', Object.entries(EFFORTS), settings.reasoningEffort)],
    ['审校 Skill', '写完后再让模型按这个 Skill 检查一遍', select('reviewSkill', [['none', '不用'], ['ss-stop-slop', 'stop-slop 去 AI 味']], settings.reviewSkill)],
    ['审校思考强度', '审校那次请求的思考强度', select('reviewReasoningEffort', Object.entries(EFFORTS), settings.reviewReasoningEffort)],
    ['按审校意见自动改正文', '试验功能：审校发现问题时自动改一遍，多一次请求', `<label class="check"><input type="checkbox" name="autoRevise"${settings.autoRevise ? ' checked' : ''}> 自动改</label>`],
    ['附带 Venice 自带的系统提示', '只对 Venice 的模型有效', `<label class="check"><input type="checkbox" name="includeVeniceSystemPrompt"${settings.includeVeniceSystemPrompt ? ' checked' : ''}> 附带</label>`],
    ['上游最长等待', '模型这么久没有任何输出就算失败', select('upstreamIdleTimeoutSec', waits.map((w) => [w, minutes(w)]), settings.upstreamIdleTimeoutSec)],
    ['电脑保留备份', '手机备份到这台电脑时只留最近这么多份', `<input type="number" name="backupKeep" min="1" max="200" value="${esc(settings.backupKeep)}"> 份`],
  ].map(([label, hint, control]) => `<div class="label"><b>${esc(label)}</b><span>${esc(hint)}</span></div><div class="control">${control}</div>`).join('') +
    '<div class="foot"><button class="btn primary" type="submit">保存设置</button><span class="muted">上游地址和密钥在“模型”页的来源里改</span></div>';
  drawAbout();
}
$('settings-form').addEventListener('submit', (event) => {
  event.preventDefault();
  const form = event.target;
  const settings = data.config.settings;
  const edited = {
    upstreamModel: form.upstreamModel.value, reasoningEffort: form.reasoningEffort.value, reviewSkill: form.reviewSkill.value,
    reviewReasoningEffort: form.reviewReasoningEffort.value, autoRevise: form.autoRevise.checked,
    includeVeniceSystemPrompt: form.includeVeniceSystemPrompt.checked, upstreamIdleTimeoutSec: Number(form.upstreamIdleTimeoutSec.value),
    backupKeep: Number(form.backupKeep.value),
  };
  const changes = Object.fromEntries(Object.entries(edited).filter(([key, value]) => value !== settings[key]));
  if (!Object.keys(changes).length) { toast('没有改动'); return; }
  busy(form.querySelector('button[type="submit"]'), async () => {
    await send('/api/v1/pocket/config', 'PATCH', { settings: changes });
    toast('设置已保存，立即生效');
    await loadSettingsPage();
    safely(loadStatus);
  });
});
function drawAbout() {
  const s = data.status;
  if (!s) return;
  $('dsh-list').innerHTML = row(s.dsh?.available ? '' : 'off', s.dsh?.available ? '可以用' : '不能用', s.dsh?.available ? '手机上用电脑写作服务、勾选 DSH 成人互动的会话会通过这里运行引擎；存档在私密目录的 dsh\\data。' : esc(s.dsh?.error || ''),
    [s.dsh?.python && `Python ${s.dsh.python}`, s.dsh?.commit && `引擎 ${s.dsh.commit.slice(0, 10)}`].filter(Boolean).map(esc).join('<br>'));
  $('about-list').innerHTML = [
    row('', `版本 ${esc(s.version)}`, `已连续运行 ${esc(lasting(s.uptime_seconds))}，启动于 ${esc(when(s.started_at))}`),
    row('off', '监听', esc((s.listen?.hosts || []).map((h) => `${h}:${s.listen.port}`).join('，'))),
    row('off', '审校', esc(s.review_skill === 'none' ? '不用' : s.review_skill)),
    row('off', '正在处理的请求', esc(String(s.active_requests ?? 0))),
  ].join('');
}

// ---- pages
const PAGES = {
  overview: () => loadOverview(),
  models: () => loadModelsPage(),
  worlds: (arg) => (arg ? openWorld(arg) : (closeWorld(), loadWorldsPage())),
  phone: () => loadPhonePage(),
  reach: () => loadReachPage(),
  records: (arg, fresh) => (fresh || !data.runs ? loadRecordsPage(arg) : showRun(arg)),
  settings: () => loadSettingsPage(),
};
let current = '';
function route() {
  const [name, arg] = location.hash.slice(1).split('/');
  const page = PAGES[name] ? name : 'overview';
  const fresh = page !== current;
  if (fresh) {
    document.querySelectorAll('.nav a').forEach((a) => (a.dataset.page === page ? a.setAttribute('aria-current', 'page') : a.removeAttribute('aria-current')));
    document.querySelectorAll('.page').forEach((p) => p.classList.toggle('on', p.id === `page-${page}`));
    window.scrollTo(0, 0);
    current = page;
  }
  safely(() => PAGES[page](arg ? decodeURIComponent(arg) : '', fresh));
}
window.addEventListener('hashchange', route);

// Status first so every page can read it; the visible page refreshes itself while the tab is shown.
safely(async () => { await Promise.all([loadStatus(), loadConnection().catch(() => null)]); route(); safely(loadStatus); });
const visible = () => document.visibilityState === 'visible';
setInterval(() => { if (visible()) safely(loadStatus); }, 5000);
setInterval(() => {
  if (!visible()) return;
  if (current === 'overview') safely(loadOverview);
  if (current === 'worlds') safely(world.id ? refreshWorld : loadWorldsPage);
  if (current === 'records') safely(async () => { await loadRuns(); drawRunFilters(); drawRuns(); });
  if (current === 'settings') drawAbout();
  if (current === 'reach') drawReach();
}, 15000);
