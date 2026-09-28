// The invite and follow-code text formats, matching shared/.../notify/Invites.kt:
// `<prefix><base64url JSON>`, found anywhere in a message or a link's fragment.
// Loaded by the page; also run under Node by test/formats.test.mjs.
'use strict';

const INVITE_PREFIX = 'pluralware-invite:';
const FOLLOW_PREFIX = 'pluralware-follow:';

function b64urlEncode(bytes) {
  let binary = '';
  for (const b of bytes) binary += String.fromCharCode(b);
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function b64urlDecode(text) {
  const base64 = text.replace(/-/g, '+').replace(/_/g, '/');
  const binary = atob(base64 + '='.repeat((4 - (base64.length % 4)) % 4));
  return Uint8Array.from(binary, (c) => c.charCodeAt(0));
}

/** Finds `<prefix><base64url JSON>` anywhere in text and decodes it, or null. */
function decodeTagged(text, prefix) {
  const start = text.indexOf(prefix);
  if (start < 0) return null;
  const payload = /^[A-Za-z0-9_-]+/.exec(text.slice(start + prefix.length));
  if (!payload) return null;
  try {
    return JSON.parse(new TextDecoder().decode(b64urlDecode(payload[0])));
  } catch (e) {
    return null;
  }
}

function parseInvite(text) {
  const invite = decodeTagged(text, INVITE_PREFIX);
  if (!invite || typeof invite.system !== 'string' || typeof invite.vapid !== 'string') return null;
  try {
    if (b64urlDecode(invite.vapid).length !== 65) return null;
  } catch (e) {
    return null;
  }
  return invite;
}

function encodeFollowCode(code) {
  return FOLLOW_PREFIX + b64urlEncode(new TextEncoder().encode(JSON.stringify({ ...code, v: 1 })));
}

if (typeof module !== 'undefined') {
  module.exports = { INVITE_PREFIX, FOLLOW_PREFIX, b64urlEncode, b64urlDecode, decodeTagged, parseInvite, encodeFollowCode };
}
