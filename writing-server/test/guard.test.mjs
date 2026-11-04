import test from 'node:test';
import assert from 'node:assert/strict';
import { clientAddress, createAuthGuard } from '../src/guard.mjs';

test('a source is blocked after repeated wrong tokens and released later', () => {
  let time = 0;
  const guard = createAuthGuard({ limit: 3, windowMs: 1000, blockMs: 5000, now: () => time });
  assert.equal(guard.fail('a'), false);
  assert.equal(guard.fail('a'), false);
  assert.equal(guard.fail('a'), true);
  assert.equal(guard.retryAfter('a'), 5);
  assert.equal(guard.retryAfter('b'), 0);
  time = 4000;
  assert.equal(guard.retryAfter('a'), 1);
  time = 5001;
  assert.equal(guard.retryAfter('a'), 0);
  assert.equal(guard.fail('a'), false, 'a fresh window starts after the block');
});

test('failures spread out over time never block, and success clears the count', () => {
  let time = 0;
  const guard = createAuthGuard({ limit: 3, windowMs: 1000, blockMs: 5000, now: () => time });
  for (let i = 0; i < 10; i++) { assert.equal(guard.fail('a'), false); time += 600; }
  guard.fail('b'); guard.fail('b');
  guard.succeed('b');
  assert.equal(guard.fail('b'), false);
});

test('the source table stays bounded', () => {
  const guard = createAuthGuard({ maxSources: 3 });
  for (const source of ['a', 'b', 'c', 'd', 'e']) guard.fail(source);
  assert.equal(guard.size, 3);
});

test('forwarded addresses are trusted only from a tunnel on this PC', () => {
  const req = (remoteAddress, xff) => ({ socket: { remoteAddress }, headers: xff ? { 'x-forwarded-for': xff } : {} });
  assert.equal(clientAddress(req('192.168.1.50', '1.2.3.4')), '192.168.1.50');
  assert.equal(clientAddress(req('127.0.0.1', '9.9.9.9, 1.2.3.4')), '1.2.3.4');
  assert.equal(clientAddress(req('::ffff:192.168.1.22', '1.2.3.4'), ['192.168.1.22']), '1.2.3.4');
  assert.equal(clientAddress(req('127.0.0.1', 'not-an-ip')), '127.0.0.1');
  assert.equal(clientAddress(req('127.0.0.1')), '127.0.0.1');
});
