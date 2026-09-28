// Cloudflare Workers entry point. The relay's state lives in a single Durable
// Object (SQLite-backed, which the free plan includes), not KV: KV reads can be
// a minute stale, which could fail PluralKit's check of a freshly uploaded
// signing token, and KV allows about one write per second per key. A Durable
// Object is strongly consistent, and stays alive to finish sending after it
// has answered PluralKit. Needs the RELAY binding and the ADMIN_SECRET secret
// (see README.md and wrangler.toml.example).

import { createRelay } from './relay.js';

function objectStore(storage) {
  return {
    get: async (key) => (await storage.get(key)) ?? null,
    put: (key, value) => storage.put(key, value),
    delete: (key) => storage.delete(key),
  };
}

export class RelayState {
  constructor(ctx, env) {
    this.handle = createRelay({ store: objectStore(ctx.storage), adminSecret: env.ADMIN_SECRET });
  }

  async fetch(request) {
    const { response, background } = await this.handle(request);
    // A Durable Object keeps running while I/O is pending, so the sends
    // finish after PluralKit has its answer.
    background?.catch((e) => console.error('sending failed:', e));
    return response;
  }
}

export default {
  fetch(request, env) {
    // One object holds the one system's state.
    return env.RELAY.get(env.RELAY.idFromName('relay')).fetch(request);
  },
};
