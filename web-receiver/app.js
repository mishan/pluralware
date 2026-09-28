// PluralWare's web receiver: follow a system from an invite, hand back a follow
// code, and show what arrives. See docs/notifications-design.md §4.5.
'use strict';

const $ = (id) => document.getElementById(id);

// --- what this browser can do ---

const isIos = /iPad|iPhone|iPod/.test(navigator.userAgent) ||
  (navigator.platform === 'MacIntel' && navigator.maxTouchPoints > 1);
const isStandalone = window.matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;

function pushSupport() {
  if (!('serviceWorker' in navigator)) return 'This browser has no service workers.';
  if (isIos && !isStandalone) return 'ios-install';
  if (!('PushManager' in window) || !('Notification' in window)) {
    return 'This browser doesn\'t support web push notifications.';
  }
  if (!window.isSecureContext) return 'Notifications need a secure (https) page.';
  return null;
}

// --- following ---

function scopeFor(id) {
  return new URL(`follow/${id}/`, location.href).href;
}

/**
 * Resolves once a new registration's worker is active, so it can subscribe.
 * Rejects if it fails to install (it goes "redundant", e.g. a script failed to
 * load) or takes too long, so the Follow button never stays stuck.
 */
function activated(registration, timeoutMs = 30000) {
  const worker = registration.installing || registration.waiting || registration.active;
  if (!worker || worker.state === 'activated') return Promise.resolve(registration);
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('The notification worker took too long to start. Try again.')), timeoutMs);
    worker.addEventListener('statechange', () => {
      if (worker.state === 'activated') {
        clearTimeout(timer);
        resolve(registration);
      } else if (worker.state === 'redundant') {
        clearTimeout(timer);
        reject(new Error('The notification worker failed to start. Check your connection and try again.'));
      }
    });
  });
}

async function follow(invite, myName) {
  // Must run inside the tap: browsers only prompt for permission on a user gesture.
  const permission = await Notification.requestPermission();
  if (permission !== 'granted') {
    throw new Error('Notifications are blocked for this page. Allow them in your browser settings, then try again.');
  }
  const id = crypto.randomUUID();
  const registration = await navigator.serviceWorker.register('sw.js', { scope: scopeFor(id) });
  let subscription;
  try {
    await activated(registration);
    subscription = await registration.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: b64urlDecode(invite.vapid),
    });
  } catch (e) {
    // Don't leave a half-made follow behind (e.g. an invite whose key isn't a
    // valid point); its registration would linger with no entry to remove it.
    await registration.unregister().catch(() => {});
    throw e;
  }
  const json = subscription.toJSON();
  const followCode = encodeFollowCode({
    name: myName || 'A friend',
    endpoint: json.endpoint,
    p256dh: json.keys.p256dh,
    auth: json.keys.auth,
  });
  await PwDb.put({ id, system: invite.system, vapid: invite.vapid, myName, followCode, lastText: null, lastSwitchedAt: null });
}

async function unfollow(id) {
  const registration = await navigator.serviceWorker.getRegistration(scopeFor(id));
  // Unregistering drops the push subscription; the sender sees 404/410 next time.
  if (registration) await registration.unregister();
  await PwDb.remove(id);
}

async function share(text) {
  if (navigator.share) {
    try {
      await navigator.share({ text });
      return;
    } catch (e) {
      if (e.name === 'AbortError') return;
    }
  }
  await navigator.clipboard.writeText(text);
  alert('Copied. Paste it to them in a private chat.');
}

// --- rendering ---

async function render() {
  const follows = await PwDb.all();
  const list = $('follows');
  list.replaceChildren();
  for (const f of follows) {
    const card = $('follow-card').content.firstElementChild.cloneNode(true);
    card.querySelector('.system').textContent = f.system;
    const waiting = !f.lastText;
    card.querySelector('.status').textContent = waiting ? 'Waiting for their first switch.' : '';
    card.querySelector('.last').textContent = f.lastText || '';
    const when = f.lastSwitchedAt ? new Date(f.lastSwitchedAt) : null;
    card.querySelector('.since').textContent = when && !Number.isNaN(when.getTime())
      ? `Since ${when.toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' })}`
      : '';
    const code = card.querySelector('.code');
    code.hidden = !waiting;
    card.querySelector('.share-code').addEventListener('click', () =>
      share(`Here's my PluralWare follow code. Paste it under Share with friends:\n\n${f.followCode}`));
    card.querySelector('.unfollow').addEventListener('click', async () => {
      if (!confirm(`Stop following ${f.system}?`)) return;
      await unfollow(f.id);
      render();
    });
    list.append(card);
  }
}

function showInvite(invite) {
  $('follow-title').textContent = invite ? `Follow ${invite.system}` : 'Follow a system';
}

async function main() {
  const fromLink = parseInvite(location.hash);
  if (fromLink) $('invite').value = location.hash.slice(1);
  showInvite(fromLink);
  $('invite').addEventListener('input', () => showInvite(parseInvite($('invite').value)));

  const unsupported = pushSupport();
  if (unsupported === 'ios-install') {
    $('ios-install').hidden = false;
    $('follow-button').disabled = true;
    if (fromLink) {
      const copy = $('copy-pending');
      copy.hidden = false;
      copy.addEventListener('click', () => navigator.clipboard.writeText(location.hash.slice(1)));
    }
  } else if (unsupported) {
    $('unsupported').hidden = false;
    $('unsupported-why').textContent = unsupported;
    $('follow').hidden = true;
    return;
  }

  $('follow-button').addEventListener('click', async () => {
    const invite = parseInvite($('invite').value);
    const error = $('follow-error');
    if (!invite) {
      error.textContent = 'That isn\'t a PluralWare invite.';
      return;
    }
    error.textContent = '';
    $('follow-button').disabled = true;
    try {
      await follow(invite, $('my-name').value.trim());
      $('invite').value = '';
      showInvite(null);
      // Drop the invite from the address bar so a reload doesn't follow twice.
      history.replaceState(null, '', location.pathname);
      await render();
    } catch (e) {
      error.textContent = e.message || String(e);
    } finally {
      $('follow-button').disabled = false;
    }
  });

  new BroadcastChannel('pluralware').addEventListener('message', render);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) render(); });
  await render();
}

main();
