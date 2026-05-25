# PluralWatch

A Wear OS app for setting your PluralKit fronter from your wrist.

## Status

Skeleton with polished UI built against mock data. The watch app builds and
runs end-to-end; real PluralKit calls are stubbed in `PluralKotClient`
pending verification of Plural.kt's exact API surface.

## Modules

- **`:shared`** — Android library. Domain models, `PluralKitClient` interface,
  Plural.kt-backed implementation (stubbed), `MockPluralKitClient`, the
  repository, token storage abstraction, and `PreviewData` for previews/tests.
- **`:wear`** — the Wear OS app. Three screens (Fronters, Picker, History),
  proper ViewModels, loading/error/empty states, custom theme with PluralKit
  amber, member colour identity stripes, relative timestamps. Wired to the
  mock client.
- **`:mobile`** — companion phone app. Stub; token entry + Wearable Data Layer
  handoff comes in the next phase.

## Stack

- Kotlin 2.0 + Compose / Compose for Wear (Wear OS 3+, API 30+)
- Plural.kt (`com.kotlindiscord.pluralkot:PluralKot:1.0.0`) — MIT, archived
  upstream. Wrapped behind our own `PluralKitClient`.
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
- Unit tests covering the repository.

## Build order

1. ~~Project skeleton + mock data~~
2. ~~UI polish on mocks~~ ← we are here
3. Verify Plural.kt's actual method names; wire `PluralKotClient` properly.
4. Companion app: token entry → Wearable Data Layer → encrypted storage on
   watch. Add sign-out / delete-token screen.
5. Real-user testing with plural folks.
6. Complication for current fronter.
7. Tile for quick-switch.
8. Pre-launch: privacy policy, Data Safety form, store listing, Play Store
   internal testing track.

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

- The Plural.kt Maven repo (`maven.kotlindiscord.com`) is third-party. If it
  goes down, build breaks. Mitigation: the `KtorPluralKitClient` fallback
  path is intentionally easy to add.
- `applicationId = "me.pluralwatch"` is set on `:wear` — that's the Play
  Store identity. Don't change post-publish.
