# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

PluralWare is a constellation of [PluralKit](https://pluralkit.me) clients sharing one core.
The primary surface is a **Wear OS app** for setting your fronter from your wrist; a **phone
companion app** handles PluralKit token entry and hands the token to the watch.

## Commands

Gradle wrapper is committed; use `./gradlew`.

- Build everything: `./gradlew build`
- Assemble a debug APK: `./gradlew :wear:assembleDebug` / `./gradlew :mobile:assembleDebug`
- Run all unit tests: `./gradlew test`
- Run one module's unit tests: `./gradlew :shared:testDebugUnitTest`
- Run a single test class: `./gradlew :shared:testDebugUnitTest --tests "me.pluralware.shared.PluralKitRepositoryTest"`
- Run a single test method (backtick-named): `./gradlew :shared:testDebugUnitTest --tests "*registerSwitch with empty list*"`
- Install on a connected device/emulator: `./gradlew :wear:installDebug`

Unit tests live in `:shared` (client, repository, handoff) and `:wear` (ViewModels, with
`MainDispatcherRule` standing in for the main dispatcher). They're plain JVM tests (JUnit4 + MockK +
Turbine + `kotlinx-coroutines-test`) and need no device. Run `./gradlew test` after touching either.

`local.properties` (git-ignored) must point `sdk.dir` at an Android SDK. Source lives under
`src/main/kotlin/`, not `src/main/java/`.

## Modules

Three Gradle modules; dependency versions are centralized in `gradle/libs.versions.toml`.

- **`:shared`** (`com.android.library`, namespace `me.pluralware.shared`, `minSdk 26`) — all
  domain logic, the PluralKit client, the repository, token storage, and phone↔watch handoff.
  Consumed by both apps. `minSdk` is the lowest of the consumers (mobile); wear pins its own 30.
- **`:wear`** (`com.android.application`, `applicationId "me.pluralware"`, `minSdk 30` for Wear OS 3) —
  the watch app: Compose-for-Wear UI, ViewModels, the token listener service.
- **`:mobile`** (`com.android.application`, `applicationId "me.pluralware"`, `minSdk 26`) — phone
  companion: token entry screen + push to watch.

**Both apps share `applicationId = "me.pluralware"` and this is load-bearing, not an accident.**
The Wearable Data Layer scopes `DataItem`s by package name — if the two diverge, the watch never
sees the token the phone writes. Both apply `applicationIdSuffix = ".debug"` on debug builds.
`me.pluralware` is the Play Store identity; don't change it post-publish.

## Architecture

### The client seam

`me.pluralware.shared.api.PluralKitClient` is the single interface the rest of the app depends on.
Two implementations:

- `RetrofitPluralKitClient` — production. Hand-rolled wrapper over PluralKit API v2 (Retrofit +
  OkHttp + kotlinx-serialization). **Never construct directly in app code** — go through
  `PluralKitClientFactory.create(token, appVersion, enableLogging)`, which wires the auth interceptor, JSON
  converter, timeouts, and base URL consistently. Tests instantiate it directly with a fake
  `PluralKitApi`.
- `MockPluralKitClient` — in-memory fake with injectable latency, drives `@Preview`s and tests.

The client is hand-rolled (rather than using Plural.kt) because Plural.kt was archived in 2024 and
its Maven server was taken down; the five MVP endpoints are a small surface to own.

Client behaviors that look like bugs but aren't:
- `getCurrentFronters()` returns `null` when PluralKit replies **204** (a system with no registered
  switches). That's a documented success state, not an error.
- The switch-history endpoint returns members as ID references, not full objects.
  `RetrofitPluralKitClient` keeps its own member-lookup cache to expand them, and **silently drops
  unresolvable IDs** (e.g. a deleted member) rather than render a broken row.
- The `Authorization` header carries the **raw token with no `Bearer` prefix** — PluralKit's
  documented format.
- Every non-success response surfaces as `PluralKitHttpException` (`isUnauthorized`, `isRateLimited`,
  `retryAfter`), never Retrofit's `HttpException`. `PkResult.Failure.isUnauthorized` and the wear
  `toUiError` helper build on it: a 401 becomes an error screen with **Sign out** instead of Retry.

### Repository layer

`PluralKitRepository` wraps a `PluralKitClient` and is what ViewModels talk to. It:
- Converts thrown exceptions into a `PkResult<T>` sealed type (`Success`/`Failure`) — chosen over
  `kotlin.Result` for Compose `when`-exhaustiveness and room to add states like `Unauthorized`.
- Caches the member list (members change rarely).
- Exposes `currentFronters` as a `StateFlow` — the one place every switch the app sees lands. After
  a successful `registerSwitch`, the new switch is pushed into that flow; the home ViewModel watches
  for **uuid** changes (not value changes) to avoid re-loading on its own writes, and `MainActivity`
  forwards every change to the complication and tile (see below).

It is deliberately **not** a singleton — apps wire it manually so tests can inject the mock.

### Token storage and handoff

`TokenStore` interface with two impls: `EncryptedTokenStore` (production,
`EncryptedSharedPreferences`, AES-256) and `InMemoryTokenStore` (tests/previews).

`EncryptedTokenStore.get(context)` is a **process-wide singleton** and that matters: on the watch,
the `WatchDataListenerService` and `MainActivity` share one in-memory `tokenFlow`, so a token written by
the service live-updates the activity's UI with no manual reload.

End-to-end pairing flow:
1. Phone `TokenEntryViewModel.connect()` validates the token via `GET /systems/@me`, saves it to the
   phone's `EncryptedTokenStore`, then calls `TokenHandoff.push(...)`.
2. `TokenHandoff` (in `:shared`) writes a `DataItem` to path `/pluralware/token` on the Wearable Data
   Layer. It stamps a timestamp into the payload because the Data Layer dedupes on payload hash —
   without it, re-pushing the same token would be a silent no-op. `DataClient` (not `MessageClient`)
   is used so the write survives the watch being asleep/out of range.
3. Watch `WatchDataListenerService` (a `WearableListenerService`, registered in the manifest with a
   path-filtered intent filter) receives `onDataChanged`, persists the token to its
   `EncryptedTokenStore` with `commit()`, and **deletes the `DataItem`** — the deletion propagates
   back to the phone, bounding how long cleartext sits on disk.
4. Watch `MainActivity` observes `tokenStore.tokenFlow`: `null` → "Pair with phone" screen;
   non-null → `ConnectedApp` inside **`key(token.raw)`**, with a `Session` from the
   activity-scoped `SessionHolder`: one `PluralKitRepository` per token, plus the `ViewModelStore`
   the screens' ViewModels live in (provided as `LocalViewModelStoreOwner`). The repository and
   the screens survive configuration changes together. A token swap or sign-out clears the old
   store, which releases the old screens' ViewModels along with their repository and token.

Signing out: the phone's **Disconnect** calls `TokenHandoff.pushSignOut`, which overwrites the same
`/pluralware/token` item with a sign-out marker — superseding any token the watch hasn't collected
yet — and the watch clears its store on receipt. The watch can also sign itself out from the error
screen a rejected token (401) produces.

Neither app backs up or transfers its data (`allowBackup="false"` plus `data_extraction_rules.xml`):
the token store is encrypted under a Keystore key that stays on the device, so a transferred copy
is unreadable. If one turns up anyway, `EncryptedTokenStore` discards it rather than crash.

Push success is surfaced independently of save success: emulators without a paired watch throw
`API_NOT_CONNECTED`, so the phone UI lets the user retry the push alone via "Resend to watch".

### Wear UI

`PluralWareApp` hosts a `SwipeDismissableNavHost` with three routes (`fronters`, `pick`, `history`).
The `PluralKitRepository` is threaded down through composables manually (no DI framework). Each
screen has a ViewModel constructed via an inner `Factory` that takes the repository; all screens
have loading/error/empty states with retry. Theme is custom (PluralKit amber, AMOLED-black
background); member colors render as a leading identity stripe on each chip; timestamps are relative.

`FrontersViewModel` walks switch history to compute each fronter's continuous-front "streak"; if
history hits the fetch limit, the streak is marked truncated (`>` uncertainty marker).

### Complications and tile

Three services in `:wear`: the LONG_TEXT `FronterComplicationService`, the static
`LauncherComplicationService` (a shortcut, no data), and `FronterTileService`. Design notes in
`docs/complication-design.md`.

- **One answer for both.** The fronter complication and the tile both ask `FronterSource` what to
  show — setup prompt, fresh cache, fetch, `Last known` fallback, or `Sign in again` on a 401 —
  so they can't disagree. It's pure Kotlin and unit-tested; `FronterSurfaces` wires it to the real
  token store, a per-process client, and `LastFronterStore`.
- **Freshness is pushed.** `MainActivity` sends every `loadedFronters` change to
  `FronterSurfaces.publish`, along with the session's token. That caches the line (keyed by a hash
  of the token, so a stale fetch can't cross systems) and requests updates; token changes (pairing,
  either side's sign-out) go through `FronterSurfaces.onTokenChanged`, which clears the cache.
  Anything that changes fronters or the token must keep reaching one of these two.
- **Polling is only the backstop** for switches made off the watch: the complication's 30-minute
  `UPDATE_PERIOD_SECONDS` and the tile's 10-minute freshness interval.

### Friend notifications

Sharing switches with friends is designed in `docs/notifications-design.md`; section 14 records
what's built. In short:

- The phone owns a `SharingConfig` (friends, shared members, VAPID key, ntfy server). It keeps it in
  an encrypted store and pushes it to the watch on `/pluralware/sharing`.
- The watch sends after each switch **it registers**, via `PluralKitRepository`'s
  `onSwitchRegistered`. It sends either encrypted Web Push (`Friend.Private`) or ntfy messages
  (`Friend.Simple`).
- The phone's **Following** screen is the receiving side, through UnifiedPush (`FollowPushService`).
  `web-receiver/` is the same for browsers: static, no dependencies, published to GitHub Pages by
  `.github/workflows/pages.yml`. Test it with `node --test 'web-receiver/test/*.test.mjs'`.
- `relay/` is the optional server that turns PluralKit's dispatch webhook into notifications
  (Cloudflare Worker or Node, no dependencies; `npm test` there). The phone uploads a `RelayConfig`
  to it. When `RelaySettings.enabled` is on, the watch stops sending (`SharingConfig.watchSends`).
  The phone turns that on only after the relay confirms the upload. The watch's copy comes from
  `forWatch()`, without relay secrets. Relays that couldn't be reached when let go of are kept in
  `SharingStore.orphanedRelays` until a delete succeeds.
  The announcement wording exists in both Kotlin and `relay/src/announce.js`, and so does the
  upload's JSON shape; each pair has tests that must change together.
- The invite and follow-code formats exist twice, in `Invites.kt` and `web-receiver/formats.js`.
  Their tests share fixture strings, so change both sides and both tests together.
- Tink's `apps-webpush` and the UnifiedPush connector both depend on the JVM `tink`, which clashes
  with `tink-android`. Every use excludes it (see `libs.versions.toml`).

## Conventions

- Wire `PluralKitClientFactory.create(..., appVersion = BuildConfig.VERSION_NAME,
  enableLogging = BuildConfig.DEBUG)` — never enable HTTP body logging in release.
- Domain models in `model/Models.kt` are intentionally narrower than the full PluralKit API; widen
  deliberately, since every field is a UI stability promise.
- Both apps take their version from `pluralware.version` in `gradle.properties`; releases are
  cut by pushing a matching `vX.Y.Z` tag (see `docs/releasing.md`).
- Commit messages: short imperative subject, capitalized. **Do not add a `Co-Authored-By` trailer.**
