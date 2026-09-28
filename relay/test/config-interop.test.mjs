// The config exactly as the phone uploads it: the same string as
// INTEROP_JSON in shared/.../notify/RelayTest.kt. If either side changes
// shape, one of the two tests fails.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { configProblem } from '../src/relay.js';
import { announce } from '../src/announce.js';

const FROM_PHONE = '{"enabled":true,"webhookPath":"hook_abcdefghijklmnop","signingToken":"pk-signing-token","title":"Sample","names":{"uuid-alex":"Alex"},"friends":[{"type":"private","id":"p1","label":"Sam","followCode":{"name":"Sam","endpoint":"https://push.example/sam","p256dh":"PUB","auth":"AUTH"}},{"type":"simple","id":"s1","label":"Kit","topic":"pw_kit"}],"vapid":{"publicKey":"VPUB","privateKey":"VPRIV"},"ntfy":{"baseUrl":"https://ntfy.example.org","accessToken":"tk_x"}}';

test('the phone\'s upload is a valid relay config', () => {
  assert.equal(configProblem(JSON.parse(FROM_PHONE)), null);
});

test('its names map is keyed the way switch events name members', () => {
  const config = JSON.parse(FROM_PHONE);
  // CREATE_SWITCH carries member UUIDs (PluralKit's ModelRepository.Switch.cs).
  assert.equal(announce(['uuid-alex', 'uuid-bea'], config.names), 'Alex and someone else are fronting');
});

test('friends arrive tagged with their delivery mode', () => {
  const config = JSON.parse(FROM_PHONE);
  assert.deepEqual(config.friends.map((f) => f.type), ['private', 'simple']);
  assert.equal(config.friends[0].followCode.endpoint, 'https://push.example/sam');
});
