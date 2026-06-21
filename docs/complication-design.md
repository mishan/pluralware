# Fronter Complication — Design

Status: proposal. Scope: a single **LONG_TEXT** complication data source for `:wear` that
shows the current fronter(s), taps through to the app, and refreshes when a switch is
registered. No code written yet.

## 1. What it is

A `ComplicationDataSourceService` that any Wear watch face can host in a LONG_TEXT slot. It
renders one of three states:

| State | Source signal | Rendered text (example) |
|---|---|---|
| Someone fronting | `Switch` with non-empty `members` | `Fronting: Alice & Bob` |
| Switched out | `Switch.isSwitchOut` (empty members) | `Switched out` |
| No data / not paired | `refreshFronters()` returns `null` (204) or fails, or no token | `Tap to set up` / last-known |

LONG_TEXT is the only declared type. SHORT_TEXT is deliberately *not* a second info surface —
~7 characters can't meaningfully show a fronter. If we add a SHORT_TEXT complication later it
should be a **launcher shortcut** (static icon / short label like `PW`, tap opens the app), not
a data view. Captured as a future option in section 11, out of scope for v1.

## 2. Why this is "that phase"

The catalog already declares the dependency; it's just not pulled into the module. In
`wear/build.gradle.kts` there's literally a placeholder:

```kotlin
// (Tiles and complications added when we get to that phase.)
```

And `gradle/libs.versions.toml` already has:

```toml
androidx-wear-watchface-complications-data-source =
  { group = "androidx.wear.watchface", name = "watchface-complications-data-source-ktx", version.ref = "wearWatchface" }  # 1.2.1
```

So step one is a one-line dependency add — no version-catalog change.

## 3. The data plumbing already exists

Nothing new is needed in `:shared`. The complication reuses the same singletons the activity
and listener service use:

- **Token** — `EncryptedTokenStore.get(appContext)` is a process-wide singleton, so the
  service reads the exact token the rest of the app holds. No new storage, no handoff change.
- **Fronters** — `PluralKitRepository.refreshFronters(): PkResult<Switch?>` already encodes
  all three render states: `Success(null)` is the documented 204 "no switches" case,
  `Success(switch)` with `isSwitchOut` is switch-out, `Failure` is the error path.
- **Labels & color** — `Member.displayLabel` for text; `Member.indicatorColor()` exists if we
  later add a monochromatic image variant.

## 4. Service design

A subclass of `SuspendingComplicationDataSourceService` (the `-ktx` artifact), which lets
`onComplicationRequest` be a `suspend fun` so we can do the network call directly.

```kotlin
// wear/.../complication/FronterComplicationService.kt
class FronterComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (request.complicationType != ComplicationType.LONG_TEXT) return null

        val token = EncryptedTokenStore.get(applicationContext).getToken()
            ?: return notPairedData()              // "Tap to set up", tap -> MainActivity

        val repo = PluralKitRepository(
            PluralKitClientFactory.create(token, enableLogging = BuildConfig.DEBUG)
        )
        return when (val r = repo.refreshFronters()) {
            is PkResult.Success -> fronterData(r.value)   // null / switch-out / fronting
            is PkResult.Failure -> errorOrLastKnownData()
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        if (type == ComplicationType.LONG_TEXT) previewData() else null
}
```

Builder shape for the populated case:

```kotlin
LongTextComplicationData.Builder(
    text = PlainComplicationText.Builder("Fronting: $names").build(),
    contentDescription = PlainComplicationText.Builder("Current fronter: $names").build(),
)
    .setTitle(PlainComplicationText.Builder("Fronter").build())
    .setTapAction(openAppPendingIntent())
    .build()
```

Text formatting rules:

- One fronter → `Fronting: Alice`.
- Multiple → join `displayLabel` with `, `, cap at ~2–3 names then `+N` to stay legible in a
  long-text slot (`Fronting: Alice, Bob +2`). The order is meaningful — index 0 is PluralKit's
  proxy fronter (same ordering the picker preserves), so don't sort.
- Switch-out → `Switched out`.
- Always set `contentDescription` for accessibility/screen-reader faces.

**BENCHMARK note:** unlike `MainActivity`, the service should always take the production path
(real token + factory client). The `BuildConfig.BENCHMARK` mock seam exists so the
baseline-profile producer can drive the *screens*; it has no bearing on the complication, so
we don't branch on it here.

## 5. Freshness — push, don't poll

Complication update periods are clamped by the platform (a non-zero `UPDATE_PERIOD_SECONDS` is
floored at 300s, and only while the face is active), so polling is both coarse and
battery-hostile. PluralKit switches are user-initiated and infrequent, which fits a **push**
model much better than the home screen's `RefreshInterval` polling.

Recommended:

- Set `UPDATE_PERIOD_SECONDS = 0` (no periodic refresh by the system).
- After a successful `registerSwitch`, ask the framework to re-request our data:

```kotlin
ComplicationDataSourceUpdateRequester
    .create(appContext, ComponentName(appContext, FronterComplicationService::class.java))
    .requestUpdateAll()
```

**Where to fire it.** `registerSwitch` lives in `PluralKitRepository` (`:shared`), which has no
Android `Context`, so the trigger belongs in the `:wear` layer. The switch is registered from
`PickerViewModel.submit(...)` on the `PkResult.Success` branch — but the VM also has no
`Context`. Two clean options:

1. **Callback hook (recommended).** Add an `onSwitchRegistered: () -> Unit = {}` to
   `PickerViewModel` (and its `Factory`), invoked on success. Wire the real implementation at
   the app layer (where a `Context` is available) to call `requestUpdateAll()`. Keeps `:shared`
   Android-free and is trivial to unit-test (assert the lambda fires).
2. **Observe the StateFlow.** A process-lifecycle observer on
   `repository.currentFronters` (keyed on `Switch.uuid`, mirroring how `FrontersViewModel`
   already watches uuid changes) that requests an update on change. More decoupled, but needs a
   live observer outside the activity, which is extra moving parts for little gain here.

Go with option 1. Optionally also request an update from `WatchDataListenerService` after a
token arrives, so a freshly-paired watch fills the complication without the user opening the app.

## 6. Offline / error behavior

`onComplicationRequest` can fire when offline or mid-token-rotation. Returning
`NoDataComplicationData()` (or null) on failure leaves the slot blank, which reads as broken.

**Decision: ship last-known render in v1.** On a successful request, persist the formatted
fronter string (and its `Switch.timestamp`); on `Failure`, render that cached value instead of a
blank slot. A small `SharedPreferences`-backed `LastFronterStore` — same pattern as
`LocalSettingsStore`, non-sensitive since it's just a display name — holds it. It does *not*
belong in the encrypted token store. Only when there's no token *and* no cache do we fall back
to the "Tap to set up" state.

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
        android:value="0" />
</service>
```

Notes: the `BIND_COMPLICATION_PROVIDER` permission + the `ACTION_COMPLICATION_UPDATE_REQUEST`
filter are what register us in the system's complication picker. A small monochrome
`ic_complication` drawable is needed for that picker entry (first real `res/drawable` asset in
`:wear`). `INTERNET` is already granted in the manifest, which the service needs.

## 9. Testing plan

The data-source logic is mostly pure mapping, so most of it is plain JVM tests in `:shared`-style:

- **Formatter unit tests** — `Switch -> LONG_TEXT text/contentDescription` for: single fronter,
  multiple (incl. the `+N` cap and order preservation), switch-out, null/204. Pull the formatter
  out as a pure function so it's testable without Android.
- **State mapping** — feed `MockPluralKitClient` through a real `PluralKitRepository` and assert
  each `PkResult` maps to the intended complication state (reuse the existing MockK/Turbine setup).
- **Update trigger** — assert `PickerViewModel` invokes `onSwitchRegistered` exactly once on
  success and never on the no-op / failure branches.
- **Manual / instrumented** — add the complication to a watch-face slot on an emulator, register a
  switch in the app, confirm it updates; verify tap opens the app and the not-paired state shows
  before a token exists.

## 10. File-by-file change list

| File | Change |
|---|---|
| `wear/build.gradle.kts` | Add `implementation(libs.androidx.wear.watchface.complications.data.source)` (replaces the placeholder comment). |
| `wear/.../complication/FronterComplicationService.kt` | New. The data source. |
| `wear/.../complication/FronterComplicationFormatter.kt` | New. Pure `Switch? -> text` formatting, unit-tested. |
| `wear/src/main/AndroidManifest.xml` | Register the service (section 8). |
| `wear/src/main/res/drawable/ic_complication.xml` | New. Picker icon. |
| `wear/.../ui/viewmodel/PickerViewModel.kt` | Add `onSwitchRegistered` hook + fire on success. |
| `wear/.../ui/PluralWareApp.kt` / `MainActivity.kt` | Wire the hook to `ComplicationDataSourceUpdateRequester`. |
| `shared/.../settings/LastFronterStore.kt` | Non-sensitive cache for last-known offline render (section 6). |
| `wear/src/test/...` | Formatter + trigger tests. |

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

- **Shared formatting/cache.** Reuses `FronterComplicationFormatter.body(...)` and
  `LastFronterStore` so the tile and complication never disagree, including the offline
  fallback (cached line → "Unavailable", or "Tap to set up" with no token).
- **Coroutine bridge.** `onTileRequest` / `onTileResourcesRequest` return `ListenableFuture`s;
  we use `SuspendToFutureAdapter.launchFuture { … }` (new `androidx.concurrent:concurrent-futures-ktx`
  dependency) to answer from a suspend function, matching the rest of the codebase's coroutine style.
- **Layout.** ProtoLayout Material `PrimaryLayout` — amber caption title, the fronter line as
  body (max 3 lines), and the chip. Colors are literal ARGB ints mirroring `PluralWareTokens`
  (ProtoLayout uses int colors, not Compose `Color`).
- **Freshness.** A coarse 10-minute freshness interval keeps the carousel copy from going stale,
  and `requestFronterTileUpdate()` (via `TileService.getUpdater`) pushes an immediate refresh on
  switch — wired into the same `onSwitchRegistered` hook as the complication.
- **Manifest.** `BIND_TILE_PROVIDER` permission + the `androidx.wear.tiles.action.BIND_TILE_PROVIDER`
  filter register it in the tile picker. The `PREVIEW` meta-data currently points at
  `ic_complication` as a placeholder — replace with a real tile preview image before publishing.

Follow-ups worth noting: the "Change" chip uses the default Material chip color (not branded
amber) to keep the first cut low-risk, and the tile preview is a placeholder.
