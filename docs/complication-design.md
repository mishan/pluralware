# Fronter Complication — Design

Status: built. Scope: a **LONG_TEXT** complication data source for `:wear` that shows the
current fronter(s) and taps through to the app, plus the launcher complication and fronter tile
that grew out of it (sections 12–13). This started as the proposal; sections 5, 6 and 9 were
revised to match what shipped, after review found the first cut's freshness model missed most
switches.

## 1. What it is

A `ComplicationDataSourceService` that any Wear watch face can host in a LONG_TEXT slot. It
renders one of three states:

| State | Source signal | Rendered text (example) |
|---|---|---|
| Someone fronting | `Switch` with non-empty `members` | `Fronting: Alex, Bea` (past two: `Fronting: Alex, Bea +2`) |
| Switched out | `Switch.isSwitchOut` (empty members) | `Switched out` |
| No switches ever | `refreshFronters()` returns `null` (PluralKit's 204) | `No switches yet` |
| Not paired | no token | `Tap to set up` |
| Fetch failed, rejected token | see section 6 | `Last known` / `Unavailable` / `Sign in again` |

LONG_TEXT is the only declared type. SHORT_TEXT is deliberately *not* a second info surface —
~7 characters can't meaningfully show a fronter. If we add a SHORT_TEXT complication later it
should be a **launcher shortcut** (static icon / short label like `PW`, tap opens the app), not
a data view. Captured as a future option in section 11, out of scope for v1.

## 2. Dependencies

The complication data source (`watchface-complications-data-source-ktx`) and the tiles and
ProtoLayout libraries were already in the version catalog; `:wear` now uses them. The tile also
needed one addition, `androidx.concurrent:concurrent-futures-ktx`, whose `SuspendToFutureAdapter`
lets a `TileService` answer from a coroutine.

## 3. Data plumbing

The surfaces reuse what the app already has:

- **Token:** `EncryptedTokenStore.get(appContext)`, the same process-wide store the activity and
  listener service use.
- **Fronters:** `PluralKitRepository.refreshFronters(): PkResult<Switch?>` already covers every
  state. `Success(null)` is the 204 "no switches" case, `Success(switch)` with `isSwitchOut` is a
  switch-out, and `Failure` is the error path.
- **Labels:** `Member.displayLabel`.

Two pieces are new. `LastFronterStore` in `:shared` is the cache (section 6). In `:wear`,
`FronterSource` decides what to show and `FronterSurfaces` wires it up (section 4).

## 4. Service design

`FronterComplicationService` is a `SuspendingComplicationDataSourceService`, so
`onComplicationRequest` is a `suspend fun`. It and the tile both ask `FronterSource` for a
`FronterDisplay` (title, text, screen-reader description), and only turn that into their own
data types:

```kotlin
override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
    if (request.complicationType != ComplicationType.LONG_TEXT) return null
    return longText(FronterSurfaces.source(this).current())
}
```

`FronterSurfaces` keeps one client per process, rebuilt when the token changes, so repeated
requests reuse a connection instead of paying a TLS handshake each. It also opens the encrypted
token store off the main thread.

Text formatting (`FronterComplicationFormatter`):

- One fronter → `Fronting: Alex`.
- Several → display labels joined with `, `, capped at two names then `+N`
  (`Fronting: Alex, Bea +2`). Order is meaningful (index 0 is PluralKit's proxy fronter, the
  order the picker preserves), so never sort.
- Switch-out → `Switched out`.
- The content description is never capped, so a screen reader hears every fronter.

**BENCHMARK note:** the surfaces always take the production path. The `BuildConfig.BENCHMARK`
mock seam exists so the baseline-profile producer can drive the *screens*.

## 5. Freshness — push from the app, poll as a backstop

The first cut pushed an update only from the picker and set `UPDATE_PERIOD_SECONDS = 0`. That
missed most switches: PluralKit switches usually come from Discord (`pk;switch`), the dashboard
or another client, and History's switch-back registers through the repository without going
near the picker. A complication could sit on the wrong fronter for days.

What shipped:

- **Push on every change the app sees.** Every switch the watch app learns about — the picker,
  History's switch-back, or a refresh that finds a switch made elsewhere — lands in
  the repository. `MainActivity` observes `PluralKitRepository.loadedFronters`, which unlike
  `currentFronters` tells "no switches yet" apart from "not loaded". It hands each change,
  with the session's token, to `FronterSurfaces.publish(...)`, which caches the formatted line and asks the
  complication (`ComplicationDataSourceUpdateRequester.requestUpdateAll()`) and the tile
  (`TileService.getUpdater`) to re-request. The ViewModels stay `Context`-free and need no hook.
- **Push on token changes.** `WatchDataListenerService` (pairing, re-pairing, a phone-side
  sign-out) and the watch's own sign-out call `FronterSurfaces.onTokenChanged(...)`, which
  clears the cache and requests updates, so a freshly paired watch fills the slot without the
  app being opened and a signed-out one stops showing the old system.
- **Poll as a backstop.** `UPDATE_PERIOD_SECONDS = 1800` for switches made off the watch while
  the app is closed; the tile's 10-minute freshness interval does the same job there. Each is
  one small request, and only while the face or tile is actually showing.

## 6. Offline / error behavior

`onComplicationRequest` can fire when offline or mid-token-rotation. Returning
`NoDataComplicationData()` (or null) on failure leaves the slot blank, which reads as broken.

Both surfaces ask one `FronterSource` (pure Kotlin, unit-tested) what to show:

| Situation | Shows |
|---|---|
| No token | `Tap to set up` |
| Cached line fetched < 5 min ago | the cached line, no network |
| Fetch succeeds | the fresh line, which is cached |
| Fetch fails, cache present | the cached line, titled `Last known` (and read out as such) |
| Fetch fails, no cache | `Unavailable` |
| 401 — token rejected | `Sign in again`; the cache is cleared |

The cache is `LastFronterStore`, an app-private `SharedPreferences` file, excluded from backup
like everything else. It holds display names, including members private in PluralKit. It stores
the line, its full screen-reader text (the line itself collapses past two names to `+N`), when it
was fetched, and a hash of the token it was fetched with.

It belongs to one token:
- Whoever changes the token clears it.
- A line whose token hash doesn't match is ignored.
- A fetch that finishes after a re-pair isn't cached.
- The app publishes each switch together with the token it was fetched with.

So a request that was already in flight can't put one system's fronters on another's watch
face. The five-minute freshness window is what lets the burst of requests after a
`publish` answer from memory instead of each surface fetching again.

## 7. Tap action

**Decision: tap opens the app home.** A `PendingIntent` to `MainActivity` (immutable,
`FLAG_UPDATE_CURRENT`), no deep-link route. (`PluralWareApp` does host a `pick` route in its
`SwipeDismissableNavHost` if we ever want to revisit deep-linking to the picker, but home is the
v1 behavior.)

## 8. Manifest registration

```xml
<service
    android:name=".complication.FronterComplicationService"
    android:exported="true"
    android:icon="@drawable/ic_complication"
    android:label="Fronter"
    android:permission="com.google.android.wearable.permission.BIND_COMPLICATION_PROVIDER">
    <intent-filter>
        <action android:name="android.support.wearable.complications.ACTION_COMPLICATION_UPDATE_REQUEST" />
    </intent-filter>
    <meta-data
        android:name="android.support.wearable.complications.SUPPORTED_TYPES"
        android:value="LONG_TEXT" />
    <meta-data
        android:name="android.support.wearable.complications.UPDATE_PERIOD_SECONDS"
        android:value="1800" />
</service>
```

Notes: the `BIND_COMPLICATION_PROVIDER` permission + the `ACTION_COMPLICATION_UPDATE_REQUEST`
filter are what register us in the system's complication picker. A small monochrome
`ic_complication` drawable is needed for that picker entry (first real `res/drawable` asset in
`:wear`). `INTERNET` is already granted in the manifest, which the service needs.

## 9. Testing plan

- **Formatter** (`FronterComplicationFormatterTest`) — `Switch -> text/contentDescription` for
  single and multiple fronters (the `+N` cap, order preservation), switch-out, and null/204.
- **What to show** (`FronterSourceTest`) — every row of the table in section 6, driven through a
  real `PluralKitRepository` over a mocked client, with an in-memory cache and a fake clock.
- **Where switches come from** (`PickerViewModelTest`, `HistoryViewModelTest`) — a registered
  switch lands in the repository, which is what `publish` follows; no-ops and failures don't.
- **Manual** — add the complication to a watch-face slot and the tile to the carousel, then:
  switch in the app (both update at once); switch from Discord (the tile within ten minutes,
  the complication within thirty, or at once on opening the app); pair and sign out from the
  phone (both follow); go offline (`Last known`).

## 10. File-by-file change list

| File | Change |
|---|---|
| `wear/build.gradle.kts` | Add `implementation(libs.androidx.wear.watchface.complications.data.source)` (replaces the placeholder comment). |
| `wear/.../complication/FronterComplicationService.kt` | New. The data source. |
| `wear/.../complication/FronterComplicationFormatter.kt` | New. Pure `Switch? -> text` formatting, unit-tested. |
| `wear/src/main/AndroidManifest.xml` | Register the service (section 8). |
| `wear/src/main/res/drawable/ic_complication.xml` | New. Picker icon. |
| `wear/.../complication/FronterSource.kt` | New. What both surfaces show (section 6), unit-tested. |
| `wear/.../complication/FronterSurfaces.kt` | New. Android wiring for `FronterSource`; `publish` and `onTokenChanged` (section 5). |
| `wear/.../MainActivity.kt` / `WatchDataListenerService.kt` | Publish `loadedFronters` changes; report token changes. |
| `shared/.../settings/LastFronterStore.kt` | Non-sensitive last-known cache (section 6). |
| `wear/src/test/...` | Formatter, `FronterSource` and ViewModel tests. |

## 11. Decisions & future options

Resolved for v1:

1. **Offline render** — ship the last-known cache (section 6).
2. **Tap target** — open the app home (section 7).
3. **Title** — keep a static `Fronter` title.
4. **SHORT_TEXT** — not in v1.

Built since:

- **SHORT_TEXT launcher complication** — implemented as `LauncherComplicationService` (section 12).
- **Fronter tile** — implemented as `FronterTileService` (section 13).

## 12. Launcher complication (SHORT_TEXT)

`me.pluralware.wear.complication.LauncherComplicationService`. A *shortcut*, not a data view:
~7 chars can't show a fronter, so this is a static glyph/label that opens the app. Supports
SHORT_TEXT (glyph + `PW`) and MONOCHROMATIC_IMAGE (glyph only). No token, no network — renders
instantly in any slot regardless of pairing. Tap opens `MainActivity` (distinct PendingIntent
request code from the fronter complication so the two don't alias). Registered in the manifest
with `UPDATE_PERIOD_SECONDS = 0` (it never changes).

## 13. Fronter tile

`me.pluralware.wear.tile.FronterTileService`, a `TileService`. Shows the fronter line with a
"Change" `CompactChip` that launches the app.

- **Shared source.** Asks the same `FronterSource` as the complication (section 6), so the two
  never disagree, including every fallback. The fronter line carries the full screen-reader text
  as its content description.
- **Coroutine bridge.** `onTileRequest` / `onTileResourcesRequest` return `ListenableFuture`s;
  we use `SuspendToFutureAdapter.launchFuture { … }` (new `androidx.concurrent:concurrent-futures-ktx`
  dependency) to answer from a suspend function, matching the rest of the codebase's coroutine style.
- **Layout.** ProtoLayout Material `PrimaryLayout` — amber caption title, the fronter line as
  body (max 3 lines), and the chip. Colors are literal ARGB ints mirroring `PluralWareTokens`
  (ProtoLayout uses int colors, not Compose `Color`).
- **Freshness.** A coarse 10-minute freshness interval catches switches made off the watch, and
  `FronterSurfaces.publish` pushes an immediate refresh whenever the app sees the fronter change
  (section 5).
- **Manifest.** `BIND_TILE_PROVIDER` permission + the `androidx.wear.tiles.action.BIND_TILE_PROVIDER`
  filter register it in the tile picker. The `PREVIEW` meta-data currently points at
  `ic_complication` as a placeholder — replace with a real tile preview image before publishing.

Follow-ups worth noting: the "Change" chip uses the default Material chip color (not branded
amber) to keep the first cut low-risk, and the tile preview is a placeholder.
