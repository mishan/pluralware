# Switch Notifications for Friends — Design

Status: proposal. Scope: let a PluralWare user share "who's fronting" with chosen friends as push
notifications, delivered by [ntfy](https://ntfy.sh). Friends need only the stock ntfy app, not
PluralWare. Phase A publishes from PluralWare itself; phase C, later and optional, adds a relay so
switches made in Discord or elsewhere notify too. No code written yet.

## 1. What it is

Today a friend learns about a switch from PluralKit's Discord bot or by asking. This adds a
**Share with friends** feature:

1. The user runs, or picks, an ntfy server: self-hosted, or a public one such as ntfy.sh.
2. On the phone app they add a friend and choose which members may be named to them. PluralWare
   creates a private topic for that friend and shows a QR code or link.
3. The friend scans it in the ntfy app. From then on, each switch the user makes in PluralWare
   sends that friend a notification like **"Alex and Bea are fronting"**.

The friend's watch gets it too, because Wear OS mirrors phone notifications.

## 2. Why ntfy

- **Friends install nothing of ours.** ntfy has Android, iOS and web clients, and subscribing is a
  URL. It's the same app whether the server is ntfy.sh or the user's own.
- **Self-hostable.** One binary, a small config file, per-user access control. A user who wants
  their fronting data on hardware they control can have that. The public ntfy.sh works for people
  who don't want to run anything.
- **Publishing is one HTTP request.** It fits the "no backend of our own" stance the rest of the
  app takes.

Alternatives considered:

| Option | Why not (for now) |
|---|---|
| Discord webhook | That's the status quo this is meant to complement; it also requires Discord. |
| Firebase Cloud Messaging | Needs a Google project and a backend we run, and friends would need PluralWare installed. |
| UnifiedPush | ntfy *is* a UnifiedPush distributor. It becomes relevant if PluralWare ever grows a friend-side app (section 11). |
| PluralKit dispatch → ntfy directly | Doesn't work: see section 7. |

## 3. Phases

| Phase | Publishes from | Catches | Infrastructure |
|---|---|---|---|
| **A** | The watch, right after it registers a switch | Switches made in PluralWare | An ntfy server (any) |
| **C** (optional, later) | A small relay receiving PluralKit's dispatch webhook | Every switch, from any client, within seconds | An ntfy server plus a relay the user hosts |

There is no phase B. The option between these two, a background job on the phone polling
PluralKit, was considered and dropped: Android's minimum periodic-job interval and Doze put its delays at 15 minutes or more, and it
costs battery for a worse version of what C does.

A and C must not both publish, or friends get every switch twice: turning on the relay turns off
publishing from the watch (section 8).

## 4. Server modes

The app supports two kinds of ntfy server, chosen at setup:

**Open server (e.g. ntfy.sh without an account).** Anyone who knows a topic name can read and
publish to it, so the **topic name is the secret**. PluralWare generates long random names
(section 5). The ntfy operator can read every message.

**Server with accounts (self-hosted, recommended).** The server denies anonymous access. The user
creates one write-only publisher account for PluralWare and one read-only account per friend.
Topic names are still random, as defense in depth, but no longer the only protection. A sketch of
the admin side:

```yaml
# server.yml
base-url: "https://ntfy.example.org"
auth-file: "/var/lib/ntfy/user.db"
auth-default-access: "deny-all"
```

```sh
# PluralWare publishes with a token; it can write to its topics but read none of them.
ntfy user add pluralware
ntfy access pluralware 'pw_*' write-only
ntfy token add pluralware              # → tk_…, pasted into PluralWare

# Per friend: an account that can read only their topic.
ntfy user add sam
ntfy access sam pw_k3v9q2r7x1m8b4n6c0t5 read-only
```

PluralWare can't create accounts on the user's server, since that needs admin access. So in this
mode, adding a friend in the app yields a topic name plus the `ntfy access` line to run. Automating
that would need the relay (section 7) or ntfy's admin API, and isn't in phase A.

## 5. Topics, one per friend

Each friend gets their own topic: `pw_` followed by 20 random characters from `[a-z0-9]`, from a
`SecureRandom`. That is about 100 bits, well past guessable. The prefix lets one `pw_*` rule cover
publishing.

Per-friend topics cost one publish per friend per switch, which is cheap for a handful of friends.
They buy:

- **Revocation of one friend** without touching anyone else: delete their topic from the app (and,
  with accounts, their user). With one shared topic, removing someone means rotating it and
  re-sharing with everyone who remains.
- **Per-friend member lists** later (section 6) at no extra design cost.
- A leaked topic exposes only what that one friend was allowed to see.

The friend subscribes with the topic URL, `https://<server>/<topic>`: opened in a browser it's the
ntfy web app, and in the ntfy mobile apps it goes into the "subscribe to topic" dialog (server plus
topic, and in accounts mode the friend's username and password). The phone shows it as a QR code
and a share sheet.

## 6. What gets shared

Fronting is sensitive, so everything defaults to off and is opt-in per member.

- **Sharing is off** until the user adds a friend.
- **Members are hidden unless chosen.** The user picks which members may be named. In a switch,
  members not chosen collapse into "someone":
  - **Alex and Bea are fronting**
  - **Alex and someone else are fronting**
  - **Someone is fronting**, when no named member is in front
  - **Switched out**
- **PluralKit's own privacy is respected.** Members whose PluralKit visibility is private start
  unselected and are labeled as private in the picker. If the system's front privacy is private, the
  sharing screen says so before the first friend is added. This means widening `MemberDto` and
  `SystemDto` with privacy fields, which CLAUDE.md asks to do deliberately; they drive only this
  screen.
- **Names are display labels** (`Member.displayLabel`), the same as everywhere else in the app.

v1 uses one member list for all friends. A per-friend list ("my partner sees everyone, my
coworker sees two members") is a natural follow-up, and per-friend topics already allow it.

The message carries a title ("PluralWare" or the system name, the user's choice) and the text above.
It carries no member IDs, no PluralKit IDs, and no timestamps beyond the one ntfy adds.

## 7. Why PluralKit can't post to ntfy directly

PluralKit can send each system's events (dispatch) to a webhook URL, which looks like it would give
real-time coverage for free by pointing it at an ntfy topic. It doesn't work, for three reasons:

- **Validation.** Every dispatch carries a `signing_token`. PluralKit periodically sends
  deliberately invalid `PING` events and expects a **401**; an endpoint that doesn't validate is
  removed. ntfy answers 200 to everything, so PluralKit would drop it.
- **IDs, not names.** A `CREATE_SWITCH` event carries the switch object, whose members are
  PluralKit IDs. Friends would get `["abcde", "fghij"]`.
- **It would leak the signing token.** The raw payload, token included, would be published to the
  topic.

Hence the relay in phase C: something that validates, translates and filters before ntfy sees
anything.

## 8. Phase A in detail

**Where publishing happens.** Switches are registered on the watch: the picker and History's
switch-back, both through `PluralKitRepository.registerSwitch`. The watch has its own network
connection, so it publishes directly. The phone registers no switches today; if it ever does, it
publishes the same way.

**Which switches.** Only switches this device just registered, never ones it merely observed on a
refresh. A switch made in Discord that the watch notices hours later on opening would otherwise go
out as if it had just happened, and the same switch could go out from two devices.

**When.** After a successful `registerSwitch`, fire and forget:

- one attempt per friend, plus a single retry on a network error;
- never blocks or fails the switch UI;
- nothing queued across restarts, because a notification about a switch from an hour ago is worse
  than none.

The switch itself needs the network, so a publish right after it usually succeeds.

**Request.** ntfy's JSON publishing: `POST https://<server>/` with `Content-Type: application/json`,
and in accounts mode `Authorization: Bearer tk_…`:

```json
{ "topic": "pw_k3v9q2r7x1m8b4n6c0t5", "title": "PluralWare", "message": "Alex and Bea are fronting" }
```

This uses the same OkHttp stack as the PluralKit client, with its own base URL and no PluralKit
token anywhere near it.

**Configuration lives on the phone and syncs to the watch.** Setup needs a real screen: server URL,
optional access token, friends, member choices, QR codes. The phone keeps the configuration and
pushes it to the watch over the Data Layer, like settings (`SettingsHandoff`), on its own path
(`/pluralware/sharing`). The ntfy access token is a credential, so on the watch it goes into the
encrypted store beside the PluralKit token, not into plain preferences. Signing out clears it with
everything else.

**Turning on the relay (phase C) turns this off**, as a single "Published by: this watch / relay"
setting, so friends never get duplicates.

## 9. Phase C: the relay

A small service the user hosts: a Cloudflare Worker, or a container next to their ntfy. It is the
only piece that receives PluralKit's dispatch.

1. **Receive and validate.** `POST /pk/<random path>`. Compare `signing_token` in constant time;
   answer 401 on a mismatch (this is what keeps PluralKit's `PING` check happy) and 200 otherwise.
2. **Filter.** Act on `CREATE_SWITCH` only; ignore the rest. Skip switches whose timestamp is more
   than a few minutes old: PluralKit lets switches be backdated, and imports create old ones in
   bulk.
3. **Translate.** Turn member IDs into display labels using a **name map the phone uploads**:
   `{ pluralkitId → label }` for shared members only. The relay never holds a PluralKit token. It
   can't read the system and can't act on it; it can only format what it's sent.
4. **Publish** to each friend's topic exactly as in phase A.

Configuration comes from the phone app over `PUT /config`, authenticated with a relay secret
generated at setup. It contains the name map, friend topics, ntfy URL, ntfy token and the signing
token. The phone re-uploads whenever the member choices or display names change.

User setup:

1. Deploy the relay (a template repo plus one-click Worker deploy would keep this short).
2. In PluralWare, enter the relay URL; the app generates and shows the webhook URL.
3. In Discord, run PluralKit's webhook command with that URL. PluralKit replies with the signing
   token, which the user pastes into the app.

Caveats to state in the setup screen:

- **PluralKit appears to allow one webhook per system** (confirm before building), so this
  displaces any other dispatch integration the user runs.
- If the relay goes down long enough, PluralKit removes the webhook, and the user has to re-run the
  command.

## 10. Threat model

| If this leaks or is compromised | Exposure | Mitigation |
|---|---|---|
| A friend's topic name | That friend's feed of shared-member switches | Accounts mode on the server; per-friend topics limit the blast; revoke by deleting the topic |
| The ntfy server (operator or breach) | Every shared message, in plaintext | Self-host; share only chosen members; nothing beyond display labels is sent |
| The publisher token (from the watch) | Posting fake switches to friends; **no** reading | `write-only` on `pw_*`; revoke with `ntfy token remove` |
| The relay | Fake notifications to friends; the name map | It holds no PluralKit token; the signing token only authenticates PluralKit to it |

ntfy has no end-to-end encryption (confirm against current ntfy before building). PluralWare could encrypt messages itself, but
then a stock ntfy client shows ciphertext, and friends would need PluralWare to read them. That
belongs with a friend-side app (section 11), not this.

## 11. Open questions and future options

- **Default title:** "PluralWare", the system name, or a user-chosen label? The system name
  identifies the system to anyone who reads the notification over a friend's shoulder.
- **Quiet hours / rate limit:** a system that switches often could flood a friend. Coalescing
  switches within a minute or so is cheap to add in both A and C.
- **Per-friend member lists** (section 6).
- **A friend-side PluralWare** ("see who's fronting for systems that share with me") could use
  UnifiedPush with encrypted payloads. That's a larger product decision; ntfy topics keep that door
  open without committing to it.
- **Self-hosting guide:** a short `docs/self-hosting-ntfy.md` with the server config, the publisher
  account and a per-friend example, linked from the sharing screen.

## 12. Phase A file-by-file change list

| File | Change |
|---|---|
| `shared/.../notify/NtfyPublisher.kt` | New. JSON publish over OkHttp, optional bearer token, single retry. |
| `shared/.../notify/SwitchAnnouncement.kt` | New. Pure `Switch` + shared-member set → message text ("someone" collapsing), unit-tested. |
| `shared/.../notify/SharingConfig.kt` | New. Server, token, friends and topics, shared member IDs; `SecureRandom` topic generation. |
| `shared/.../handoff/SharingHandoff.kt` | New. Phone → watch sync of the config on `/pluralware/sharing`. |
| `shared/.../api/dto/PluralKitDto.kt`, `model/Models.kt` | Widen with member visibility and system front privacy (section 6). |
| `mobile/.../ui/SharingScreen.kt` (+ ViewModel) | New. Server setup, friends, member picker, QR and share sheet. |
| `wear/.../WatchDataListenerService.kt` | Handle `/pluralware/sharing`; ntfy token into the encrypted store. |
| `wear/...` switch path | After a successful `registerSwitch` from this device, publish to each friend topic. |
| `wear/src/main/AndroidManifest.xml` | Add `/pluralware/sharing` to the listener's path filter. |
| Tests | Announcement text, topic generation, publisher request shape and auth header, "observed switches are never published". |
