// Followed systems, in IndexedDB so the page and the service worker share them.
// Loaded by the page with <script> and by the worker with importScripts().
'use strict';

const PwDb = (() => {
  const NAME = 'pluralware-following';
  const STORE = 'follows';

  function open() {
    return new Promise((resolve, reject) => {
      const req = indexedDB.open(NAME, 1);
      req.onupgradeneeded = () => req.result.createObjectStore(STORE, { keyPath: 'id' });
      req.onsuccess = () => resolve(req.result);
      req.onerror = () => reject(req.error);
    });
  }

  async function run(mode, fn) {
    const db = await open();
    try {
      return await new Promise((resolve, reject) => {
        const tx = db.transaction(STORE, mode);
        const result = fn(tx.objectStore(STORE));
        tx.oncomplete = () => resolve(result && 'result' in result ? result.result : undefined);
        tx.onerror = () => reject(tx.error);
        // An abort (e.g. over quota) fires no error event; without this the
        // promise would never settle.
        tx.onabort = () => reject(tx.error ?? new Error('transaction aborted'));
      });
    } finally {
      db.close();
    }
  }

  return {
    all: () => run('readonly', (s) => s.getAll()),
    get: (id) => run('readonly', (s) => s.get(id)),
    put: (follow) => run('readwrite', (s) => s.put(follow)),
    remove: (id) => run('readwrite', (s) => s.delete(id)),
    /** Applies `change` to one follow, if it still exists. */
    async update(id, change) {
      const follow = await this.get(id);
      if (follow) await this.put(change(follow));
      return follow;
    },
  };
})();
