// The relay's request handling, independent of where it runs: worker.js adapts
// it to Cloudflare Workers, server.mjs to Node. See README.md and
// docs/notifications-design.md §10.

import { announce } from './announce.js';
import { encrypt, vapidAuthorization } from './webpush.js';

/** Switches older than this are backdated or imported, not news (§10 step 2). */
export const MAX_SWITCH_AGE_MS = 5 * 60 * 1000;
/** Allowance for PluralKit's clock running ahead of ours. */
const MAX_CLOCK_SKEW_MS = 60 * 1000;
const TTL_SECONDS = 12 * 3600;
/** Gone friends are retried once a day, like the watch does (GoneFriends.kt). */
export const GONE_RETRY_MS = 24 * 3600 * 1000;

const json = (body, status = 200) => new Response(JSON.stringify(body), {
  status,
  headers: { 'content-type': 'application/json' },
});
const empty = (status) => new Response(null, { status });

/** Compares secrets without leaking, through timing, how much of a guess was right. */
export function sameSecret(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  const x = new TextEncoder().encode(a);
  const y = new TextEncoder().encode(b);
  let diff = x.length ^ y.length;
  for (let i = 0; i < Math.max(x.length, y.length); i++) diff |= (x[i] ?? 0) ^ (y[i] ?? 0);
  return diff === 0;
}

/**
 * An https URL on a public host name: what push endpoints and ntfy servers
 * are. Anything else (an IP literal, localhost, an internal name) would let a
 * follow code aim a self-hosted relay at its own network.
 */
export function isPublicHttpsUrl(text) {
  let url;
  try {
    url = new URL(text);
  } catch {
    return false;
  }
  if (url.protocol !== 'https:' || url.username || url.password) return false;
  const host = url.hostname.toLowerCase();
  if (host.startsWith('[') || /^[\d.]+$/.test(host)) return false; // IPv6 / IPv4 literal
  if (!host.includes('.')) return false; // localhost and other bare names
  return !/\.(localhost|local|internal|intranet|lan|home\.arpa)$/.test(host);
}

/** Checks the shape of a config the phone uploads; returns an error message or null. */
export function configProblem(c) {
  if (!c || typeof c !== 'object') return 'not an object';
  if (typeof c.webhookPath !== 'string' || !/^[A-Za-z0-9_-]{16,}$/.test(c.webhookPath)) return 'bad webhookPath';
  if (typeof c.signingToken !== 'string' || c.signingToken.length === 0) return 'missing signingToken';
  if (typeof c.names !== 'object' || c.names === null) return 'missing names';
  if (!Array.isArray(c.friends)) return 'missing friends';
  for (const f of c.friends) {
    if (typeof f.id !== 'string') return 'friend without id';
    if (f.type === 'private') {
      if (!c.vapid || !isPublicHttpsUrl(f.followCode?.endpoint)) return `friend ${f.id}: bad follow code`;
    } else if (f.type === 'simple') {
      if (!isPublicHttpsUrl(c.ntfy?.baseUrl) || typeof f.topic !== 'string') return `friend ${f.id}: bad ntfy setup`;
    } else {
      return `friend ${f.id}: unknown type`;
    }
  }
  return null;
}

/**
 * @param {object} deps
 * @param {{ get(key: string): Promise<any>, put(key: string, value: any): Promise<void>, delete(key: string): Promise<void> }} deps.store
 * @param {string} deps.adminSecret  guards /config and /status
 * @param {typeof fetch} [deps.fetch]
 * @param {() => number} [deps.now]
 * @returns {(request: Request) => Promise<{ response: Response, background?: Promise<void> }>}
 */
export function createRelay({ store, adminSecret, fetch = globalThis.fetch, now = Date.now }) {
  if (!adminSecret || adminSecret.length < 16) throw new Error('ADMIN_SECRET must be at least 16 characters');

  // State updates run one at a time, so two switches finishing together can't
  // lose each other's read-modify-write of the gone list.
  let updates = Promise.resolve();
  const serially = (fn) => {
    const run = updates.then(fn, fn);
    updates = run.catch(() => {});
    return run;
  };

  const isAdmin = (request) => {
    const header = request.headers.get('authorization') ?? '';
    return header.startsWith('Bearer ') && sameSecret(header.slice(7), adminSecret);
  };

  async function sendTo(friend, config, payload, text) {
    try {
      let response;
      if (friend.type === 'private') {
        const endpoint = friend.followCode.endpoint;
        response = await fetch(endpoint, {
          method: 'POST',
          headers: {
            'content-encoding': 'aes128gcm',
            'content-type': 'application/octet-stream',
            ttl: String(TTL_SECONDS),
            urgency: 'normal',
            authorization: await vapidAuthorization(endpoint, config.vapid, now()),
          },
          body: await encrypt(friend.followCode, payload),
        });
        if (response.status === 404 || response.status === 410) return 'gone';
      } else {
        response = await fetch(`${config.ntfy.baseUrl.replace(/\/+$/, '')}/`, {
          method: 'POST',
          headers: {
            'content-type': 'application/json',
            ...(config.ntfy.accessToken ? { authorization: `Bearer ${config.ntfy.accessToken}` } : {}),
          },
          body: JSON.stringify({ topic: friend.topic, title: config.title, message: text }),
        });
      }
      return response.ok ? 'delivered' : `failed: HTTP ${response.status}`;
    } catch (e) {
      return `failed: ${e.message}`;
    }
  }

  async function announceSwitch(config, sw) {
    const text = announce(sw.members ?? [], config.names);
    const payload = new TextEncoder().encode(JSON.stringify({
      system: config.title,
      text,
      switchedAt: sw.timestamp,
      v: 1,
    }));
    const at = now();
    const goneBefore = (await store.get('gone')) ?? {};
    const friends = config.friends.filter((f) => !(at - (goneBefore[f.id] ?? -Infinity) < GONE_RETRY_MS));
    const outcomes = await Promise.all(friends.map((f) => sendTo(f, config, payload, text)));
    await serially(async () => {
      const gone = (await store.get('gone')) ?? {};
      friends.forEach((f, i) => {
        if (outcomes[i] === 'gone') gone[f.id] = at;
        else if (outcomes[i] === 'delivered') delete gone[f.id];
      });
      await store.put('gone', gone);
      await store.put('last', { at: new Date(at).toISOString(), outcomes: Object.fromEntries(friends.map((f, i) => [f.id, outcomes[i]])) });
    });
  }

  async function dispatch(request, path) {
    const config = await store.get('config');
    // An unknown path looks like any other missing page.
    if (!config || !sameSecret(path, config.webhookPath)) return { response: empty(404) };
    let event;
    try {
      event = await request.json();
    } catch {
      return { response: empty(400) };
    }
    // PluralKit's own rule: an invalid token stops here with a 401, and its
    // periodic PINGs check that we do.
    if (!sameSecret(event?.signing_token, config.signingToken)) return { response: empty(401) };
    if (event.type !== 'CREATE_SWITCH' || !config.enabled) return { response: empty(200) };

    const sw = event.data ?? {};
    const at = Date.parse(sw.timestamp);
    const age = now() - at;
    if (Number.isNaN(at) || age > MAX_SWITCH_AGE_MS || age < -MAX_CLOCK_SKEW_MS) return { response: empty(200) };
    // Answer PluralKit at once; the sending carries on in the background.
    return { response: empty(200), background: announceSwitch(config, sw) };
  }

  return async function handle(request) {
    const url = new URL(request.url);
    const hook = /^\/pk\/([^/]+)$/.exec(url.pathname);
    if (hook && request.method === 'POST') return dispatch(request, hook[1]);

    if (url.pathname === '/config' || url.pathname === '/status') {
      if (!isAdmin(request)) return { response: empty(401) };
    }
    if (url.pathname === '/config' && request.method === 'PUT') {
      let config;
      try {
        config = await request.json();
      } catch {
        return { response: json({ error: 'not JSON' }, 400) };
      }
      const problem = configProblem(config);
      if (problem) return { response: json({ error: problem }, 400) };
      await serially(async () => {
        await store.put('config', config);
        // Forget gone marks for friends who are no longer in the list.
        const ids = new Set(config.friends.map((f) => f.id));
        const gone = (await store.get('gone')) ?? {};
        await store.put('gone', Object.fromEntries(Object.entries(gone).filter(([id]) => ids.has(id))));
      });
      return { response: empty(204) };
    }
    if (url.pathname === '/config' && request.method === 'DELETE') {
      await serially(() => Promise.all(['config', 'gone', 'last'].map((k) => store.delete(k))));
      return { response: empty(204) };
    }
    if (url.pathname === '/status' && request.method === 'GET') {
      const config = await store.get('config');
      return {
        response: json({
          configured: Boolean(config),
          enabled: Boolean(config?.enabled),
          gone: Object.keys((await store.get('gone')) ?? {}),
          last: (await store.get('last')) ?? null,
        }),
      };
    }
    return { response: empty(404) };
  };
}
