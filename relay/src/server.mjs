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

/** Everything in one JSON file, rewritten atomically; the relay's state is tiny. */
function fileStore(path) {
  let state = null;
  const load = async () => {
    if (state) return state;
    try {
      state = JSON.parse(await readFile(path, 'utf8'));
    } catch (e) {
      if (e.code !== 'ENOENT') throw e;
      state = {};
    }
    return state;
  };
  const save = async () => {
    await writeFile(`${path}.tmp`, JSON.stringify(state), { mode: 0o600 });
    await rename(`${path}.tmp`, path);
  };
  return {
    get: async (key) => (await load())[key] ?? null,
    put: async (key, value) => { (await load())[key] = value; await save(); },
    delete: async (key) => { delete (await load())[key]; await save(); },
  };
}

const handle = createRelay({ store: fileStore(file), adminSecret: process.env.ADMIN_SECRET });

createServer(async (req, res) => {
  try {
    const chunks = [];
    for await (const chunk of req) chunks.push(chunk);
    const body = chunks.length ? Buffer.concat(chunks) : undefined;
    const request = new Request(new URL(req.url, `http://${req.headers.host ?? 'localhost'}`), {
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
}).listen(port, () => console.log(`PluralWare relay on :${port}`));
