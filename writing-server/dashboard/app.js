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
      tile('监听', (s.listen?.hosts || []).map((h) => `${h}:${s.listen.port}`).join('，')),
    ].join('');
    $('dsh-list').innerHTML = pairs([
      ['状态', s.dsh?.available ? '可用' : `不可用：${esc(s.dsh?.error || '')}`],
      ['Python', esc(s.dsh?.python || '—')], ['引擎版本', esc((s.dsh?.commit || '').slice(0, 10) || '—')],
      ['说明', '手机上用电脑写作服务、勾选 DSH 成人互动的会话会通过这里运行引擎；存档在私密目录的 dsh\\data。'],
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
$('runs-refresh').onclick = () => loadRuns();
$('logs-refresh').onclick = () => loadLogs();
$('run-close').onclick = () => { $('run-detail').hidden = true; };

const safely = (load) => load().catch((error) => console.warn(error));
[loadStatus, loadConnection, loadRuns, loadSettings, loadBackups, loadStickers, loadLogs].forEach(safely);
setInterval(() => safely(loadStatus), 5000);
setInterval(() => safely(loadRuns), 20000);
