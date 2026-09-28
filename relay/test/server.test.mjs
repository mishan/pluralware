import { test } from 'node:test';
import assert from 'node:assert/strict';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { bodyLimit, fileStore } from '../src/server.mjs';

test('overlapping saves never corrupt the state file', async () => {
  const dir = await mkdtemp(join(tmpdir(), 'relay-state-'));
  try {
    const path = join(dir, 'state.json');
    const store = fileStore(path);
    const big = 'x'.repeat(3000);
    // What two switches finishing together, or a config upload during a
    // send, do. This corrupted the file most of the time before saves queued.
    await Promise.all(Array.from({ length: 50 }, (_, i) => store.put(`k${i % 5}`, { i, big })));
    const onDisk = JSON.parse(await readFile(path, 'utf8'));
    assert.equal(Object.keys(onDisk).length, 5);
    // A fresh store (a restart) reads the same state.
    assert.deepEqual(await fileStore(path).get('k0'), onDisk.k0);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test('bodies are capped by path, and nothing else gets one', () => {
  assert.equal(bodyLimit('/pk/abc'), 64 * 1024);
  assert.equal(bodyLimit('/config'), 1024 * 1024);
  assert.equal(bodyLimit('/status'), 0);
  assert.equal(bodyLimit('/anything'), 0);
});
