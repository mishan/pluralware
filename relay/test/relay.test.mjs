import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createRelay, sameSecret, MAX_SWITCH_AGE_MS } from '../src/relay.js';
import { b64urlEncode } from '../src/webpush.js';

const ADMIN = 'admin-secret-0123456789';
const NOW = Date.parse('2026-09-27T14:02:30Z');
const subtle = globalThis.crypto.subtle;

function memoryStore() {
  const m = new Map();
  return {
    get: async (k) => (m.has(k) ? structuredClone(m.get(k)) : null),
    put: async (k, v) => { m.set(k, structuredClone(v)); },
    delete: async (k) => { m.delete(k); },
  };
}

async function receiverKeys() {
  const pair = await subtle.generateKey({ name: 'ECDH', namedCurve: 'P-256' }, true, ['deriveBits']);
  return {
    p256dh: b64urlEncode(new Uint8Array(await subtle.exportKey('raw', pair.publicKey))),
    auth: b64urlEncode(globalThis.crypto.getRandomValues(new Uint8Array(16))),
  };
}

async function vapidKeys() {
  const pair = await subtle.generateKey({ name: 'ECDSA', namedCurve: 'P-256' }, true, ['sign']);
  return {
    publicKey: b64urlEncode(new Uint8Array(await subtle.exportKey('raw', pair.publicKey))),
    privateKey: (await subtle.exportKey('jwk', pair.privateKey)).d,
  };
}

async function setup({ pushStatus = 201, enabled = true } = {}) {
  const sent = [];
  const fetch = async (url, init) => {
    sent.push({ url: String(url), init });
    const status = String(url).startsWith('https://push.example/gone') ? 410 : String(url).startsWith('https://push.example') ? pushStatus : 200;
    return new Response(null, { status });
  };
  const store = memoryStore();
  const handle = createRelay({ store, adminSecret: ADMIN, fetch, now: () => NOW });
  const config = {
    v: 1,
    enabled,
    webhookPath: 'hook_abcdefghijklmnop',
    signingToken: 'pk-signing-token',
    title: 'Sample',
    names: { 'uuid-alex': 'Alex' },
    friends: [
      { type: 'private', id: 'p1', label: 'Sam', followCode: { name: 'Sam', endpoint: 'https://push.example/sam', ...(await receiverKeys()) } },
      { type: 'private', id: 'p2', label: 'Old', followCode: { name: 'Old', endpoint: 'https://push.example/gone', ...(await receiverKeys()) } },
      { type: 'simple', id: 's1', label: 'Kit', topic: 'pw_kit' },
    ],
    vapid: await vapidKeys(),
    ntfy: { baseUrl: 'https://ntfy.example.org/', accessToken: 'tk_x' },
  };
  const put = await handle(new Request('https://relay.example/config', {
    method: 'PUT',
    headers: { authorization: `Bearer ${ADMIN}` },
    body: JSON.stringify(config),
  }));
  assert.equal(put.response.status, 204);
  return { handle, sent, store, config };
}

function event(body, path = 'hook_abcdefghijklmnop') {
  return new Request(`https://relay.example/pk/${path}`, { method: 'POST', body: JSON.stringify(body) });
}

const switchEvent = (members, timestamp = '2026-09-27T14:02:00Z', token = 'pk-signing-token') => ({
  type: 'CREATE_SWITCH',
  signing_token: token,
  system_id: 'system-uuid',
  id: 'switch-uuid',
  data: { id: 'switch-uuid', timestamp, members },
});

test('PluralKit PINGs: 200 with the right token, 401 without', async () => {
  const { handle } = await setup();
  assert.equal((await handle(event({ type: 'PING', signing_token: 'pk-signing-token' }))).response.status, 200);
  assert.equal((await handle(event({ type: 'PING', signing_token: 'wrong' }))).response.status, 401);
  assert.equal((await handle(event({ type: 'PING' }))).response.status, 401);
});

test('an unknown webhook path is a plain 404, token or not', async () => {
  const { handle } = await setup();
  assert.equal((await handle(event(switchEvent(['uuid-alex']), 'hook_guessedwrongpath00'))).response.status, 404);
});

test('a new switch goes to every friend, each their own way', async () => {
  const { handle, sent, store } = await setup();
  const { response, background } = await handle(event(switchEvent(['uuid-alex', 'uuid-hidden'])));
  assert.equal(response.status, 200);
  await background;

  const push = sent.find((s) => s.url === 'https://push.example/sam');
  assert.equal(push.init.headers['content-encoding'], 'aes128gcm');
  assert.match(push.init.headers.authorization, /^vapid t=.+, k=.+$/);
  assert.equal(push.init.body.length, 512);

  const ntfy = sent.find((s) => s.url === 'https://ntfy.example.org/');
  assert.equal(ntfy.init.headers.authorization, 'Bearer tk_x');
  assert.deepEqual(JSON.parse(ntfy.init.body), { topic: 'pw_kit', title: 'Sample', message: 'Alex and someone else are fronting' });

  // The gone friend is remembered and skipped next time.
  assert.deepEqual(await store.get('gone'), ['p2']);
  sent.length = 0;
  await (await handle(event(switchEvent(['uuid-alex'])))).background;
  assert.ok(!sent.some((s) => s.url === 'https://push.example/gone'));
  assert.equal(sent.length, 2);
});

test('old or future switches are not news', async () => {
  const { handle, sent } = await setup();
  const old = new Date(NOW - MAX_SWITCH_AGE_MS - 1000).toISOString();
  const future = new Date(NOW + 5 * 60 * 1000).toISOString();
  for (const ts of [old, future, 'not a date']) {
    const { response, background } = await handle(event(switchEvent(['uuid-alex'], ts)));
    assert.equal(response.status, 200);
    assert.equal(background, undefined);
  }
  assert.equal(sent.length, 0);
});

test('other events are acknowledged and ignored', async () => {
  const { handle, sent } = await setup();
  for (const type of ['UPDATE_SWITCH', 'DELETE_SWITCH', 'UPDATE_MEMBER', 'CREATE_MESSAGE']) {
    const { response, background } = await handle(event({ type, signing_token: 'pk-signing-token', data: {} }));
    assert.equal(response.status, 200);
    assert.equal(background, undefined);
  }
  assert.equal(sent.length, 0);
});

test('a relay that isn\'t enabled validates but doesn\'t send', async () => {
  const { handle, sent } = await setup({ enabled: false });
  const { response, background } = await handle(event(switchEvent(['uuid-alex'])));
  assert.equal(response.status, 200);
  assert.equal(background, undefined);
  assert.equal(sent.length, 0);
});

test('config and status need the admin secret', async () => {
  const { handle } = await setup();
  for (const [method, path] of [['PUT', '/config'], ['DELETE', '/config'], ['GET', '/status']]) {
    const r = await handle(new Request(`https://relay.example${path}`, { method, headers: { authorization: 'Bearer nope' }, body: method === 'PUT' ? '{}' : undefined }));
    assert.equal(r.response.status, 401, `${method} ${path}`);
  }
  const status = await handle(new Request('https://relay.example/status', { headers: { authorization: `Bearer ${ADMIN}` } }));
  const body = await status.response.json();
  assert.equal(body.configured, true);
  assert.equal(body.enabled, true);
});

test('a malformed config is refused with the reason', async () => {
  const { handle } = await setup();
  const r = await handle(new Request('https://relay.example/config', {
    method: 'PUT',
    headers: { authorization: `Bearer ${ADMIN}` },
    body: JSON.stringify({ webhookPath: 'short', signingToken: 't', names: {}, friends: [] }),
  }));
  assert.equal(r.response.status, 400);
  assert.equal((await r.response.json()).error, 'bad webhookPath');
});

test('deleting the config signs the relay out', async () => {
  const { handle } = await setup();
  await handle(new Request('https://relay.example/config', { method: 'DELETE', headers: { authorization: `Bearer ${ADMIN}` } }));
  assert.equal((await handle(event({ type: 'PING', signing_token: 'pk-signing-token' }))).response.status, 404);
});

test('secrets compare equal only when equal', () => {
  assert.ok(sameSecret('abc', 'abc'));
  assert.ok(!sameSecret('abc', 'abd'));
  assert.ok(!sameSecret('abc', 'abcd'));
  assert.ok(!sameSecret('abc', undefined));
});

test('a short admin secret is refused at startup', () => {
  assert.throws(() => createRelay({ store: memoryStore(), adminSecret: 'short' }));
});
