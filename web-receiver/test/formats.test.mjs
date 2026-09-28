// Run with: node --test 'web-receiver/test/*.test.mjs'
// The fixtures are shared with shared/.../notify/InvitesInteropTest.kt, so the
// web receiver and the apps can't drift apart on the wire format.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';

const require = createRequire(import.meta.url);
const f = require('../formats.js');

const VAPID = 'BAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIjJCUmJygpKissLS4vMDEyMzQ1Njc4OTo7PD0-P0A';
const INVITE = 'pluralware-invite:eyJzeXN0ZW0iOiJTYW1wbGUiLCJ2YXBpZCI6IkJBRUNBd1FGQmdjSUNRb0xEQTBPRHhBUkVoTVVGUllYR0JrYUd4d2RIaDhnSVNJakpDVW1KeWdwS2lzc0xTNHZNREV5TXpRMU5qYzRPVG83UEQwLVAwQSJ9';
const P256DH = 'BP79_Pv6-fj39vX08_Lx8O_u7ezr6uno5-bl5OPi4eDf3t3c29rZ2NfW1dTT0tHQz87NzMvKycjHxsXEw8LBwL8';
const AUTH = 'AAMGCQwPEhUYGx4hJCcqLQ';
const FOLLOW = 'pluralware-follow:eyJuYW1lIjoiU2FtIOKcqCIsImVuZHBvaW50IjoiaHR0cHM6Ly9mY20uZ29vZ2xlYXBpcy5jb20vZmNtL3NlbmQvYWJjOmRlZiIsInAyNTZkaCI6IkJQNzlfUHY2LWZqMzl2WDA4X0x4OE9fdTdlenI2dW5vNS1ibDVPUGk0ZURmM3QzYzI5cloyTmZXMWRUVDB0SFF6ODdOek12S3ljakh4c1hFdzhMQndMOCIsImF1dGgiOiJBQU1HQ1F3UEVoVVlHeDRoSkNjcUxRIiwidiI6MX0';

test('reads an invite the apps wrote', () => {
  assert.deepEqual(f.parseInvite(INVITE), { system: 'Sample', vapid: VAPID });
});

test('finds an invite in a link fragment or a message', () => {
  assert.equal(f.parseInvite(`#${INVITE}`).system, 'Sample');
  assert.equal(f.parseInvite(`https://example.org/pluralware/#${INVITE}`).system, 'Sample');
  assert.equal(f.parseInvite(`Follow us! ${INVITE}\n(from PluralWare)`).system, 'Sample');
});

test('writes the follow code the apps read', () => {
  const code = f.encodeFollowCode({
    name: 'Sam ✨',
    endpoint: 'https://fcm.googleapis.com/fcm/send/abc:def',
    p256dh: P256DH,
    auth: AUTH,
  });
  assert.equal(code, FOLLOW);
});

test('rejects what is not an invite', () => {
  assert.equal(f.parseInvite('hello'), null);
  assert.equal(f.parseInvite('pluralware-invite:%%%'), null);
  assert.equal(f.parseInvite(FOLLOW), null);
  // A VAPID key that isn't a 65-byte point.
  const short = f.INVITE_PREFIX + f.b64urlEncode(new TextEncoder().encode(JSON.stringify({ system: 'S', vapid: 'AAAA' })));
  assert.equal(f.parseInvite(short), null);
});

test('base64url round-trips every byte value', () => {
  const bytes = Uint8Array.from({ length: 256 }, (_, i) => i);
  assert.deepEqual(f.b64urlDecode(f.b64urlEncode(bytes)), bytes);
  assert.doesNotMatch(f.b64urlEncode(bytes), /[+/=]/);
});
