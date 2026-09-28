// Cloudflare Workers entry point. Needs a KV namespace bound as RELAY_KV and a
// secret ADMIN_SECRET (see README.md and wrangler.toml.example).

import { createRelay } from './relay.js';

function kvStore(kv) {
  return {
    get: (key) => kv.get(key, 'json'),
    put: (key, value) => kv.put(key, JSON.stringify(value)),
    delete: (key) => kv.delete(key),
  };
}

export default {
  async fetch(request, env, ctx) {
    const handle = createRelay({ store: kvStore(env.RELAY_KV), adminSecret: env.ADMIN_SECRET });
    const { response, background } = await handle(request);
    if (background) ctx.waitUntil(background);
    return response;
  },
};
