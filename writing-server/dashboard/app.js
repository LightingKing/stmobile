// PC dashboard. Every text from the server goes through esc() before it reaches the page.
const $ = (id) => document.getElementById(id);
const esc = (value) => String(value ?? '').replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
const size = (bytes) => bytes >= 1048576 ? `${(bytes / 1048576).toFixed(1)} MB` : bytes >= 1024 ? `${Math.round(bytes / 1024)} KB` : `${bytes || 0} B`;
const time = (iso) => { const d = new Date(iso); return Number.isNaN(d.getTime()) ? '—' : d.toLocaleString('zh-CN', { hour12: false }); };
const minutes = (seconds) => seconds === 0 ? '不限' : seconds % 60 === 0 ? `${seconds / 60} 分钟` : `${seconds} 秒`;
const PURPOSES = { chat: '聊天', summary: '摘要', compact: '压缩', rebuild: '重建记忆' };
const STATUS = { complete: '完成', failed: '失败', running: '进行中' };
const REASONS = { manual: '手动', auto: '自动', 'pre-restore': '恢复前自动保存' };
const EFFORTS = { default: '默认（不传）', none: '关闭', low: '低', medium: '中', high: '高' };

async function api(path, options = {}) {
  const write = options.method && options.method !== 'GET';
  const response = await fetch(path, { ...options, headers: { ...(options.body ? { 'content-type': 'application/json' } : {}), ...(write ? { 'x-pocket-admin': '1' } : {}), ...(options.headers || {}) } });
  const type = response.headers.get('content-type') || '';
  const body = type.includes('json') ? await response.json() : await response.text();
  if (!response.ok) throw new Error(body?.error?.message || `HTTP ${response.status}`);
  return body;
}

function tile(label, value) { return `<div class="tile"><div class="label">${esc(label)}</div><div class="value">${esc(value)}</div></div>`; }
function pairs(rows) { return rows.map(([k, v]) => `<dt>${esc(k)}</dt><dd>${v}</dd>`).join(''); }

let lastStatus = null;
async function loadStatus() {
  try {
    const s = await api('/api/v1/pocket/status');
    lastStatus = s;
    $('badge').textContent = s.ready ? '运行中' : '运行中（未配置上游 Key）';
    $('badge').className = `badge ${s.ready ? 'ok' : 'bad'}`;
    const up = Math.round(s.uptime_seconds / 60);
    $('overview-grid').innerHTML = [
      tile('版本', s.version), tile('已运行', up >= 60 ? `${Math.floor(up / 60)} 小时 ${up % 60} 分` : `${up} 分钟`),
      tile('默认模型', s.default_model?.name || s.upstream?.model), tile('上游', s.upstream?.host),
      tile('可选上游模型', s.models_available ?? '尚未读取'), tile('正在处理的请求', s.active_requests),
      tile('上游最长等待', minutes(s.upstream?.idle_timeout_seconds ?? 900)), tile('审校 Skill', s.review_skill),
      tile('手机备份', `${s.backups?.count ?? 0} 份 · ${size(s.backups?.total_bytes)}`),
      tile('表情包', `${s.stickers?.packs ?? 0} 个包 · ${s.stickers?.stickers ?? 0} 张`),
      tile('小世界', `${s.worlds?.count ?? 0} 个 · ${s.worlds?.running ?? 0} 个运行中`),
      tile('监听', (s.listen?.hosts || []).map((h) => `${h}:${s.listen.port}`).join('，')),
    ].join('');
    $('dsh-list').innerHTML = pairs([
      ['状态', s.dsh?.available ? '可用' : `不可用：${esc(s.dsh?.error || '')}`],
      ['Python', esc(s.dsh?.python || '—')], ['引擎版本', esc((s.dsh?.commit || '').slice(0, 10) || '—')],
      ['说明', '手机上用电脑写作服务、勾选 DSH 成人互动的会话会通过这里运行引擎；存档在私密目录的 dsh\\data。'],
    ]);
    $('reach-list').innerHTML = pairs([
      ['状态', s.reach?.available ? '可用' : `不可用：${esc(s.reach?.error || '')}`],
      ['版本', esc(s.reach?.version || '—')],
      ['渠道', esc(Object.entries(s.reach?.channels || {}).map(([c, a]) => `${c}（${a.join('/')}）`).join('，') || '—')],
      ['说明', '手机上用电脑写作服务、模型支持工具的会话会自动带上联网搜索：搜网页、读链接、看视频字幕；角色“自己刷视频”也走这里。不读取浏览器里的登录。'],
    ]);
  } catch (error) {
    $('badge').textContent = `读不到服务：${error.message}`;
    $('badge').className = 'badge bad';
  }
}

async function loadConnection() {
  const c = await api('/api/admin/connection');
  const masked = `${c.token.slice(0, 4)}${'•'.repeat(12)}${c.token.slice(-4)}`;
  $('connection-list').innerHTML = pairs([
    ['局域网地址', esc(c.lan_url || '未开启局域网模式')], ['本机地址', esc(c.local_url)], ['远程地址', esc(c.public_url || '未填写')],
    ['网关 Token', `<span id="token" class="token">${esc(masked)}</span> <button id="token-show" class="small">显示</button> <button id="token-copy" class="small">复制</button>`],
    ['私密目录', esc(c.data_dir)],
  ]);
  $('public-url').value = c.public_url || '';
  $('token-show').onclick = () => { const shown = $('token').textContent === c.token; $('token').textContent = shown ? masked : c.token; $('token-show').textContent = shown ? '显示' : '隐藏'; };
  $('token-copy').onclick = async () => { await navigator.clipboard.writeText(c.token); $('token-copy').textContent = '已复制'; setTimeout(() => { $('token-copy').textContent = '复制'; }, 1500); };
}

async function loadRuns() {
  const { runs } = await api('/api/admin/runs?limit=60');
  if (!runs.length) { $('runs-table').innerHTML = '<tr><td class="muted">还没有请求记录。</td></tr>'; return; }
  $('runs-table').innerHTML = '<tr><th>时间</th><th>模型</th><th>用途</th><th>状态</th><th>耗时</th><th>tokens</th><th>备注</th></tr>' + runs.map((r, i) => {
    const tokens = r.usage ? (r.usage.total_tokens ?? ((r.usage.prompt_tokens || 0) + (r.usage.completion_tokens || 0))) : '—';
    return `<tr class="clickable" data-i="${i}"><td>${esc(time(r.created_at))}</td><td>${esc(r.model)}</td><td>${esc(PURPOSES[r.purpose] || r.purpose)}</td>` +
      `<td class="status-${esc(r.status)}">${esc(STATUS[r.status] || r.status)}</td><td>${r.duration_ms == null ? '—' : esc((r.duration_ms / 1000).toFixed(1) + ' 秒')}</td>` +
      `<td>${esc(tokens)}</td><td>${esc(r.error || r.warning || '')}</td></tr>`;
  }).join('');
  $('runs-table').querySelectorAll('tr.clickable').forEach((row) => {
    row.onclick = async () => {
      const r = runs[Number(row.dataset.i)];
      $('run-title').textContent = `${time(r.created_at)} · ${r.model}`;
      $('run-text').textContent = '读取中…';
      $('run-detail').hidden = false;
      try { $('run-text').textContent = await api(`/api/admin/runs/${r.chat_hash}/${r.run_id}`); } catch (error) { $('run-text').textContent = error.message; }
    };
  });
}

let settings = null;
async function loadSettings() {
  const [config, models] = await Promise.all([api('/api/v1/pocket/config'), api('/api/v1/models').catch(() => ({ data: [] }))]);
  settings = config.settings;
  const choices = models.data.filter((m) => !m.id.startsWith('pocket-')).map((m) => [m.id, m.model_spec?.name || m.name || m.id]);
  if (!choices.some(([id]) => id === settings.upstreamModel)) choices.unshift([settings.upstreamModel, settings.upstreamModel]);
  const select = (name, options, value) => `<select name="${name}">${options.map(([v, label]) => `<option value="${esc(v)}"${String(v) === String(value) ? ' selected' : ''}>${esc(label)}</option>`).join('')}</select>`;
  const waits = [300, 900, 1800, 3600, 0];
  if (!waits.includes(settings.upstreamIdleTimeoutSec)) waits.push(settings.upstreamIdleTimeoutSec);
  $('settings-form').innerHTML = [
    ['默认模型', select('upstreamModel', choices, settings.upstreamModel)],
    ['思考强度', select('reasoningEffort', Object.entries(EFFORTS), settings.reasoningEffort)],
    ['审校 Skill', select('reviewSkill', [['none', '不用'], ['ss-stop-slop', 'stop-slop 去 AI 味']], settings.reviewSkill)],
    ['审校思考强度', select('reviewReasoningEffort', Object.entries(EFFORTS), settings.reviewReasoningEffort)],
    ['按审校意见自动改正文（试验）', `<input type="checkbox" name="autoRevise"${settings.autoRevise ? ' checked' : ''}>`],
    ['附带 Venice 自带的系统提示', `<input type="checkbox" name="includeVeniceSystemPrompt"${settings.includeVeniceSystemPrompt ? ' checked' : ''}>`],
    ['上游最长等待', select('upstreamIdleTimeoutSec', waits.map((w) => [w, minutes(w)]), settings.upstreamIdleTimeoutSec)],
    ['电脑保留备份', `<input type="number" name="backupKeep" min="1" max="200" value="${esc(settings.backupKeep)}"> 份`],
  ].map(([label, field]) => `<label>${esc(label)}</label><div>${field}</div>`).join('') +
    '<div class="full"><button type="submit">保存设置</button> <span id="settings-note" class="muted"></span></div>';
}

$('settings-form').onsubmit = async (event) => {
  event.preventDefault();
  const form = event.target;
  const edited = {
    upstreamModel: form.upstreamModel.value, reasoningEffort: form.reasoningEffort.value, reviewSkill: form.reviewSkill.value,
    reviewReasoningEffort: form.reviewReasoningEffort.value, autoRevise: form.autoRevise.checked,
    includeVeniceSystemPrompt: form.includeVeniceSystemPrompt.checked, upstreamIdleTimeoutSec: Number(form.upstreamIdleTimeoutSec.value),
    backupKeep: Number(form.backupKeep.value),
  };
  const changes = Object.fromEntries(Object.entries(edited).filter(([key, value]) => value !== settings[key]));
  if (!Object.keys(changes).length) { $('settings-note').textContent = '没有改动'; return; }
  try {
    await api('/api/v1/pocket/config', { method: 'PATCH', body: JSON.stringify({ settings: changes }) });
    await Promise.all([loadSettings(), loadStatus()]);
    $('settings-note').textContent = '已保存，立即生效';
  } catch (error) { $('settings-note').textContent = `保存失败：${error.message}`; }
};

async function loadBackups() {
  const { data } = await api('/api/v1/backups');
  if (!data.length) { $('backups-table').innerHTML = '<tr><td class="muted">还没有手机备份。</td></tr>'; return; }
  $('backups-table').innerHTML = '<tr><th>时间</th><th>大小</th><th>设备</th><th>方式</th><th>App 版本</th><th></th></tr>' + data.map((b) =>
    `<tr><td>${esc(time(b.created_at))}</td><td>${esc(size(b.size))}</td><td>${esc(b.device || '—')}</td><td>${esc(REASONS[b.reason] || b.reason)}</td>` +
    `<td>${esc(b.app_version || '—')}</td><td><a href="/api/v1/backups/${esc(b.id)}" download>下载</a> <button class="danger small" data-id="${esc(b.id)}">删除</button></td></tr>`).join('');
  $('backups-table').querySelectorAll('button[data-id]').forEach((button) => {
    button.onclick = async () => {
      if (!confirm('删除这份备份？删除后无法恢复。')) return;
      await api(`/api/v1/backups/${button.dataset.id}`, { method: 'DELETE' });
      loadBackups(); loadStatus();
    };
  });
}

async function loadStickers() {
  const { packs } = await api('/api/v1/stickers');
  if (!packs.length) { $('sticker-list').innerHTML = '<p class="muted">还没有表情包。在手机“设置 → 表情包”导入，图片会存到这台电脑上。</p>'; return; }
  $('sticker-list').innerHTML = packs.map((p) => `<div class="pack"><div class="pack-head"><strong>${esc(p.name)}</strong>` +
    `<span class="muted">${esc(p.stickers.length)} 张 · 许可：${esc(p.license || '未声明')}</span>` +
    `<label><input type="checkbox" data-toggle="${esc(p.id)}"${p.enabled ? ' checked' : ''}> 启用</label>` +
    `<button class="danger small" data-delete="${esc(p.id)}">删除</button></div>` +
    `<div class="thumbs">${p.stickers.slice(0, 16).map((s) => `<img loading="lazy" alt="${esc(s.key)}" title="${esc(s.key)}${s.description ? '：' + esc(s.description) : ''}" src="/api/v1/stickers/files/${esc(s.file)}">`).join('')}</div></div>`).join('');
  $('sticker-list').querySelectorAll('input[data-toggle]').forEach((box) => {
    box.onchange = async () => { await api(`/api/v1/stickers/packs/${box.dataset.toggle}`, { method: 'PATCH', body: JSON.stringify({ enabled: box.checked }) }); loadStatus(); };
  });
  $('sticker-list').querySelectorAll('button[data-delete]').forEach((button) => {
    button.onclick = async () => {
      if (!confirm('从电脑上删除这个表情包和它的图片？')) return;
      await api(`/api/v1/stickers/packs/${button.dataset.delete}`, { method: 'DELETE' });
      loadStickers(); loadStatus();
    };
  });
}

async function loadLogs() {
  const { server, supervisor } = await api('/api/admin/logs?lines=200');
  $('log-view').textContent = [...server, ...(supervisor.length ? ['', '— 崩溃重启记录 —', ...supervisor] : [])].join('\n') || '还没有日志。';
  $('log-view').scrollTop = $('log-view').scrollHeight;
}

$('public-form').onsubmit = async (event) => {
  event.preventDefault();
  try { await api('/api/admin/connection', { method: 'PATCH', body: JSON.stringify({ publicBaseUrl: $('public-url').value }) }); await loadConnection(); }
  catch (error) { alert(error.message); }
};
let biliTimer = null;
async function loadBili() {
  const s = await api('/api/admin/bili');
  $('bili-state').textContent = s.logged_in ? `已登录${s.name ? `：${s.name}` : ''}${s.valid === null ? '（暂时无法向 B 站确认）' : ''}` : (s.valid === false ? '登录已失效，请重新扫码' : '未登录：模型只能看到标题和简介，读不到字幕');
  $('bili-logout').hidden = !s.logged_in;
}
async function pollBili() {
  try {
    const r = await api('/api/admin/bili/poll', { method: 'POST' });
    if (r.state === 'scanned') $('bili-state').textContent = '已扫码，请在手机上确认登录';
    if (r.state === 'waiting' || r.state === 'scanned') { biliTimer = setTimeout(pollBili, 2000); return; }
    $('bili-qr').hidden = true;
    $('bili-login').disabled = false;
    if (r.state === 'expired') $('bili-state').textContent = '二维码已过期，请重新扫码';
    else await loadBili();
  } catch (error) {
    $('bili-qr').hidden = true;
    $('bili-login').disabled = false;
    $('bili-state').textContent = `登录失败：${error.message}`;
  }
}
$('bili-login').onclick = async () => {
  clearTimeout(biliTimer);
  $('bili-login').disabled = true;
  try {
    await api('/api/admin/bili/login', { method: 'POST' });
    $('bili-qr').src = `/api/admin/bili/qr.png?t=${Date.now()}`;
    $('bili-qr').hidden = false;
    $('bili-state').textContent = '用 B 站 App 扫描二维码（3 分钟内有效）';
    biliTimer = setTimeout(pollBili, 2000);
  } catch (error) {
    $('bili-login').disabled = false;
    $('bili-state').textContent = `生成二维码失败：${error.message}`;
  }
};
$('bili-logout').onclick = async () => {
  if (!confirm('退出 B 站登录？之后模型读不到字幕。')) return;
  await api('/api/admin/bili/login', { method: 'DELETE' });
  await loadBili();
};
$('runs-refresh').onclick = () => loadRuns();
$('logs-refresh').onclick = () => loadLogs();
$('run-close').onclick = () => { $('run-detail').hidden = true; };

const safely = (load) => load().catch((error) => console.warn(error));
async function loadSocial() {
  const s = await api('/api/admin/social');
  $('social-state').textContent = !s.available ? '还不能用：没有找到 OpenCLI 或 Chrome。' : s.mode === 'window' ? '登录窗口已打开：在窗口里登录，完成后点“登录完成”。' : s.mode === 'headless' ? '正在后台查询。' : '';
  $('social-done').hidden = s.mode !== 'window';
  $('social-kept').textContent = [s.keepalive ? `上次保活：${time(s.keepalive.at)}${s.keepalive.lost?.length ? `，掉线：${s.keepalive.lost.join('、')}` : ''}` : '还没有保活过（服务启动 3 分钟后第一次，之后每 8 小时一次）',
    s.cookies_saved_at ? `登录信息备份于 ${time(s.cookies_saved_at)}` : ''].filter(Boolean).join('；');
  $('social-list').innerHTML = Object.entries(s.platforms || {}).map(([key, p]) => `<dt>${esc(p.name)}</dt><dd>${p.logged_in ? (p.blocked_at ? '已登录，但上次查询要求验证' : '已登录') : '未登录'}
    ${p.logged_in && p.blocked_at && p.verify ? `<button class="small" data-social-verify="${esc(key)}">去验证</button>` : ''}
    <button class="small" data-social-login="${esc(key)}">${p.logged_in ? '重新登录' : '登录'}</button>
    ${p.logged_in ? `<button class="small danger" data-social-logout="${esc(key)}">退出</button>` : ''}</dd>`).join('');
}
$('social-list').onclick = async (event) => {
  const login = event.target.closest('[data-social-login]');
  const verify = event.target.closest('[data-social-verify]');
  const logout = event.target.closest('[data-social-logout]');
  try {
    if (login) await api(`/api/admin/social/login?platform=${encodeURIComponent(login.dataset.socialLogin)}`, { method: 'POST' });
    if (verify) await api(`/api/admin/social/login?platform=${encodeURIComponent(verify.dataset.socialVerify)}&verify=1`, { method: 'POST' });
    if (logout && confirm('退出这个平台的登录？')) await api(`/api/admin/social/login?platform=${encodeURIComponent(logout.dataset.socialLogout)}`, { method: 'DELETE' });
  } catch (error) { alert(error.message); }
  await loadSocial();
};
$('social-keep').onclick = async () => {
  $('social-keep').disabled = true;
  try { const r = await api('/api/admin/social/keepalive', { method: 'POST' }); if (r.skipped) alert(r.skipped === 'window' ? '登录窗口开着，先点“登录完成”' : r.skipped === 'busy' ? '有查询正在进行，稍后再试' : '还没有登录任何平台'); }
  catch (error) { alert(error.message); }
  $('social-keep').disabled = false;
  await loadSocial();
};
$('social-done').onclick = async () => {
  try { await api('/api/admin/social/done', { method: 'POST' }); } catch (error) { alert(error.message); }
  await loadSocial();
};

// ---- 小世界
const HOLDS = { paused: '已暂停', calls: '今天的请求次数用完了', ticks: '今天的推进次数用完了', quiet: '安静时段（居民在睡觉）' };
let worldModels = null;
const world = { id: null, items: [], next: 0, info: null, tab: 'all' };

function worldBadge(w) {
  if (w.busy) return ['正在推进…', 'badge'];
  if (w.hold) return [HOLDS[w.hold] || w.hold, w.hold === 'paused' ? 'badge bad' : 'badge'];
  return ['运行中', 'badge ok'];
}

async function loadWorlds() {
  const { data } = await api('/api/v1/pocket/worlds');
  $('world-list').innerHTML = data.length ? data.map((w) => {
    const [label, cls] = worldBadge(w);
    const last = w.last_message ? `${w.last_message.time} ${w.last_message.name}：${w.last_message.text}` : '还没有消息';
    return `<div class="world-row" data-world="${esc(w.id)}"><strong>${esc(w.name)}</strong><span class="${cls}">${esc(label)}</span>` +
      `<span class="muted">世界时间 ${esc(w.world_time_label)} · ${esc(w.residents.length)} 位居民 · 今天 ${esc(w.usage.calls)}/${esc(w.settings.dailyCalls)} 次请求</span>` +
      `${w.unread ? `<span class="unread">${esc(w.unread)}</span>` : ''}<span class="preview">${esc(last.length > 120 ? `${last.slice(0, 120)}…` : last)}</span></div>`;
  }).join('') : '<p class="muted">还没有世界。手机 App 0.4.0 起可以把剧本“做成小世界”；现在可以在下面粘贴世界 JSON 创建。</p>';
  $('world-list').querySelectorAll('[data-world]').forEach((row) => { row.onclick = () => openWorld(row.dataset.world); });
}

function residentName(id) { return world.info?.definition.residents.find((r) => r.id === id)?.name || id; }

function renderTabs() {
  const residents = world.info.definition.residents;
  const tabs = [['all', '全部'], ['group', `群聊「${world.info.definition.groupName}」`], ...residents.map((r) => [`dm:${r.id}`, `私信 · ${r.name}`]), ['event', '事件']];
  $('world-tabs').innerHTML = tabs.map(([key, label]) => {
    const count = key.startsWith('dm:') ? world.items.filter((item) => item.channel === key).length : 0;
    return `<button type="button" data-tab="${esc(key)}" class="${world.tab === key ? 'active' : ''}">${esc(label)}${count ? ` (${count})` : ''}</button>`;
  }).join('');
  $('world-tabs').querySelectorAll('[data-tab]').forEach((button) => { button.onclick = () => { world.tab = button.dataset.tab; renderTabs(); renderTimeline(); }; });
  const channels = [['group', `群聊「${world.info.definition.groupName}」`], ...residents.map((r) => [`dm:${r.id}`, `私信 ${r.name}`])];
  const chosen = world.tab.startsWith('dm:') || world.tab === 'group' ? world.tab : $('world-channel').value || 'group';
  $('world-channel').innerHTML = channels.map(([key, label]) => `<option value="${esc(key)}"${key === chosen ? ' selected' : ''}>${esc(label)}</option>`).join('');
}

function renderTimeline() {
  const box = $('world-timeline');
  const atBottom = box.scrollHeight - box.scrollTop - box.clientHeight < 40;
  const shown = world.items.filter((item) => world.tab === 'all' || (world.tab === 'event' ? item.kind === 'event' : item.channel === world.tab));
  box.innerHTML = shown.length ? shown.map((item) => {
    if (item.kind === 'event') {
      const who = item.who?.length ? `（${item.who.map(residentName).join('、')}）` : '';
      return `<div class="item event"><span class="meta">${esc(item.time)}</span> ${esc(item.text)}${esc(who)}</div>`;
    }
    const channel = world.tab === 'all' && item.channel !== 'group' ? `<span class="channel">私信 · ${esc(residentName(item.channel.slice(3)))}</span>` : '';
    return `<div class="item${item.from === 'user' ? ' mine' : ''}"><span class="meta">${esc(item.time)}</span> <span class="who">${esc(item.from === 'user' ? `${item.name}（你）` : item.name)}</span>${channel}<div class="text">${esc(item.text)}</div></div>`;
  }).join('') : '<p class="muted">这里还没有内容。</p>';
  if (atBottom) box.scrollTop = box.scrollHeight;
}

function renderWorldInfo() {
  const w = world.info;
  const [label, cls] = worldBadge(w);
  $('world-name').textContent = w.name;
  $('world-state').textContent = label;
  $('world-state').className = cls;
  $('world-pause').textContent = w.paused ? '恢复' : '暂停';
  const tokens = (w.usage.prompt_tokens || 0) + (w.usage.completion_tokens || 0);
  $('world-tiles').innerHTML = [
    tile('世界时间', w.world_time_label),
    tile('下次推进', w.paused ? '暂停中' : w.next_tick_at ? time(new Date(w.next_tick_at).toISOString()) : HOLDS[w.hold] || '—'),
    tile('推进间隔', `${w.interval_minutes} 分钟${w.interval_minutes > w.settings.tickMinutes ? '（你不在，已放慢）' : ''}`),
    tile('今天推进', `${w.usage.ticks} / ${w.settings.dailyTicks}`),
    tile('今天请求', `${w.usage.calls} / ${w.settings.dailyCalls}`),
    tile('今天 tokens', `${tokens.toLocaleString()}（输入 ${(w.usage.prompt_tokens || 0).toLocaleString()}）`),
    tile('累计请求', `${w.totals.calls} 次 · ${((w.totals.prompt_tokens || 0) + (w.totals.completion_tokens || 0)).toLocaleString()} tokens`),
    tile('模型', w.model === 'pocket-direct' ? '默认模型' : w.model),
  ].join('');
  $('world-error').textContent = w.last_error ? `上次出错（${time(new Date(w.last_error.at).toISOString())}）：${w.last_error.message}` : '';
  $('world-residents').innerHTML = '<tr><th>居民</th><th>在哪</th><th>在做什么</th><th>心情</th><th>打算</th></tr>' + w.residents.map((r) =>
    `<tr><td>${esc(r.name)}</td><td>${esc(r.location || '—')}</td><td>${esc(r.activity || '—')}</td><td>${esc(r.mood || '—')}</td><td>${esc(r.plan || '—')}</td></tr>`).join('');
}

async function renderWorldSettings() {
  const w = world.info;
  if (!worldModels) worldModels = await api('/api/v1/models').then((m) => m.data.filter((x) => x.id !== 'pocket-writer')).catch(() => []);
  const models = worldModels.map((m) => [m.id, m.id === 'pocket-direct' ? '默认模型' : m.model_spec?.name || m.name || m.id]);
  if (!models.some(([id]) => id === w.model)) models.unshift([w.model, w.model]);
  const select = (name, options, value) => `<select name="${name}">${options.map(([v, label]) => `<option value="${esc(v)}"${String(v) === String(value) ? ' selected' : ''}>${esc(label)}</option>`).join('')}</select>`;
  const s = w.settings;
  $('world-settings').innerHTML = [
    ['推进间隔', select('tickMinutes', [15, 20, 30, 45, 60, 90, 120].map((m) => [m, `${m} 分钟`]), s.tickMinutes)],
    ['安静时段', `<input type="time" name="quietStart" value="${esc(s.quietStart)}"> 到 <input type="time" name="quietEnd" value="${esc(s.quietEnd)}">（两个时间相同表示没有安静时段）`],
    ['每天最多推进', `<input type="number" name="dailyTicks" min="0" max="200" value="${esc(s.dailyTicks)}"> 次`],
    ['每天最多请求', `<input type="number" name="dailyCalls" min="1" max="400" value="${esc(s.dailyCalls)}"> 次（推进、回应、写日记都算）`],
    ['你超过一天没看就放慢', `<input type="checkbox" name="slowWhenAway"${s.slowWhenAway ? ' checked' : ''}>`],
    ['模型', select('model', models, w.model)],
  ].map(([label, input]) => `<label>${esc(label)}</label><div>${input}</div>`).join('') +
    '<div class="full"><button type="submit">保存</button> <span id="world-settings-note" class="muted"></span></div>';
}

$('world-settings').onsubmit = async (event) => {
  event.preventDefault();
  const form = event.target;
  const settings = {
    tickMinutes: Number(form.tickMinutes.value), quietStart: form.quietStart.value, quietEnd: form.quietEnd.value,
    dailyTicks: Number(form.dailyTicks.value), dailyCalls: Number(form.dailyCalls.value), slowWhenAway: form.slowWhenAway.checked,
  };
  try {
    world.info = await api(`/api/v1/pocket/worlds/${world.id}`, { method: 'PATCH', body: JSON.stringify({ settings, model: form.model.value }) });
    renderWorldInfo();
    $('world-settings-note').textContent = '已保存';
  } catch (error) { $('world-settings-note').textContent = `保存失败：${error.message}`; }
};

async function loadDiary() {
  const { entries, residents } = await api(`/api/v1/pocket/worlds/${world.id}/diary`);
  const notes = residents.filter((r) => r.notes).map((r) => `<div class="chapter"><strong>${esc(r.name)}记得的事</strong>\n${esc(r.notes)}</div>`).join('');
  const chapters = [...entries].reverse().map((e) => `<div class="chapter"><strong>${esc(e.day)}</strong>\n${esc(e.text)}</div>`).join('');
  $('world-diary').innerHTML = notes + chapters || '<p class="muted">世界里每过一天写一章日记，同时更新每个人记得的事。</p>';
}

async function openWorld(id) {
  world.id = id; world.items = []; world.next = 0; world.tab = 'all';
  world.info = await api(`/api/v1/pocket/worlds/${id}`);
  const first = await api(`/api/v1/pocket/worlds/${id}/messages?tail=300&seen=1`);
  world.items = first.items;
  world.next = first.next;
  $('world-detail').hidden = false;
  renderWorldInfo(); renderTabs(); renderTimeline();
  $('world-timeline').scrollTop = $('world-timeline').scrollHeight;
  await Promise.all([renderWorldSettings(), loadDiary()]);
  loadWorlds();
}

async function refreshWorld() {
  if (!world.id || $('world-detail').hidden) return;
  const update = await api(`/api/v1/pocket/worlds/${world.id}/messages?since=${world.next}&seen=1`);
  const known = new Set(world.items.map((item) => item.seq));
  const fresh = update.items.filter((item) => !known.has(item.seq));
  world.items.push(...fresh);
  world.next = update.next;
  world.info = { ...world.info, ...update.world };
  renderWorldInfo();
  if (fresh.length) { renderTabs(); renderTimeline(); loadDiary(); }
}

$('world-say').onsubmit = async (event) => {
  event.preventDefault();
  const text = $('world-text').value.trim();
  if (!text || !world.id) return;
  const button = event.target.querySelector('button');
  button.disabled = true;
  $('world-say-note').textContent = '等她们回应…（一次付费请求）';
  try {
    await api(`/api/v1/pocket/worlds/${world.id}/messages`, { method: 'POST', body: JSON.stringify({ channel: $('world-channel').value, text }) });
    $('world-text').value = '';
    $('world-say-note').textContent = '';
  } catch (error) { $('world-say-note').textContent = error.message; }
  button.disabled = false;
  await refreshWorld();
};
$('world-pause').onclick = async () => {
  world.info = { ...world.info, ...await api(`/api/v1/pocket/worlds/${world.id}/${world.info.paused ? 'resume' : 'pause'}`, { method: 'POST' }) };
  renderWorldInfo(); loadWorlds();
};
$('world-tick').onclick = async () => {
  if (!confirm('现在推进一次？这是一次付费请求，计入今天的上限。')) return;
  $('world-tick').disabled = true;
  try { const r = await api(`/api/v1/pocket/worlds/${world.id}/tick`, { method: 'POST' }); if (r.error) alert(r.error); }
  catch (error) { alert(error.message); }
  $('world-tick').disabled = false;
  await refreshWorld(); loadWorlds();
};
$('world-close').onclick = () => { $('world-detail').hidden = true; world.id = null; };
$('world-delete').onclick = async () => {
  if (!confirm(`删除世界「${world.info.name}」？所有聊天、事件和日记都会删掉，无法恢复。`)) return;
  await api(`/api/v1/pocket/worlds/${world.id}`, { method: 'DELETE' });
  $('world-detail').hidden = true; world.id = null;
  loadWorlds();
};
$('world-create').onclick = async () => {
  let pack;
  try { pack = JSON.parse($('world-pack').value); } catch { $('world-create-note').textContent = '不是有效的 JSON'; return; }
  if (pack && typeof pack === 'object' && pack.startPaused === undefined) pack.startPaused = $('world-start-paused').checked;
  try {
    const created = await api('/api/v1/pocket/worlds', { method: 'POST', body: JSON.stringify(pack) });
    $('world-pack').value = '';
    $('world-create-note').textContent = created.paused ? `已创建「${created.name}」，暂停中：点“恢复”后大约半小时第一次推进，也可以点“推进一次”。` : `已创建「${created.name}」，大约一分钟后第一次推进。`;
    $('world-import').open = false;
    await loadWorlds();
    await openWorld(created.id);
  } catch (error) { $('world-create-note').textContent = `创建失败：${error.message}`; }
};
$('worlds-refresh').onclick = () => { safely(loadWorlds); safely(refreshWorld); };

[loadStatus, loadWorlds, loadConnection, loadRuns, loadSettings, loadBackups, loadStickers, loadLogs, loadBili, loadSocial].forEach(safely);
setInterval(() => safely(loadStatus), 5000);
setInterval(() => safely(loadRuns), 20000);
setInterval(() => { safely(refreshWorld); safely(loadWorlds); }, 10000);
