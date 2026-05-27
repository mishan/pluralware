# PluralWare

PluralWare is a small constellation of PluralKit clients sharing one core.
The first surface is a **Wear OS app** for setting your fronter from your
wrist; a companion phone app handles token entry and handoff.

## Status

Skeleton with polished UI built against mock data. The watch app builds and
runs end-to-end against `MockPluralKitClient`. The production HTTP client is
written and unit-tested; wiring it into the activity is gated on the
phone→watch token-handoff path landing.

## Modules

- **`:shared`** — Android library. Domain models, `PluralKitClient` interface,
  the production `RetrofitPluralKitClient` (over OkHttp + kotlinx-serialization),
  `MockPluralKitClient`, the repository, token storage abstraction, and
  `PreviewData` for previews/tests.
- **`:wear`** — the Wear OS app. Three screens (Fronters, Picker, History),
  proper ViewModels, loading/error/empty states, custom theme with PluralKit
  amber, member colour identity stripes, relative timestamps. Wired to the
  mock client today; swap to the production client once the token store is
  populated.
- **`:mobile`** — companion phone app. Stub; token entry + Wearable Data Layer
  handoff comes in the next phase.

## Stack

- Kotlin 2.0 + Compose / Compose for Wear (Wear OS 3+, API 30+).
- **Hand-rolled PluralKit v2 client** — Retrofit + OkHttp + kotlinx-serialization.
  We previously planned to use [Plural.kt](https://github.com/The-ProxyFox-Group/Plural.kt),
  but that project was archived in March 2024 and its Maven server
  (`maven.proxyfox.dev`) has been taken down. The five endpoints we need
  (system, members, fronters, switch history, log switch) are a small enough
  surface that owning the client beats inheriting an unmaintained dependency.
- AndroidX Security for the API token; Wearable Data Layer for handoff.

## What works in this build

- Home screen: shows current fronter(s), member colour stripes, relative time
  since the switch, switch-out state, empty state for no-history systems.
- Picker: multi-select with checkmarks, disabled-until-selected confirm,
  switch-out shortcut, ~700ms confirmation flash before nav-back.
- History: recent switches as rows with member badges (truncated past 3),
  tap any past switch to "switch back" to that configuration.
- Theme: warm amber primary, deep neutral surface, AMOLED-friendly black
  background, scale tuned for arm's-length glanceability.
- All screens have loading and error states with retry.
- `@Preview`s for round-large and round-small Wear devices.
- Unit tests covering the repository and the `RetrofitPluralKitClient`
  DTO↔domain mapping (incl. 204 handling and orphan-member resolution).

## Build order

1. ~~Project skeleton + mock data~~
2. ~~UI polish on mocks~~
3. ~~Hand-rolled `RetrofitPluralKitClient` + tests~~ ← we are here
4. Companion app: token entry → Wearable Data Layer → encrypted storage on
   watch. Add sign-out / delete-token screen. Swap `MainActivity`'s mock for
   `PluralKitClientFactory.create(token, enableLogging = BuildConfig.DEBUG)`.
5. Real-user testing with plural folks.
6. Complication for current fronter.
7. Tile for quick-switch.
8. Pre-launch: privacy policy, Data Safety form, store listing, Play Store
   internal testing track.

## API client

`me.pluralware.shared.api.PluralKitClient` is the only thing the app code
should depend on. Implementations:

- **`RetrofitPluralKitClient`** — production. Construct via
  `PluralKitClientFactory.create(token)`. Talks to `https://api.pluralkit.me/v2/`.
- **`MockPluralKitClient`** — in-memory fake with injectable latency. Used
  by previews and ViewModel tests.

Notable client behaviours:

- `getCurrentFronters` returns `null` when PluralKit replies 204 (system with
  no registered switches) — that's a documented success state, not an error.
- `getRecentSwitches` resolves the member-ID list PluralKit returns against
  the member list in one call; an orphaned ID (deleted member) is dropped
  rather than rendered broken.
- The `Authorization` header carries the raw token, with no `Bearer` prefix —
  that's PluralKit's documented format.
- `User-Agent` is `PluralWare/<version> (+<contact URL>)` — PluralKit asks
  consumers to be contactable. **TODO: update the placeholder URL** in
  `PluralKitClientFactory.kt` before publishing.

## Design notes

- **Member colour as identity stripe**: a 4dp-wide vertical stripe on the
  leading edge of each member chip. Keeps the label on a neutral surface
  so it stays legible across any colour PluralKit returns.
- **Relative time, not absolute**: "3h ago" beats "11:42 AM" on a watch.
  Past a week we show "Nw ago" and stop — for older history a user will
  reach for the dashboard.
- **No font import yet**: legibility on tiny screens is hard-won. Using the
  system default until we have a concrete reason to change.
- **Skipping "current" in the history list**: the most recent switch is the
  home screen's job. Showing it again on the history screen wastes space.

## Notes

- `applicationId = "me.pluralware"` is set on `:wear` — that's the Play
  Store identity. Don't change post-publish.
- Run `./gradlew :shared:test` after touching the client to keep the
  DTO mapping honest.
