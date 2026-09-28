// Web Push on WebCrypto alone: RFC 8291 payload encryption and RFC 8292 VAPID.
// Mirrors shared/.../notify/WebPush.kt (which uses Tink); same padding buckets.

const subtle = globalThis.crypto.subtle;
const enc = new TextEncoder();

export const OVERHEAD = 103; // header 86 + GCM tag 16 + padding delimiter 1
export const BUCKETS = [512, 1024, 2048, 4096];
const RECORD_SIZE = 4096;

// btoa/atob rather than Buffer: Workers have no Buffer.
export function b64urlEncode(bytes) {
  let binary = '';
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

export function b64urlDecode(text) {
  const base64 = text.replace(/-/g, '+').replace(/_/g, '/');
  const binary = atob(base64 + '='.repeat((4 - (base64.length % 4)) % 4));
  return Uint8Array.from(binary, (c) => c.charCodeAt(0));
}

function concat(...parts) {
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let at = 0;
  for (const p of parts) {
    out.set(p, at);
    at += p.length;
  }
  return out;
}

async function hmac(key, data) {
  const k = await subtle.importKey('raw', key, { name: 'HMAC', hash: 'SHA-256' }, false, ['sign']);
  return new Uint8Array(await subtle.sign('HMAC', k, data));
}

/** One block of HKDF-Expand (enough for every output here). */
async function hkdf(salt, ikm, info, length) {
  const prk = await hmac(salt, ikm);
  return (await hmac(prk, concat(info, new Uint8Array([1])))).slice(0, length);
}

/**
 * Encrypts `plaintext` to a receiver's keys, padded up to a size bucket.
 * `ephemeral`, `salt` and `padding` are for tests only (the RFC's example); leave them out.
 */
export async function encrypt({ p256dh, auth }, plaintext, { ephemeral, salt, padding } = {}) {
  const bucket = BUCKETS.find((b) => plaintext.length + OVERHEAD <= b);
  if (!bucket) throw new Error('payload too large for one Web Push record');
  padding ??= bucket - OVERHEAD - plaintext.length;
  const uaPublic = b64urlDecode(p256dh);
  const authSecret = b64urlDecode(auth);
  const uaKey = await subtle.importKey('raw', uaPublic, { name: 'ECDH', namedCurve: 'P-256' }, false, []);

  const as = ephemeral ?? await subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits']);
  const asPublic = new Uint8Array(await subtle.exportKey('raw', as.publicKey));
  const ecdhSecret = new Uint8Array(await subtle.deriveBits({ name: 'ECDH', public: uaKey }, as.privateKey, 256));

  const keyInfo = concat(enc.encode('WebPush: info\0'), uaPublic, asPublic);
  const ikm = await hkdf(authSecret, ecdhSecret, keyInfo, 32);
  salt ??= globalThis.crypto.getRandomValues(new Uint8Array(16));
  const cek = await hkdf(salt, ikm, enc.encode('Content-Encoding: aes128gcm\0'), 16);
  const nonce = await hkdf(salt, ikm, enc.encode('Content-Encoding: nonce\0'), 12);

  const padded = concat(plaintext, new Uint8Array([2]), new Uint8Array(padding));
  const key = await subtle.importKey('raw', cek, 'AES-GCM', false, ['encrypt']);
  const ciphertext = new Uint8Array(await subtle.encrypt({ name: 'AES-GCM', iv: nonce }, key, padded));

  const header = new Uint8Array(21);
  header.set(salt, 0);
  new DataView(header.buffer).setUint32(16, RECORD_SIZE);
  header[20] = asPublic.length;
  return concat(header, asPublic, ciphertext);
}

/** The system's VAPID private key as a WebCrypto signing key. */
async function vapidSigningKey({ publicKey, privateKey }) {
  const pub = b64urlDecode(publicKey);
  const jwk = {
    kty: 'EC',
    crv: 'P-256',
    d: privateKey,
    x: b64urlEncode(pub.slice(1, 33)),
    y: b64urlEncode(pub.slice(33, 65)),
  };
  return subtle.importKey('jwk', jwk, { name: 'ECDSA', namedCurve: 'P-256' }, false, ['sign']);
}

export const VAPID_SUBJECT = 'https://github.com/mishan/pluralware';

/** `Authorization` for a push to `endpoint`; WebCrypto's ECDSA already gives JWS's raw r‖s. */
export async function vapidAuthorization(endpoint, vapid, now = Date.now()) {
  const url = new URL(endpoint);
  const header = b64urlEncode(enc.encode(JSON.stringify({ typ: 'JWT', alg: 'ES256' })));
  const claims = b64urlEncode(enc.encode(JSON.stringify({
    aud: url.origin,
    exp: Math.floor(now / 1000) + 12 * 3600,
    sub: VAPID_SUBJECT,
  })));
  const input = `${header}.${claims}`;
  const signature = await subtle.sign({ name: 'ECDSA', hash: 'SHA-256' }, await vapidSigningKey(vapid), enc.encode(input));
  return `vapid t=${input}.${b64urlEncode(new Uint8Array(signature))}, k=${vapid.publicKey}`;
}
