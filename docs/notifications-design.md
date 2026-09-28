# Switch Notifications for Friends — Design

Status: all four rollout steps are built (section 3; what differs from this plan is in section 14).
Scope: let a PluralWare user share "who's fronting" with chosen friends as push
notifications. There are two delivery modes:

- **Private** (the default): each notification is end-to-end encrypted with standard Web Push
  encryption, so only the friend's device can read it. The friend receives it in PluralWare's phone
  app (via UnifiedPush) or in a small web app.
- **Simple**: plain messages on an [ntfy](https://ntfy.sh) topic, readable in the stock ntfy app
  by the friend and by whoever runs the server. Meant for a server you run yourself, or for friends
  who won't install anything.

Phase A sends from PluralWare itself. Phase C, later and optional, adds a relay the user hosts, so
switches made in Discord or elsewhere notify too.

## 1. What it is

Today a friend learns about a switch from PluralKit's Discord bot or by asking. This adds
**Share with friends**:

1. On the phone app the user chooses which members may be named, and invites a friend.
2. The friend accepts on their device. In Private mode that's PluralWare's phone app or the web
   receiver; in Simple mode it's the ntfy app.
3. From then on, each switch the user makes in PluralWare sends that friend a notification like
   **"Alex and Bea are fronting"**.

On Android, the friend's watch gets it too, because Wear OS mirrors phone notifications.

## 2. Building blocks

| Piece | Role here |
|---|---|
| **Web Push** (RFC 8030 delivery, RFC 8291 payload encryption, RFC 8292 VAPID sender identity) | The one protocol PluralWare sends in Private mode. The *receiver* generates the encryption keys; everything between sender and receiver relays ciphertext. |
| **UnifiedPush** | Android push without Google. Its Android spec requires RFC 8291-encrypted messages, so it *is* Web Push on the wire. A distributor app on the friend's phone holds the connection and hands ciphertext to our app, whose connector library decrypts it. |
| **ntfy** | Two jobs. In Private mode, the ntfy app is a UnifiedPush distributor and an ntfy server (the user's own, or ntfy.sh) carries ciphertext. In Simple mode it carries plain messages to the stock ntfy app. |
| **Browser push services** | Google, Mozilla and Apple run these for their browsers. They carry ciphertext to the web receiver. |

**ntfy has no end-to-end encryption of its own**, self-hosted or not: the request (ntfy issue #69)
has been open since 2021. Its server can read every message it carries, and caches messages for a
while (12 hours by default) unless a message is sent with `X-Cache: no`. That is why Private mode
encrypts before ntfy is involved, and why Simple mode is labeled as readable by the server.

Alternatives considered:

| Option | Why not (for now) |
|---|---|
| Our own encryption over ntfy topics | The stock ntfy app would show ciphertext, so friends need our receiver anyway, and then the standard encryption is the better choice. |
| Firebase Cloud Messaging directly | Needs a Google project and a backend we run. UnifiedPush can still fall back to FCM on phones without a distributor, carrying the same ciphertext (section 5). |
| Discord webhook | That's the status quo this is meant to complement; it also requires Discord. |
| PluralKit dispatch → ntfy directly | Doesn't work: see section 9. |

## 3. Modes, phases and rollout

The two modes are orthogonal to the two phases, which decide *who sends*:

| Phase | Sends from | Catches | Infrastructure |
|---|---|---|---|
| **A** | The watch, right after it registers a switch | Switches made in PluralWare | None for Private (public push services, or the friend's ntfy server); an ntfy server for Simple |
| **C** (optional, later) | A small relay receiving PluralKit's dispatch webhook | Every switch, from any client, within seconds | The relay, which the user hosts |

There is no phase B. The option between these two, a background job on the phone polling
PluralKit, was dropped: Android's minimum periodic-job interval and Doze put its delays at 15
minutes or more, and it costs battery for a worse version of what C does.

Rollout, each step usable on its own:

1. **Shared core and Simple mode.** Member choices, message text and the send path on the watch,
   delivered as plain ntfy messages. The smallest useful slice, and enough for personal use on a
   self-hosted server.
2. **Private mode, Android receiver.** Web Push encryption and VAPID signing on the sending side;
   a "Following" section in PluralWare's phone app, registered through UnifiedPush.
3. **Private mode, web receiver.** A static web app for iPhone, desktop, and Android friends who'd
   rather not install PluralWare.
4. **Phase C relay**, if friends miss switches made off the watch.

## 4. Private mode

### 4.1 Keys and the follow-code handshake

In Web Push the **receiver** generates the encryption keys: a P-256 key pair (`p256dh`) and a
16-byte `auth` secret. The receiver also gets a push **endpoint**, a URL at its push service. The
sender needs all three for each friend, so the handshake runs in the opposite direction from Simple
mode:

1. **Invite.** The user's phone makes an invite link:
   `https://<receiver host>/follow#<base64url JSON>`. The JSON holds a display label for the system
   and the system's VAPID public key (section 4.3), plus the relay URL and a one-time token if the
   relay is on. It rides in the URL **fragment**, which browsers don't send to the server hosting the
   page. The user sends the link however they like.
2. **Subscribe.** The friend opens it:
   - In a browser, the web receiver subscribes through the Push API, passing the VAPID public key.
   - With PluralWare installed, the page offers "Open in PluralWare". The app registers through
     UnifiedPush with the same key.
3. **Follow code.** The receiver shows a **follow code**, a QR code plus copyable text encoding
   `{ endpoint, p256dh, auth, friend's label }`, and the friend gets it back to the user. Scanning
   in person is the easy path; pasting it through a private chat works too.
   - With the relay on, the receiver instead posts the code straight to the relay using the invite's
     one-time token, so the friend taps once and is done.
4. **Add.** The user's phone stores the friend and syncs them to the watch (section 8).

A follow code contains the friend's `auth` secret, so it's treated as a credential: it goes only
through the invite's own channel, is stored encrypted, and is never logged.

### 4.2 Sending

For each friend: build the payload, encrypt it to their keys (RFC 8291, `aes128gcm`), sign a VAPID
JWT (RFC 8292), and `POST` to their endpoint:

```
POST <endpoint>
Content-Encoding: aes128gcm
Authorization: vapid t=<JWT>, k=<VAPID public key>
TTL: 43200
Urgency: normal

<ciphertext>
```

The plaintext is small JSON:

```json
{ "v": 1, "system": "<label>", "text": "Alex and Bea are fronting", "switchedAt": "2026-09-27T14:02:00Z" }
```

- **Padded** to a fixed size bucket (512 bytes, or the next bucket up for unusually long names)
  before encryption, so the ciphertext's length doesn't give away how many names are in it.
- **TTL of 12 hours.** A friend whose device is offline still gets the latest switch when it
  reconnects. The receiver shows `switchedAt` ("since 2:02 PM") and **replaces** the previous
  notification for that system, so a backlog collapses to the current state instead of a stack of
  stale ones.
- **Gone subscriptions clean themselves up.** A `404` or `410` from an endpoint means the friend
  unsubscribed or reinstalled; the sender drops that friend's subscription and shows it on the
  sharing screen.

**Encryption uses Tink's `apps-webpush`** (`com.google.crypto.tink:apps-webpush`, 1.15.0 when
evaluated). It was evaluated in a scratch build:

- **Correct.** Its decrypter recovers RFC 8291's own example message, and encrypt/decrypt round-trips
  with receiver keys generated by the platform's JCA.
- **Padding fits the design.** `withPaddingSize` adds a fixed number of bytes, so building one
  encrypter per message with `bucket − overhead − plaintext` padding produces ciphertext of exactly
  the bucket size (overhead is 103 bytes: an 86-byte header, a 16-byte tag and the delimiter).
- **Small and maintained.** Four source files over Tink's primitives, which validate curve points
  on decode. It adds about 49 KB to the release watch APK after R8.
- **Encryption only.** It has no VAPID support. The ES256 JWT is a few lines with JCA
  (`SHA256withECDSA`), plus Tink's `EllipticCurves.ecdsaDer2Ieee` to turn JCA's DER signature into
  the raw form JWTs use.

The dependency needs one adjustment. `apps-webpush` depends on Java `tink`, while `security-crypto`
brings `tink-android`. The two contain the same classes, so the build fails with duplicate-class
errors. The UnifiedPush connector (section 4.4) depends on Java `tink` too, so Tink 1.23 arrives
with the Android receiver either way. The fix is the same everywhere: exclude `tink` and pin
`tink-android` to the version these libraries are built against.

```kotlin
implementation(libs.tink.apps.webpush) { exclude(group = "com.google.crypto.tink", module = "tink") }
implementation(libs.tink.android) // 1.23.0; also lifts security-crypto's 1.8.0
```

That lifts `EncryptedSharedPreferences` from Tink 1.8 to 1.23. Tink keeps its 1.x API compatible,
and the build and unit tests pass, but token storage runs on the Keystore, so it needs a check on a
real watch and phone first: an existing token still reads after the upgrade, and a new one saves.
This also lines up with moving off the deprecated `security-crypto`, which could then use
`tink-android` directly.

### 4.3 The VAPID key

Push subscriptions are **bound to the VAPID public key** they were created with, and a push signed
with any other key is rejected. So every sender for a system must share one VAPID key: the watch in
phase A, the phone if it ever sends, and the relay in phase C.

That rules out a non-extractable Keystore key made on the watch. Instead the phone generates one
P-256 key per system when sharing is first turned on. It keeps the key in its encrypted store,
syncs it to the watch like the ntfy token (section 8), and uploads it to the relay if C is enabled.

What the key can do if it leaks: someone who also has the subscriptions can push notifications to
those friends that pass the sender check. It can't decrypt anything, since the payload keys belong
to the receivers. "Reset sharing" generates a new key, after which friends must follow again.

### 4.4 The Android receiver (in PluralWare)

A **Following** section in the phone app lists the systems this person follows and each one's
latest fronter line.

- It registers through the UnifiedPush connector library (`org.unifiedpush.android:connector`,
  one new dependency). The connector decrypts messages, and our code only posts the notification,
  using one notification per followed system, replaced on each switch.
- The friend chooses a distributor: typically the ntfy app pointed at any ntfy server, the
  PluralWare user's own included. Every ntfy server along the way sees only ciphertext.
- With no distributor installed, the connector can fall back to an embedded FCM distributor. That
  routes through Google, still as ciphertext. The follow screen says which route is in use.

### 4.5 The web receiver

A static web app with no backend: an HTML page, a web app manifest and a service worker. It can be
hosted on GitHub Pages from this repo, or self-hosted by anyone who'd rather not trust ours.

- It subscribes with `PushManager.subscribe({ userVisibleOnly: true, applicationServerKey })`. The
  browser decrypts incoming pushes, and the service worker calls `showNotification` with the system
  label as `tag`, so each switch replaces the last.
- Followed systems live in IndexedDB. Unfollowing unsubscribes, which the sender then sees as a
  `410`.
- **iPhone and iPad** support Web Push from iOS 16.4, but only for a web app added to the Home
  Screen. The page detects this and walks the friend through adding it first.
- Its trust boundary is whoever serves the page, since the page runs with the friend's keys. That's
  why it's small, dependency-free and self-hostable.

### 4.6 What the carriers still see

Encryption hides content, not traffic. The push service (or ntfy server) in the path still sees:

- that some sender pushes to this endpoint, and when;
- the padded message size.

Timing correlates with switches. A friend who needs to hide even the fact of following someone
would want a distributor on a server they trust. Simple mode doesn't improve any of this.

## 5. Simple mode

Plain ntfy messages to one topic per friend, readable by the stock ntfy app, by the friend, and by
whoever runs the server. It's the right choice on a server the user runs themselves, and a
deliberate, labeled compromise anywhere else.

### 5.1 Servers

**Open server (e.g. ntfy.sh without an account).** Anyone who knows a topic name can read and
publish to it, so the **topic name is the secret**.

**Server with accounts (self-hosted, recommended).** The server denies anonymous access. The user
creates one write-only publisher account for PluralWare and one read-only account per friend:

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
mode, adding a friend in the app yields a topic name plus the `ntfy access` line to run.

### 5.2 Topics, one per friend

Each friend gets their own topic: `pw_` followed by 20 random characters from `[a-z0-9]`, from a
`SecureRandom`. That is about 100 bits, well past guessable, and the prefix lets one `pw_*` rule
cover publishing. One topic per friend means:

- one friend can be revoked without re-sharing with anyone else;
- per-friend member lists become possible later;
- a leaked topic exposes only that one friend's feed.

The friend subscribes with the topic URL, `https://<server>/<topic>`: opened in a browser it's the
ntfy web app, and in the ntfy mobile apps it goes into the "subscribe to topic" dialog (with the
friend's username and password on a server with accounts). The phone shows it as a QR code and a
share sheet.

### 5.3 Sending

ntfy's JSON publishing: `POST https://<server>/`, with `Content-Type: application/json` and, on a
server with accounts, `Authorization: Bearer tk_…`:

```json
{ "topic": "pw_k3v9q2r7x1m8b4n6c0t5", "title": "PluralWare", "message": "Alex and Bea are fronting" }
```

## 6. What gets shared

Fronting is sensitive, so everything defaults to off, and naming is opt-in per member. This holds
in both modes.

- **Sharing is off** until the user adds a friend.
- **Members are hidden unless chosen.** In a switch, members not chosen collapse into "someone":
  - **Alex and Bea are fronting**
  - **Alex and someone else are fronting**
  - **Someone is fronting**, when no named member is in front
  - **Switched out**
- **PluralKit's own privacy is respected.** Members whose PluralKit visibility is private start
  unselected and are labeled private in the picker. If the system's front privacy is private, the
  sharing screen says so before the first friend is added. This means widening `MemberDto` and
  `SystemDto` with privacy fields, which CLAUDE.md asks to do deliberately; they drive only this
  screen.
- **Names are display labels** (`Member.displayLabel`), the same as everywhere else in the app.
- **Nothing else goes out:** no member IDs, no PluralKit IDs.

v1 has one member list for all friends. Per-friend lists ("my partner sees everyone, my coworker
sees two members") are a natural follow-up; both modes already address each friend separately.

## 7. Sending from the watch (phase A)

**Where.** Switches are registered on the watch: the picker and History's switch-back, both through
`PluralKitRepository.registerSwitch`. The watch has its own network connection, so it sends
directly. The phone registers no switches today; if it ever does, it sends the same way.

**Which switches.** Only switches this device just registered, never ones it merely observed on a
refresh. A switch made in Discord that the watch notices hours later would otherwise go out as if it
had just happened, and the same switch could go out from two devices.

**When.** After a successful `registerSwitch`, fire and forget:

- one attempt per friend, plus a single retry on a network error;
- never blocks or fails the switch UI;
- nothing queued across restarts. Private mode's TTL (section 4.2) is what covers friends who are
  offline, on the carrier's side rather than ours.

**Turning on the relay (phase C) turns this off**, as a single "Sent by: this watch / relay"
setting, so friends never get duplicates.

## 8. Configuration and sync

Setup needs a real screen: mode, server, friends, member choices, invites, QR codes. So the
configuration lives on the phone and syncs to the watch over the Data Layer, like settings
(`SettingsHandoff`), on its own path (`/pluralware/sharing`).

Its secrets go into the encrypted stores on both devices, beside the PluralKit token, and never into
plain preferences:

- the VAPID private key;
- the friends' follow codes (they include `auth` secrets);
- the ntfy access token.

Signing out clears them with everything else, and "Reset sharing" clears them deliberately.

## 9. Why PluralKit can't post to ntfy directly

PluralKit can send each system's events (dispatch) to a webhook URL, which looks like real-time
coverage for free by pointing it at an ntfy topic. It doesn't work, for three reasons:

- **Validation.** Every dispatch carries a `signing_token`. PluralKit periodically sends
  deliberately invalid `PING` events and expects a **401**; an endpoint that doesn't validate is
  removed. ntfy answers 200 to everything, so PluralKit would drop it.
- **IDs, not names.** A `CREATE_SWITCH` event carries the switch object, whose members are
  PluralKit IDs.
- **It would leak the signing token.** The raw payload, token included, would be published to the
  topic.

It also couldn't do Private mode at all, which needs per-friend encryption. Hence the relay.

## 10. Phase C: the relay

A small service the user hosts: a Cloudflare Worker, or a container next to their ntfy. It is the
only piece that receives PluralKit's dispatch, and it sends in whichever mode each friend uses.

1. **Receive and validate.** `POST /pk/<random path>`. Compare `signing_token` in constant time;
   answer 401 on a mismatch (this is what keeps PluralKit's `PING` check happy) and 200 otherwise.
2. **Filter.** Act on `CREATE_SWITCH` only. Skip switches whose timestamp is more than a few
   minutes old: PluralKit lets switches be backdated, and imports create old ones in bulk.
3. **Translate.** Turn members into display labels using a **name map the phone uploads**:
   `{ member UUID → label }`, for shared members only. (`CREATE_SWITCH` names members by UUID, per
   PluralKit's `ModelRepository.Switch.cs`.) The relay never holds a PluralKit token, so it can't
   read the system or act on it.
4. **Send** to each friend exactly as in section 4.2 or 5.3.

Configuration comes from the phone over `PUT /config`, authenticated with a relay secret generated
at setup. It holds the name map, the friends (follow codes or topics), the VAPID key, the ntfy URL
and token, and the signing token. The phone re-uploads whenever member choices or display names
change. The relay also accepts follow codes posted by receivers with a valid one-time invite token
(section 4.1).

The relay formats the text, so it necessarily sees names and fronters in plaintext. It is the
user's own server, the same trust as a self-hosted ntfy in Simple mode.

User setup:

1. Deploy the relay (a template repo plus a one-click Worker deploy would keep this short).
2. In PluralWare, enter the relay URL; the app generates and shows the webhook URL.
3. In Discord, run PluralKit's webhook command with that URL. PluralKit replies with the signing
   token, which the user pastes into the app.

Caveats to state in the setup screen:

- **PluralKit allows one webhook per system** (a single `WebhookUrl` on the system), so this
  displaces any other dispatch integration the user runs.
- If the relay goes down long enough, PluralKit removes the webhook, and the user has to re-run the
  command.

## 11. Threat model

| If this leaks or is compromised | Private mode | Simple mode |
|---|---|---|
| The carrier (push service, or an ntfy server) | Timing and padded size only (section 4.6) | Every message, in plaintext |
| A friend's follow code, or their topic name | Forged pushes to that friend. Browser push services also demand the VAPID key; a carrier that doesn't check VAPID doesn't. No reading: that needs the friend's private key, which never leaves their device | That friend's feed; revoke by deleting the topic |
| The VAPID key | Nothing on its own; with friends' follow codes too, forged pushes to them. No reading. "Reset sharing" rotates it | n/a |
| The ntfy publisher token | n/a | Fake messages to friends; `write-only` means no reading. `ntfy token remove` |
| The web receiver's host | Could serve a page that leaks the friend's feed. Keep it small and self-hostable | n/a |
| The relay | Plaintext of what it formats, the name map, and the ability to send; no PluralKit token | Same |

## 12. Open questions and future options

- **Default title:** "PluralWare", the system name, or a user-chosen label? The system name
  identifies the system to anyone who reads the notification over a friend's shoulder.
- **Rate limit:** a system that switches often could flood a friend. Coalescing switches within a
  minute or so is cheap in both phases, and Private mode's replace-by-tag already hides the backlog.
- **Per-friend member lists** (section 6).
- **Richer following:** the Android receiver could grow into a small "friends' fronters" view, and
  even a watch complication for a followed system.
- **Self-hosting guide:** `docs/self-hosting.md`, covering ntfy for Simple mode and as a UnifiedPush
  server, the relay, and serving the web receiver.

## 13. File-by-file change list

Rollout steps 1 and 2 (section 3):

| File | Change |
|---|---|
| `shared/.../notify/SwitchAnnouncement.kt` | New. Pure `Switch` + shared-member set → text ("someone" collapsing) and the Private payload JSON with padding. Unit-tested. |
| `shared/.../notify/NtfySender.kt` | New. Simple mode: JSON publish with an optional bearer token, single retry. |
| `shared/.../notify/WebPushSender.kt` | New. Private mode: encryption via Tink `apps-webpush`, VAPID JWT, the POST, and 404/410 handling. Tested against RFC 8291's published example. |
| `gradle/libs.versions.toml`, `shared/build.gradle.kts` | Tink `apps-webpush` and `tink-android` 1.23, with Java `tink` excluded (section 4.2). |
| `shared/.../notify/SharingConfig.kt` | New. Mode, friends (follow codes or topics), shared member IDs, VAPID key; topic generation. |
| `shared/.../handoff/SharingHandoff.kt` | New. Phone → watch sync on `/pluralware/sharing`. |
| `shared/.../api/dto/PluralKitDto.kt`, `model/Models.kt` | Widen with member visibility and system front privacy (section 6). |
| `mobile/.../ui/sharing/` | New. Sharing setup, friends, member picker, invite links, follow-code scanning. |
| `mobile/.../ui/following/` + UnifiedPush receiver | New. The Following section and notification posting (section 4.4). |
| `wear/.../WatchDataListenerService.kt`, manifest | Handle `/pluralware/sharing`; secrets into the encrypted store. |
| `wear/...` switch path | After a successful `registerSwitch` from this device, send to each friend. |
| Tests | Announcement text and padding, RFC 8291 vectors, VAPID JWT shape, topic generation, "observed switches are never sent". |

Rollout step 3 adds a `web-receiver/` directory holding the static web app. Step 4 adds the relay,
in its own repository or a `relay/` directory.

## 14. As built

Where the code lives:

- `shared/.../notify/`: the config and its encrypted store, invites and follow codes, the
  announcement text, Web Push encryption and VAPID, both senders, and `SwitchSharer`, which fans a
  switch out to every friend. Also the receiving side's `Following` store.
- `PluralKitRepository`'s `onSwitchRegistered` hook fires only for switches the repository
  registers, which is how "observed switches are never sent" is enforced (and tested).
- Watch: `WatchSharing` sends from a process-wide scope. `WatchDataListenerService` stores the
  config the phone pushes, then deletes the DataItem. Either side's sign-out clears it.
- Phone: **Share with friends** (`sharing/`) and **Following** (`following/`, with
  `FollowPushService` as the UnifiedPush receiver).
- Web: `web-receiver/`, with no dependencies and no build step. `.github/workflows/pages.yml`
  tests it and publishes it to GitHub Pages, whose address is `Invite.WEB_RECEIVER`.
  - Each followed system gets its own service worker registration, scoped `follow/<id>/`. A
    registration holds one push subscription bound to one VAPID key, so following several systems
    needs several registrations.
  - `formats.js` holds the invite and follow-code encoding. Its tests share fixture strings with
    `InvitesInteropTest.kt`, so the two sides can't drift apart.

Differences from the plan above:

- **Invites are links, shown as a QR code.** An invite is the web receiver's address with the invite
  in the fragment, so a friend can scan it with any phone camera. PluralWare's Following screen
  accepts the same link pasted in.
- **Follow codes come back as text**, through any chat, and are pasted into the sharing screen.
  Scanning them would need a camera and a barcode scanner in the app, which isn't worth it yet.
- Both formats are `pluralware-invite:` / `pluralware-follow:` plus base64url JSON, and the parsers
  find them anywhere in a message or a link.
- **Gone friends:**
  - The phone learns about them by reading a status DataItem the watch writes
    (`/pluralware/sharing-status`) when the sharing screen opens.
  - A gone mark isn't permanent. The friend is retried once a day (`GoneFriends`), and a delivery
    clears the mark, so one misattributed 404 doesn't mute someone for good.
- **A changed endpoint is loud.** When a follower's distributor moves them to a new endpoint, their
  follow code changes and the system's copy stops working. The phone notifies them and marks the
  follow, until they share the new code.
- **One bad friend can't break the rest.** Follow codes must name a real https host.
  `SwitchSharer` turns any per-friend error into that friend's `Failed` outcome, and the watch's
  sending scope has a last-resort exception handler.
- **The relay switch** ("Sent by: this watch / relay") waits for step 4.
- **The system's VAPID key** is generated on the first invite, not when sharing is first turned
  on. Simple-mode-only setups never need one.

**The relay** (`relay/`, step 4) is plain JavaScript on web standards (fetch, WebCrypto) with no
dependencies. `worker.js` runs it on Cloudflare Workers with KV; `server.mjs` runs it on Node with a
JSON state file. What PluralKit's source added to the plan:

- `pk;s webhook <url>` works only in DMs. It shows the signing token and waits for "yes" before
  testing the URL, with one ping carrying the right token (expecting 200) and one carrying a wrong
  one (expecting 401). So the phone uploads the token to the relay in between, and the setup
  screen walks through that order.
- The uploaded config carries an `enabled` flag. A relay that has the token but isn't enabled
  still answers PluralKit's checks, but sends nothing. That lets the webhook be set up before
  sending moves off the watch, with no window where both send.
- The relay's Web Push is its own WebCrypto implementation (no Tink in JavaScript). Its tests
  reproduce RFC 8291's worked example byte for byte.

**Verified end to end** with the web receiver in Google Chrome:

1. It subscribed through Google's push service (FCM) using a VAPID key from an invite.
2. `SwitchSharer` in `:shared` encrypted and signed a switch and sent it to that endpoint.
3. Chrome decrypted it, and the service worker recorded and showed "Alex and someone else are
   fronting".

The same through the relay, running under Node:

1. A PluralKit-shaped `CREATE_SWITCH` with the right signing token was posted to it.
2. It pushed through FCM to the subscribed Chrome, which showed "Bea and Alex are fronting", in
   front order.

One finding: after the browser unsubscribed, FCM kept accepting pushes to the old endpoint for
at least a minute. So how quickly a friend shows as gone depends on the push service's own timing.

Still needing real devices:

- the Android receiver through the ntfy app as UnifiedPush distributor;
- Safari/iOS;
- `EncryptedSharedPreferences` on Tink 1.23 (section 4.2).

