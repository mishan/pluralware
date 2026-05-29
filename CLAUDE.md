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

Unit tests currently live only in `:shared` (`shared/src/test/...`). They're plain JVM tests
(JUnit4 + MockK + Turbine + `kotlinx-coroutines-test`) and need no device. After touching the
API client or repository, run `./gradlew :shared:test` — that's the layer the tests guard.

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
  `PluralKitClientFactory.create(token, enableLogging)`, which wires the auth interceptor, JSON
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
- `USER_AGENT` in `PluralKitClientFactory.kt` has a **TODO placeholder URL** to replace before publishing.

### Repository layer

`PluralKitRepository` wraps a `PluralKitClient` and is what ViewModels talk to. It:
- Converts thrown exceptions into a `PkResult<T>` sealed type (`Success`/`Failure`) — chosen over
  `kotlin.Result` for Compose `when`-exhaustiveness and room to add states like `Unauthorized`.
- Caches the member list (members change rarely).
- Exposes `currentFronters` as a `StateFlow` so multiple surfaces (home screen, future tile,
  complication) observe one source of truth. After a successful `registerSwitch`, the new switch is
  pushed into that flow; the home ViewModel watches for **uuid** changes (not value changes) to
  avoid re-loading on its own writes.

It is deliberately **not** a singleton — apps wire it manually so tests can inject the mock.

### Token storage and handoff

`TokenStore` interface with two impls: `EncryptedTokenStore` (production,
`EncryptedSharedPreferences`, AES-256) and `InMemoryTokenStore` (tests/previews).

`EncryptedTokenStore.get(context)` is a **process-wide singleton** and that matters: on the watch,
the `TokenListenerService` and `MainActivity` share one in-memory `tokenFlow`, so a token written by
the service live-updates the activity's UI with no manual reload.

End-to-end pairing flow:
1. Phone `TokenEntryViewModel.connect()` validates the token via `GET /systems/@me`, saves it to the
   phone's `EncryptedTokenStore`, then calls `TokenHandoff.push(...)`.
2. `TokenHandoff` (in `:shared`) writes a `DataItem` to path `/pluralware/token` on the Wearable Data
   Layer. It stamps a timestamp into the payload because the Data Layer dedupes on payload hash —
   without it, re-pushing the same token would be a silent no-op. `DataClient` (not `MessageClient`)
   is used so the write survives the watch being asleep/out of range.
3. Watch `TokenListenerService` (a `WearableListenerService`, registered in the manifest with a
   path-filtered intent filter) receives `onDataChanged`, persists the token to its
   `EncryptedTokenStore`, and **deletes the `DataItem`** — the deletion propagates back to the phone,
   bounding how long cleartext sits on disk.
4. Watch `MainActivity` observes `tokenStore.tokenFlow`: `null` → "Pair with phone" screen;
   non-null → builds a `PluralKitRepository` (re-`remember`ed on `token.raw` so a token swap doesn't
   serve cached data for the wrong system) and shows `PluralWareApp`.

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

## Conventions

- Wire `PluralKitClientFactory.create(..., enableLogging = BuildConfig.DEBUG)` — never enable HTTP
  body logging in release.
- Domain models in `model/Models.kt` are intentionally narrower than the full PluralKit API; widen
  deliberately, since every field is a UI stability promise.
- Commit messages: short imperative subject, capitalized. **Do not add a `Co-Authored-By` trailer.**
