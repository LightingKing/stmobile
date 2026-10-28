import http from 'node:http';
import https from 'node:https';
import { randomUUID } from 'node:crypto';
import { StringDecoder } from 'node:string_decoder';

export class UpstreamError extends Error {
  constructor(message, { status = 502, partial = null, code = 'upstream_error', cause = undefined } = {}) {
    super(message, { cause });
    this.name = 'UpstreamError';
    this.status = status;
    this.code = code;
    this.partial = partial;
  }
}

function appendText(current, incoming) { return `${current || ''}${incoming || ''}`; }

function usageOf(value) {
  if (!value || typeof value !== 'object') return null;
  const keys = ['prompt_tokens', 'completion_tokens', 'total_tokens', 'reasoning_tokens', 'cached_tokens'];
  const result = {};
  for (const key of keys) if (Number.isFinite(Number(value[key]))) result[key] = Number(value[key]);
  return Object.keys(result).length ? result : null;
}

function mergeUsage(all, usage) {
  if (!usage) return all;
  const result = { ...(all || {}) };
  for (const [key, value] of Object.entries(usage)) result[key] = (result[key] || 0) + value;
  return result;
}

function extractMessage(json) {
  const choice = Array.isArray(json?.choices) ? json.choices[0] || {} : {};
  const message = choice.message || choice.delta || {};
  const content = typeof message.content === 'string' ? message.content : '';
  const reasoning = message.reasoning_content || message.reasoning || '';
  const toolCalls = Array.isArray(message.tool_calls) ? message.tool_calls : [];
  return {
    id: json.id || randomUUID(),
    requestId: json.request_id || '',
    content,
    reasoning: String(reasoning || ''),
    tool_calls: toolCalls,
    finish_reason: choice.finish_reason || '',
    usage: usageOf(json.usage),
  };
}

function appendToolCall(calls, incoming) {
  for (const call of incoming || []) {
    const index = Number.isInteger(call.index) ? call.index : calls.length;
    const target = calls[index] || { index, id: '', type: 'function', function: { name: '', arguments: '' } };
    target.id = target.id || call.id || '';
    target.type = target.type || call.type || 'function';
    target.function = target.function || { name: '', arguments: '' };
    const fn = call.function || {};
    target.function.name = target.function.name || fn.name || '';
    target.function.arguments = appendText(target.function.arguments, fn.arguments || '');
    calls[index] = target;
  }
}

function newResult() { return { id: randomUUID(), requestId: '', content: '', reasoning: '', tool_calls: [], finish_reason: '', usage: null }; }

function applyEvent(result, json) {
  if (json?.error) throw new UpstreamError(json.error.message || '上游在流中返回错误', { code: 'upstream_in_band_error', partial: result });
  result.id = json.id || result.id;
  result.requestId = json.request_id || result.requestId;
  // Usage frames are snapshots. Pipeline-level aggregation happens between calls.
  result.usage = usageOf(json.usage) || result.usage;
  const choice = Array.isArray(json.choices) ? json.choices[0] || {} : {};
  if (choice.delta) {
    const delta = choice.delta;
    result.content = appendText(result.content, typeof delta.content === 'string' ? delta.content : '');
    result.content = appendText(result.content, typeof delta.refusal === 'string' ? delta.refusal : '');
    result.reasoning = appendText(result.reasoning, delta.reasoning_content || delta.reasoning || '');
    appendToolCall(result.tool_calls, delta.tool_calls);
  } else if (choice.message) {
    // Some compatible gateways send complete message snapshots instead of deltas.
    const message = choice.message;
    if (typeof message.content === 'string') result.content = message.content;
    if (typeof message.reasoning_content === 'string' || typeof message.reasoning === 'string') result.reasoning = message.reasoning_content || message.reasoning;
    if (Array.isArray(message.tool_calls)) result.tool_calls = message.tool_calls;
  }
  if (choice.finish_reason) result.finish_reason = choice.finish_reason;
}

async function readResponse(response, limit = 64 * 1024 * 1024) {
  const chunks = [];
  let size = 0;
  for await (const chunk of response) {
    size += chunk.length;
    if (size > limit) throw new UpstreamError('上游响应过大', { code: 'upstream_response_too_large' });
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString('utf8');
}

async function parseSse(stream, { onEvent } = {}) {
  const result = newResult();
  let buffer = '';
  let doneSignal = false;
  const decoder = new StringDecoder('utf8');
  const consume = async (line) => {
    if (!line.startsWith('data:')) return;
    const value = line.slice(5).trim();
    if (value === '[DONE]') { doneSignal = true; return; }
    if (!value) return;
    let json;
    try { json = JSON.parse(value); } catch (error) { throw new UpstreamError('上游 SSE 数据格式无效', { code: 'invalid_stream', partial: result, cause: error }); }
    applyEvent(result, json);
    onEvent?.(json);
  };
  try {
    for await (const chunk of stream) {
      buffer += decoder.write(Buffer.from(chunk));
      let newline;
      while ((newline = buffer.indexOf('\n')) >= 0) {
        const line = buffer.slice(0, newline).replace(/\r$/, '');
        buffer = buffer.slice(newline + 1);
        await consume(line);
      }
    }
    buffer += decoder.end();
    if (buffer.trim()) await consume(buffer.trim());
  } catch (error) {
    if (error instanceof UpstreamError) throw error;
    throw new UpstreamError(`上游流读取失败：${error?.message || '网络错误'}`, { code: 'stream_error', partial: result, cause: error });
  }
  if (!doneSignal || !result.finish_reason) throw new UpstreamError('上游流在完成前结束', { code: 'truncated_stream', partial: result });
  return result;
}

function requestHttp(url, { headers, body, signal }) {
  const transport = url.protocol === 'https:' ? https : http;
  return new Promise((resolve, reject) => {
    let settled = false;
    const req = transport.request(url, { method: 'POST', headers, timeout: 0 }, (response) => {
      settled = true;
      resolve(response);
    });
    const abort = () => {
      const error = Object.assign(new Error('请求已取消'), { name: 'AbortError', code: 'ABORT_ERR' });
      req.destroy(error);
      if (!settled) reject(error);
    };
    if (signal) {
      if (signal.aborted) return abort();
      signal.addEventListener('abort', abort, { once: true });
    }
    req.on('error', (error) => { if (!settled) reject(error); });
    req.end(body);
  });
}

export function createUpstream({ baseUrl, key }) {
  if (!baseUrl) throw new Error('upstreamBaseUrl is required');
  const endpoint = new URL('chat/completions', `${String(baseUrl).replace(/\/+$/, '')}/`);
  return {
    async complete({ body, signal, onEvent } = {}) {
      let response;
      try {
        response = await requestHttp(endpoint, {
          headers: { authorization: `Bearer ${key}`, 'content-type': 'application/json', accept: 'text/event-stream, application/json' },
          body: JSON.stringify(body), signal,
        });
      } catch (error) {
        if (error?.name === 'AbortError') throw error;
        throw new UpstreamError(`连接上游失败：${error?.message || '网络错误'}`, { code: 'network_error', cause: error });
      }
      if (response.statusCode < 200 || response.statusCode >= 300) {
        await readResponse(response).catch(() => '');
        throw new UpstreamError(`上游 HTTP ${response.statusCode}`, { status: response.statusCode, code: 'upstream_http' });
      }
      const type = String(response.headers['content-type'] || '');
      if (type.toLowerCase().includes('application/json')) {
        const text = await readResponse(response);
        let json;
        try { json = JSON.parse(text); } catch (error) { throw new UpstreamError('上游 JSON 响应无效', { code: 'invalid_json', cause: error }); }
        if (json?.error) throw new UpstreamError(json.error.message || '上游返回错误', { code: 'upstream_error' });
        const result = extractMessage(json);
        if (!result.finish_reason) throw new UpstreamError('上游 JSON 缺少 finish_reason', { code: 'missing_finish_reason', partial: result });
        return result;
      }
      return parseSse(response, { onEvent });
    },
  };
}

export { mergeUsage };
