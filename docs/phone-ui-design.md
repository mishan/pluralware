# Phone app: one home, settings in sections, per-friend sharing

The phone app grew screen by screen, and it shows: someone who installs it only to follow friends
meets a full-screen "connect your PluralKit token" first, the systems they follow sit on a screen
of their own, and sharing, ntfy and the relay share one long settings page. This redesign gives
every user one home screen, moves setup into settings sections, and lets each friend be shown
their own choice of members. Per-friend sharing is specified in docs/notifications-design.md §16.

Decisions from review: your system comes first on Home; a dismissed setup prompt leaves a
collapsed card, not nothing; a followed system's switch history is kept; a friend hears nothing
about a switch to members hidden from them; a new friend starts with every member, adjustable
before the invite goes out.

## 1. Navigation

One `NavHost` for everyone, no longer split into signed-out and signed-in graphs. A PluralKit
token only decides what the **Your system** card and its routes can do.

| Route | Screen |
|---|---|
| `home` | Your system, then Systems you follow |
| `pick`, `history`, `members/...` | As today; reachable only with a token |
| `connect` | As today's Connect screen, reached from the card or settings |
| `follow` | Follow someone: paste an invite |
| `followed/{instance}` | One followed system: who's fronting, and its switch history |
| `settings` | Sections (§4), each leading to a screen below |
| `settings/friends`, `settings/friends/add`, `settings/friends/{id}` | Friends, adding one, editing one |
| `settings/ntfy`, `settings/relay` | Delivery setup |

- **Keyed on the token:** the `NavHost` stays inside `key(token)`, as today, with `home` as the
  start whether there's a token or not. A token change or sign-out therefore still releases every
  screen's ViewModel, and with them the repository.
- **The session:** `Session` (repository and ViewModel store) is unchanged. The routes that need
  it are only added when it exists.
- **Notifications:** a friend's notification opens `followed/{instance}` for that system, with a
  request code per system so each notification opens its own. A system no longer followed lands
  on `home`.
- **The widget:** its Switch button with no token opens `home`, where the Your system card offers
  to connect.

## 2. Home

1. **Your system** card.
   - Connected: today's home content in a card: who's fronting and since when, and **Switch**,
     **History**, **Members**.
   - Not connected, never dismissed: expanded, with one line on what connecting gives, and
     **Connect** and **Not now**.
   - Not connected, dismissed: collapsed to one row ("Your system: not connected"); tapping it
     expands it again with **Connect**. Dismissal is a local setting; connecting clears it.
2. **Systems you follow** heading, with **Follow someone**. One card per followed system: its
   name, the latest text ("Alex is fronting") and since when; a problem line when its
   registration failed or its code changed (as Following shows today). Tapping opens
   `followed/{instance}`. With none, one line saying what following does.

## 3. Followed systems' history

Each received push is kept, per followed system, as a `FollowEvent(text, switchedAt, receivedAt)`:
the payload has no member list, only the worded announcement, so history is that text over time.

- Kept in the encrypted following store beside the follows, ordered by `receivedAt`, newest first,
  and capped at 100 events per system. Unfollowing deletes them. Each shows its `switchedAt`.
  Received time orders the list, because a correction's `switchedAt` can be older than what came
  before it. A push whose `switchedAt` and text match one already kept
  (a retry, or the relay and an app both sending) isn't added again.
- `followed/{instance}` shows the latest at the top as on the card, then the list, with the same
  date grouping History uses. Corrections ("Correction: ...") appear as their own entries.
- `Follow.lastText` and `lastSwitchedAt` stay as the card's source, so drawing Home doesn't read
  the history. They're updated only by a push whose `switchedAt` is at least as new, so a late
  delivery can't take the card back in time.

## 4. Settings, in sections

1. **Your system**: connected as (system name), **Disconnect**; or **Connect**.
2. **Watch**: today's watch status and **Send to watch**, shown only with Wear OS on the phone.
3. **Sharing**: the notification title; **Friends** (count) to `settings/friends`; a failed push
   of sharing to the watch. Until slice 3, today's global **Who may be named** stays here.
   - Without a token (follow-only, or after signing out a rejected token) this section and
     Delivery say "Connect your system to share your switches". Sharing needs your members, and
     friends kept for a reconnect are listed but can't be edited.
4. **Delivery**: **ntfy server** and **Relay**, each a row with its state ("Not set up",
   "ntfy.example.org", "Connected", the relay's error) leading to its screen. Their content is
   today's sections, moved.
5. **About**: version, privacy policy, source code.

`settings/friends` lists friends as today's Friends section does (method, problem line, last
failure), with **Add a friend**. Tapping one opens `settings/friends/{id}`: rename, **Who may be
named** for this friend, remove (with the Discord note). `settings/friends/add` is the flow in
§16.3 of the notifications design.

## 5. Slices

1. **One home.** The single nav graph, the Your system card (connected, expanded prompt,
   collapsed), Systems you follow with cards, the Follow screen, and `followed/{instance}` with
   the kept history. Following's screen is removed.
2. **Settings in sections.** The five sections; ntfy, relay and friends move to their own
   screens; friend detail with rename and remove. No change to what's shared yet.
3. **Per-friend sharing** (notifications-design §16), receivers first, so each part can be
   reverted alone:
   - 3a, no behavior change: the relay accepts `labels` and per-friend lists and reports its
     version, and PluralWare's Following and the browser follow page keep and echo `i`. The
     follow page is published on merge, ahead of app releases, which is fine since `i` is
     optional.
   - 3b: the watch applies per-friend lists, falling back to the global one, with the compact copy.
   - 3c: the phone's model, migration, announce rule, pending friends, the add and paste flows, and
     the upload with `labels`.

Tests: the phone's UI tests render each new screen's `...Content`; the history store and the
dedupe in `:shared`; slice 3's rules on both sides, as §16.6 lists.
