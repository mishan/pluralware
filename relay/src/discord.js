// Posting to a Discord channel's webhook (docs/notifications-design.md §15).
// Mirrors shared/.../notify/DiscordWebhook.kt and DiscordSender.kt; both sides
// are tested against test/fixtures/announcements.json.

const MARKDOWN = /[\\*_~`|>#[\]()<-]/g;
// "1." starting a line would make a numbered list.
const LIST_NUMBER = /^([ \t]*\d+)\./gm;
const MAX_CONTENT = 2000;

/**
 * [text], an announcement, as the system titled [title] posts it: names shown
 * as typed rather than as Discord formatting, pinging nobody, without link
 * previews (flag 4).
 */
export function discordPayload(title, text) {
  const payload = { content: fit(escape(text)) };
  const name = poster(title);
  if (name) payload.username = name;
  payload.allowed_mentions = { parse: [] };
  payload.flags = 4;
  return payload;
}

/** The two parts of a webhook the phone uploads, in the shapes Discord gives them. */
export const isWebhookId = (s) => typeof s === 'string' && /^\d{17,20}$/.test(s);
export const isWebhookToken = (s) => typeof s === 'string' && /^[A-Za-z0-9_-]+$/.test(s);

/**
 * Posts to [friend]'s webhook: 'delivered', 'gone' (deleted or reset), or
 * 'failed: ...', which never names the webhook, as its address is its credential.
 */
export async function postToDiscord(fetch, friend, title, text) {
  // Always discord.com, rebuilt from the parts; wait, or Discord can answer success for a message it didn't keep.
  const url = `https://discord.com/api/webhooks/${friend.webhookId}/${friend.webhookToken}?wait=true`;
  const post = (payload) => fetch(url, {
    method: 'POST',
    headers: { 'content-type': 'application/json' },
    body: JSON.stringify(payload),
  });
  try {
    const payload = discordPayload(title, text);
    let response = await post(payload);
    // Discord bars more poster names than it documents: post as the webhook's own name instead.
    if (response.status === 400 && 'username' in payload && (await response.text()).includes('"username"')) {
      const { username, ...withoutName } = payload;
      response = await post(withoutName);
    }
    if (response.status === 404 || response.status === 401) return 'gone';
    return response.ok ? 'delivered' : `failed: HTTP ${response.status}`;
  } catch (e) {
    return `failed: couldn't reach Discord (${e.name})`;
  }
}

function escape(text) {
  return text.replace(MARKDOWN, (c) => `\\${c}`).replace(LIST_NUMBER, '$1\\.');
}

function fit(text) {
  if (text.length <= MAX_CONTENT) return text;
  let cut = text.slice(0, MAX_CONTENT - 1);
  // Not between the two halves of an emoji.
  if (/[\uD800-\uDBFF]$/.test(cut)) cut = cut.slice(0, -1);
  return `${cut}…`;
}

/** Discord refuses a poster's name with "discord" or "clyde" in it, or over 80 characters. */
function poster(title) {
  const name = (title ?? '').trim();
  return name && name.length <= 80 && !/discord|clyde/i.test(name) ? name : null;
}
