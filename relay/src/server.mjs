// Node entry point, for running the relay yourself (Node 20+, no dependencies).
//
//   ADMIN_SECRET=… PORT=8787 STATE_FILE=./relay-state.json node src/server.mjs
//
// Put it behind a TLS-terminating reverse proxy: PluralKit and the phone must
// reach it over https.

import { createServer } from 'node:http';
import { readFile, rename, writeFile } from 'node:fs/promises';
import { createRelay } from './relay.js';

const file = process.env.STATE_FILE ?? './relay-state.json';
const port = Number(process.env.PORT ?? 8787);

/**
 * Everything in one JSON file, rewritten atomically; the relay's state is tiny.
 * Saves run one at a time: two overlapping writes to one temp file used to
 * interleave into an unparseable file, which failed every request after a
 * restart, PluralKit's checks included.
 */
export function fileStore(path) {
  let state = null;
  let loading = null;
  let saving = Promise.resolve();
  // Loaded once and shared: requests arriving together before the first load
  // finishes must all get the same object, or each would replace the others'.
  const load = () => {
    loading ??= readFile(path, 'utf8').then(
      (text) => { state = JSON.parse(text); return state; },
      (e) => {
        if (e.code !== 'ENOENT') { loading = null; throw e; }
        state = {};
        return state;
      },
    );
    return loading;
  };
  const save = () => {
    const run = saving.then(async () => {
      await writeFile(`${path}.tmp`, JSON.stringify(state), { mode: 0o600 });
      await rename(`${path}.tmp`, path);
    });
    saving = run.catch(() => {});
    return run;
  };
  return {
    get: async (key) => (await load())[key] ?? null,
    put: async (key, value) => { (await load())[key] = value; await save(); },
    delete: async (key) => { delete (await load())[key]; await save(); },
  };
}

/** Largest body worth reading, by path: dispatch events are small, configs a little bigger. */
export function bodyLimit(pathname) {
  if (pathname.startsWith('/pk/')) return 64 * 1024;
  if (pathname === '/config') return 1024 * 1024;
  return 0;
}

class TooLarge extends Error {}

/** Reads the body, refusing (before buffering it all) anything over `limit`. */
async function readBody(req, limit) {
  const declared = Number(req.headers['content-length'] ?? 0);
  if (declared > limit) throw new TooLarge();
  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > limit) throw new TooLarge();
    chunks.push(chunk);
  }
  return chunks.length ? Buffer.concat(chunks) : undefined;
}

async function onRequest(handle, req, res) {
  try {
    const url = new URL(req.url, `http://${req.headers.host ?? 'localhost'}`);
    let body;
    try {
      body = await readBody(req, bodyLimit(url.pathname));
    } catch (e) {
      if (!(e instanceof TooLarge)) throw e;
      res.writeHead(413, { connection: 'close' }).end();
      req.destroy();
      return;
    }
    const request = new Request(url, {
      method: req.method,
      headers: req.headers,
      body: req.method === 'GET' || req.method === 'HEAD' ? undefined : body,
    });
    const { response, background } = await handle(request);
    res.writeHead(response.status, Object.fromEntries(response.headers));
    res.end(Buffer.from(await response.arrayBuffer()));
    background?.catch((e) => console.error('sending failed:', e));
  } catch (e) {
    console.error(e);
    res.writeHead(500).end();
  }
}

function serve() {
  const handle = createRelay({ store: fileStore(file), adminSecret: process.env.ADMIN_SECRET });
  createServer((req, res) => onRequest(handle, req, res))
    .listen(port, () => console.log(`PluralWare relay on :${port}`));
}

// Serve when run directly; tests import the helpers above.
if (import.meta.url === `file://${process.argv[1]}`) serve();
