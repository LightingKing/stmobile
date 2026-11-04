import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { loadConfig, isPrivateIpv4 } from '../src/config.mjs';

test('LAN listener is restricted to explicit private IPv4 ranges', () => {
  for (const host of ['192.168.1.22', '10.1.2.3', '172.16.1.1', '172.31.255.254']) assert.equal(isPrivateIpv4(host), true);
  for (const host of ['0.0.0.0', '8.8.8.8', '172.15.1.1', '172.32.1.1', '192.168.evil.com', '192.168.1.300']) assert.equal(isPrivateIpv4(host), false);
});

test('private bootstrap preserves a stable token and verifies upstream review bytes', async () => {
  const dir = await mkdtemp(path.join(tmpdir(), 'pocket-config-'));
  try {
    const first = await loadConfig(dir);
    const second = await loadConfig(dir);
    assert.equal(first.token, second.token);
    assert.equal(first.reviewSkillSource.id, 'ss-stop-slop');
    assert.equal(first.backupKeep, 20);
    assert.equal(first.upstreamIdleTimeoutMs, 180_000);
    assert.ok(first.reviewSkillText.length > 0);
    assert.equal(first.host, '127.0.0.1');
    assert.equal(JSON.parse(await readFile(path.join(dir, 'config.json'))).token, undefined);
    const raw = JSON.parse(await readFile(path.join(dir, 'config.json')));
    await writeFile(path.join(dir, 'config.json'), JSON.stringify({ ...raw, upstreamBaseUrl: 'https://secret:password@example.com/v1' }));
    await assert.rejects(loadConfig(dir), /HTTPS/);
  } finally { await rm(dir, { recursive: true, force: true }); }
});

test('configs naming the retired reviewer still start, and bad limits are refused', async () => {
  const dir = await mkdtemp(path.join(tmpdir(), 'pocket-config-legacy-'));
  try {
    await loadConfig(dir);
    const raw = JSON.parse(await readFile(path.join(dir, 'config.json')));
    await writeFile(path.join(dir, 'config.json'), JSON.stringify({ ...raw, reviewSkill: 'sh-shuorenhua', backupKeep: 3, upstreamIdleTimeoutSec: 60 }));
    const legacy = await loadConfig(dir);
    assert.equal(legacy.reviewSkillSource.id, 'ss-stop-slop');
    assert.equal(legacy.backupKeep, 3);
    assert.equal(legacy.upstreamIdleTimeoutMs, 60_000);
    await writeFile(path.join(dir, 'config.json'), JSON.stringify({ ...raw, reviewSkill: 'none' }));
    assert.equal((await loadConfig(dir)).reviewSkillText, '');
    await writeFile(path.join(dir, 'config.json'), JSON.stringify({ ...raw, reviewSkill: 'unknown-skill' }));
    await assert.rejects(loadConfig(dir), /审校 Skill/);
    await writeFile(path.join(dir, 'config.json'), JSON.stringify({ ...raw, upstreamIdleTimeoutSec: 5 }));
    await assert.rejects(loadConfig(dir), /上游空闲超时/);
  } finally { await rm(dir, { recursive: true, force: true }); }
});
