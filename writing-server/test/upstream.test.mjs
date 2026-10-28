import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { createUpstream, UpstreamError } from '../src/upstream.mjs';

async function withUpstream(body, type, fn) {
  const server = http.createServer((_req, res) => {
    res.writeHead(200, { 'content-type': type });
    res.end(body);
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  const port = server.address().port;
  try { return await fn(createUpstream({ baseUrl: `http://127.0.0.1:${port}/v1`, key: 'fixture' })); }
  finally { await new Promise((resolve) => server.close(resolve)); }
}

test('upstream parses fragmented SSE and usage without inventing a finish', async () => {
  await withUpstream('data: {"choices":[{"delta":{"reasoning_content":"think"}}]}\n\ndata: {"choices":[{"delta":{"content":"answer"},"finish_reason":"stop"}],"usage":{"prompt_tokens":2,"completion_tokens":3,"total_tokens":5}}\n\ndata: [DONE]\n\n', 'text/event-stream', async (upstream) => {
    const result = await upstream.complete({ body: { model: 'm', messages: [], stream: true, max_tokens: 7 } });
    assert.equal(result.reasoning, 'think');
    assert.equal(result.content, 'answer');
    assert.equal(result.finish_reason, 'stop');
    assert.equal(result.usage.total_tokens, 5);
  });
});

test('upstream rejects an EOF without finish reason and preserves partial result', async () => {
  await withUpstream('data: {"choices":[{"delta":{"content":"partial"}}]}\n\n', 'text/event-stream', async (upstream) => {
    await assert.rejects(() => upstream.complete({ body: { model: 'm', messages: [], stream: true } }), (error) => {
      assert.ok(error instanceof UpstreamError);
      assert.equal(error.code, 'truncated_stream');
      assert.equal(error.partial.content, 'partial');
      return true;
    });
  });
});

test('upstream keeps message snapshots while appending token deltas', async () => {
  await withUpstream('data: {"choices":[{"delta":{"content":"a"}}]}\n\ndata: {"choices":[{"delta":{"content":"b"}}]}\n\ndata: {"choices":[{"message":{"content":"snapshot"},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n', 'text/event-stream', async (upstream) => {
    const result = await upstream.complete({ body: { model: 'm', messages: [], stream: true } });
    assert.equal(result.content, 'snapshot');
  });
});

test('upstream rejects non-stream JSON without finish_reason', async () => {
  await withUpstream('{"choices":[{"message":{"content":"answer"}}]}', 'application/json', async (upstream) => {
    await assert.rejects(() => upstream.complete({ body: { model: 'm', messages: [], stream: true } }), (error) => {
      assert.ok(error instanceof UpstreamError);
      assert.equal(error.code, 'missing_finish_reason');
      assert.equal(error.partial.content, 'answer');
      return true;
    });
  });
});

test('upstream preserves UTF-8 text split across response chunks', async () => {
  const http = await import('node:http');
  const server = http.createServer((_req, res) => {
    res.writeHead(200, { 'content-type': 'text/event-stream' });
    const bytes = Buffer.from('data: {"choices":[{"delta":{"content":"中文"},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n');
    for (let i = 0; i < bytes.length; i += 1) res.write(bytes.subarray(i, i + 1));
    res.end();
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  try {
    const upstream = createUpstream({ baseUrl: `http://127.0.0.1:${server.address().port}/v1`, key: 'fixture' });
    const result = await upstream.complete({ body: { model: 'm', messages: [], stream: true } });
    assert.equal(result.content, '中文');
  } finally { await new Promise((resolve) => server.close(resolve)); }
});
