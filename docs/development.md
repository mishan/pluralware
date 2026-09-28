# Developing PluralWare

For using PluralWare, see the [README](../README.md). `CLAUDE.md` at the repo
root has more on the architecture and conventions.

## Modules

- **`:shared`** — Android library. Domain models, `PluralKitClient` interface,
  the production `RetrofitPluralKitClient` (over OkHttp + kotlinx-serialization),
  `MockPluralKitClient`, the repository, token storage abstraction, and
  `PreviewData` for previews/tests.
- **`:wear`** — the Wear OS app. Three screens (Fronters, Picker, History),
  proper ViewModels, loading/error/empty states, custom theme with PluralKit
  amber, member color identity stripes, relative timestamps. Also the fronter
  complication, launcher complication and tile, and sending switches to friends.
- **`:mobile`** — companion phone app: token entry and Wearable Data Layer
  handoff, **Share with friends** and **Following**.
- **`web-receiver/`** — the browser version of Following, published to GitHub
  Pages.
- **`relay/`** — the optional server that turns PluralKit's dispatch webhook
  into friend notifications.

## Stack

- Kotlin 2.0 + Compose / Compose for Wear (Wear OS 3+, API 30+).
- **Hand-rolled PluralKit v2 client** — Retrofit + OkHttp + kotlinx-serialization.
  We previously planned to use [Plural.kt](https://github.com/The-ProxyFox-Group/Plural.kt),
  but that project was archived in March 2024 and its Maven server
  (`maven.proxyfox.dev`) has been taken down. The five endpoints we need
  (system, members, fronters, switch history, log switch) are a small enough
  surface that owning the client beats inheriting an unmaintained dependency.
- AndroidX Security for the API token; Wearable Data Layer for handoff.

## Build order

1. ~~Project skeleton + mock data~~
2. ~~UI polish on mocks~~
3. ~~Hand-rolled `RetrofitPluralKitClient` + tests~~
4. ~~Companion app: token entry → Wearable Data Layer → encrypted storage on
   watch, with sign-out from either side. Watch on the production client.~~
5. Real-user testing with plural folks. ← we are here
6. ~~Complication for current fronter~~ — plus a launcher complication.
7. ~~Tile showing the current fronter~~. Quick-switch from the tile itself is still open.
8. Pre-launch: privacy policy, Data Safety form, store listing, Play Store
   internal testing track.
9. Friend notifications (`docs/notifications-design.md`): end-to-end
   encrypted switch notifications to friends, or plain ntfy messages. Sending
   from the watch, receiving in the phone app, and the web receiver
   (`web-receiver/`, for iPhone and desktop) are built, and so is an optional
   relay (`relay/`) that also catches switches made in Discord or elsewhere.

## API client

`me.pluralware.shared.api.PluralKitClient` is the only thing the app code
should depend on. Implementations:

- **`RetrofitPluralKitClient`** — production. Construct via
  `PluralKitClientFactory.create(token, appVersion)`. Talks to `https://api.pluralkit.me/v2/`.
- **`MockPluralKitClient`** — in-memory fake with injectable latency. Used
  by previews and ViewModel tests.

Notable client behaviors:

- `getCurrentFronters` returns `null` when PluralKit replies 204 (system with
  no registered switches) — that's a documented success state, not an error.
- `getRecentSwitches` resolves the member-ID list PluralKit returns against
  the member list in one call; an orphaned ID (deleted member) is dropped
  rather than rendered broken.
- The `Authorization` header carries the raw token, with no `Bearer` prefix —
  that's PluralKit's documented format.
- `User-Agent` is `PluralWare/<version> (+https://github.com/mishan/pluralware)`
  — PluralKit asks consumers to be contactable.
- Every non-success response is a `PluralKitHttpException`, whichever endpoint
  it came from. A 401 means the token was revoked or regenerated: the watch
  stops showing stale fronters and offers a sign-out. A 429 pauses the home
  screen's polling for the server's `Retry-After` (a minute if it doesn't say).

## Design notes

- **Member color as identity stripe**: a 4dp-wide vertical stripe on the
  leading edge of each member chip. Keeps the label on a neutral surface
  so it stays legible across any color PluralKit returns.
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
