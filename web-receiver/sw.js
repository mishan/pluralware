// One registration of this worker per followed system, each with its own scope
// (./follow/<id>/) and so its own push subscription, bound to that system's
// VAPID key. The browser decrypts each push before it gets here.
'use strict';

importScripts('db.js');

/** The follow this registration belongs to, from its scope. */
function followId() {
  const match = /\/follow\/([^/]+)\/$/.exec(self.registration.scope);
  return match ? match[1] : null;
}

self.addEventListener('install', () => self.skipWaiting());
self.addEventListener('activate', (event) => event.waitUntil(self.clients.claim()));

self.addEventListener('push', (event) => {
  event.waitUntil((async () => {
    const id = followId();
    let payload = null;
    try {
      payload = event.data ? event.data.json() : null;
    } catch (e) {
      payload = null;
    }
    const follow = id ? await PwDb.get(id).catch(() => null) : null;
    // Browsers require every push to show a notification, so an unreadable one
    // still does, generically, rather than getting this registration penalized.
    if (!follow || !payload || typeof payload.text !== 'string') {
      await self.registration.showNotification('PluralWare', { body: 'A system you follow switched.' });
      return;
    }
    try {
      await PwDb.update(id, (f) => ({ ...f, lastText: payload.text, lastSwitchedAt: payload.switchedAt }));
    } catch (e) {
      // Never let bookkeeping cost the notification: browsers (Safari
      // especially) revoke subscriptions whose pushes show nothing.
    }
    const when = Date.parse(payload.switchedAt);
    await self.registration.showNotification(payload.system || follow.system, {
      body: payload.text,
      // One notification per system, replaced by the next switch.
      tag: `pluralware-${id}`,
      renotify: true,
      timestamp: Number.isNaN(when) ? undefined : when,
      icon: new URL('../../icon-192.png', self.registration.scope).href,
    });
    new BroadcastChannel('pluralware').postMessage({ type: 'updated', id });
  })());
});

self.addEventListener('notificationclick', (event) => {
  event.notification.close();
  const app = new URL('../../', self.registration.scope).href;
  event.waitUntil((async () => {
    const windows = await self.clients.matchAll({ type: 'window', includeUncontrolled: true });
    const open = windows.find((w) => w.url.startsWith(app));
    if (open) return open.focus();
    return self.clients.openWindow(app);
  })());
});
