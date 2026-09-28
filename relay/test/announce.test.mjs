// Mirrors shared/.../notify/SwitchAnnouncementTest.kt.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { announce } from '../src/announce.js';

const names = { a: 'Alex', b: 'Bea', c: 'Cy' };

test('switch-out', () => assert.equal(announce([], names), 'Switched out'));
test('one named member', () => assert.equal(announce(['a'], names), 'Alex is fronting'));
test('two named members', () => assert.equal(announce(['a', 'b'], names), 'Alex and Bea are fronting'));
test('three named members take a serial comma', () => assert.equal(announce(['a', 'b', 'c'], names), 'Alex, Bea, and Cy are fronting'));
test('front order is kept', () => assert.equal(announce(['b', 'a'], names), 'Bea and Alex are fronting'));
test('unshared members read as someone else', () => assert.equal(announce(['a', 'x'], names), 'Alex and someone else are fronting'));
test('how many are hidden is not revealed', () => assert.equal(announce(['a', 'x', 'y'], names), 'Alex and someone else are fronting'));
test('with nobody named, someone is fronting', () => assert.equal(announce(['x', 'y'], names), 'Someone is fronting'));
test('inherited object keys are not names', () => assert.equal(announce(['toString'], names), 'Someone is fronting'));
