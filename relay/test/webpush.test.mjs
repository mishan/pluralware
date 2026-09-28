import { test } from 'node:test';
import assert from 'node:assert/strict';
import { b64urlDecode, b64urlEncode, encrypt, vapidAuthorization, OVERHEAD, VAPID_SUBJECT } from '../src/webpush.js';

const subtle = globalThis.crypto.subtle;
const b = b64urlDecode;
const cat = (...p) => { const o = new Uint8Array(p.reduce((n, x) => n + x.length, 0)); let i = 0; for (const x of p) { o.set(x, i); i += x.length; } return o; };

// RFC 8291 §5 and Appendix A.
const rfc = {
  plaintext: b('V2hlbiBJIGdyb3cgdXAsIEkgd2FudCB0byBiZSBhIHdhdGVybWVsb24'),
  asPublic: 'BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8',
  asPrivate: 'yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw',
  uaPublic: 'BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4',
  uaPrivate: 'q1dXpw3UpT5VOmu_cf_v6ih07Aems3njxI-JWgLcM94',
  auth: 'BTBZMqHH6r4Tts7J_aSIgg',
  salt: b('DGv6ra1nlYgDCS1FRnbzlw'),
  // Appendix A gives the header and the ciphertext separately.
  header: b('DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8'),
  ciphertext: b('8pfeW0KbunFT06SuDKoJH9Ql87S1QUrdirN6GcG7sFz1y1sqLgVi1VhjVkHsUoEsbI_0LpXMuGvnzQ'),
};

function jwk(publicB64, privateB64, extra = {}) {
  const pub = b(publicB64);
  return { kty: 'EC', crv: 'P-256', x: b64urlEncode(pub.slice(1, 33)), y: b64urlEncode(pub.slice(33)), ...(privateB64 ? { d: privateB64 } : {}), ...extra };
}

test('reproduces the RFC 8291 example message byte for byte', async () => {
  const ephemeral = {
    publicKey: await subtle.importKey('raw', b(rfc.asPublic), { name: 'ECDH', namedCurve: 'P-256' }, true, []),
    privateKey: await subtle.importKey('jwk', jwk(rfc.asPublic, rfc.asPrivate), { name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits']),
  };
  const message = await encrypt({ p256dh: rfc.uaPublic, auth: rfc.auth }, rfc.plaintext, { ephemeral, salt: rfc.salt, padding: 0 });
  assert.deepEqual(message, cat(rfc.header, rfc.ciphertext));
});

/** A receiver's side of RFC 8291, to open what the relay sends. */
async function decrypt(message, uaPublicB64, uaPrivateB64, authB64) {
  const salt = message.slice(0, 16);
  const asPublic = message.slice(21, 86);
  const uaPrivate = await subtle.importKey('jwk', jwk(uaPublicB64, uaPrivateB64), { name: 'ECDH', namedCurve: 'P-256' }, false, ['deriveBits']);
  const asKey = await subtle.importKey('raw', asPublic, { name: 'ECDH', namedCurve: 'P-256' }, false, []);
  const secret = new Uint8Array(await subtle.deriveBits({ name: 'ECDH', public: asKey }, uaPrivate, 256));
  const hmac = async (k, d) => new Uint8Array(await subtle.sign('HMAC', await subtle.importKey('raw', k, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']), d));
  const hkdf = async (s, ikm, info, n) => (await hmac(await hmac(s, ikm), cat(info, new Uint8Array([1])))).slice(0, n);
  const te = new TextEncoder();
  const ikm = await hkdf(b(authB64), secret, cat(te.encode('WebPush: info\0'), b(uaPublicB64), asPublic), 32);
  const cek = await hkdf(salt, ikm, te.encode('Content-Encoding: aes128gcm\0'), 16);
  const nonce = await hkdf(salt, ikm, te.encode('Content-Encoding: nonce\0'), 12);
  const key = await subtle.importKey('raw', cek, 'AES-GCM', false, ['decrypt']);
  const padded = new Uint8Array(await subtle.decrypt({ name: 'AES-GCM', iv: nonce }, key, message.slice(86)));
  let end = padded.length - 1;
  while (padded[end] === 0) end--;
  assert.equal(padded[end], 2, 'padding delimiter');
  return padded.slice(0, end);
}

test('pads to a size bucket, and the receiver gets the plaintext back', async () => {
  const text = new TextEncoder().encode('{"system":"Sample","text":"Alex is fronting","switchedAt":"2026-09-27T14:02:00Z","v":1}');
  const message = await encrypt({ p256dh: rfc.uaPublic, auth: rfc.auth }, text);
  assert.equal(message.length, 512);
  assert.deepEqual(await decrypt(message, rfc.uaPublic, rfc.uaPrivate, rfc.auth), text);

  const big = await encrypt({ p256dh: rfc.uaPublic, auth: rfc.auth }, new Uint8Array(512 - OVERHEAD + 1));
  assert.equal(big.length, 1024);
});

test('the VAPID token verifies and names the push origin', async () => {
  const pair = await subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign', 'verify']);
  const pubRaw = new Uint8Array(await subtle.exportKey('raw', pair.publicKey));
  const d = (await subtle.exportKey('jwk', pair.privateKey)).d;
  const vapid = { publicKey: b64urlEncode(pubRaw), privateKey: d };
  const now = Date.parse('2026-09-27T12:00:00Z');

  const header = await vapidAuthorization('https://web.push.apple.com/abc?x=1', vapid, now);
  const [, jwt, k] = /^vapid t=([^,]+), k=(.+)$/.exec(header);
  assert.equal(k, vapid.publicKey);
  const [h, c, s] = jwt.split('.');
  assert.ok(await subtle.verify({ name: 'ECDSA', hash: 'SHA-256' }, pair.publicKey, b(s), new TextEncoder().encode(`${h}.${c}`)));
  const claims = JSON.parse(new TextDecoder().decode(b(c)));
  assert.deepEqual(claims, { aud: 'https://web.push.apple.com', exp: now / 1000 + 12 * 3600, sub: VAPID_SUBJECT });
});
