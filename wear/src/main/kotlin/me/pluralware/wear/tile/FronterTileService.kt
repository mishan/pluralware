package me.pluralware.wear.tile

import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ResourceBuilders.Resources
import androidx.wear.protolayout.TimelineBuilders.Timeline
import androidx.wear.protolayout.material.CompactChip
import androidx.wear.protolayout.material.Text
import androidx.wear.protolayout.material.Typography
import androidx.wear.protolayout.material.layouts.PrimaryLayout
import androidx.wear.tiles.RequestBuilders
import androidx.wear.tiles.TileBuilders.Tile
import androidx.wear.tiles.TileService
import com.google.common.util.concurrent.ListenableFuture
import me.pluralware.shared.api.PluralKitClientFactory
import me.pluralware.shared.repository.EncryptedTokenStore
import me.pluralware.shared.repository.PkResult
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.settings.LastFronterStore
import me.pluralware.wear.BuildConfig
import me.pluralware.wear.MainActivity
import me.pluralware.wear.complication.FronterComplicationFormatter

/**
 * A tile showing the current fronter(s) with a "Change" chip that opens the app.
 *
 * Reuses the complication's formatting ([FronterComplicationFormatter]) and
 * offline cache ([LastFronterStore]) so the tile and the complication never
 * disagree about how a switch reads.
 *
 * Freshness is twofold: a coarse [FRESHNESS_INTERVAL_MS] keeps it from going
 * stale while sitting in the carousel, and a push from
 * [requestFronterTileUpdate] refreshes it the instant the user changes fronter.
 */
class FronterTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<Tile> = SuspendToFutureAdapter.launchFuture {
        val body = currentFronterText()
        Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(FRESHNESS_INTERVAL_MS)
            .setTileTimeline(
                Timeline.fromLayoutElement(layout(body, requestParams.deviceConfiguration)),
            )
            .build()
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<Resources> = SuspendToFutureAdapter.launchFuture {
        // No bundled images; just version the (empty) resource set.
        Resources.Builder().setVersion(RESOURCES_VERSION).build()
    }

    /** Fetch the current fronter line, falling back to the cached one offline. */
    private suspend fun currentFronterText(): String {
        val token = EncryptedTokenStore.get(applicationContext).getToken()
            ?: return "Tap to set up"
        val repo = PluralKitRepository(
            PluralKitClientFactory.create(token = token, enableLogging = BuildConfig.DEBUG),
        )
        return when (val result = repo.refreshFronters()) {
            is PkResult.Success -> {
                val text = FronterComplicationFormatter.body(result.value)
                val timestamp = result.value?.timestamp?.toEpochMilli() ?: System.currentTimeMillis()
                LastFronterStore.get(applicationContext).set(text, timestamp)
                text
            }
            is PkResult.Failure ->
                LastFronterStore.get(applicationContext).get()?.text ?: "Unavailable"
        }
    }

    private fun layout(body: String, device: DeviceParameters): LayoutElement {
        val openApp = Clickable.Builder()
            .setId(CLICK_ID_OPEN)
            .setOnClick(
                ActionBuilders.LaunchAction.Builder()
                    .setAndroidActivity(
                        ActionBuilders.AndroidActivity.Builder()
                            .setPackageName(packageName)
                            .setClassName(MainActivity::class.java.name)
                            .build(),
                    )
                    .build(),
            )
            .build()

        return PrimaryLayout.Builder(device)
            .setResponsiveContentInsetEnabled(true)
            .setPrimaryLabelTextContent(
                Text.Builder(this, FronterComplicationFormatter.TITLE)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(argb(COLOR_AMBER))
                    .build(),
            )
            .setContent(
                Text.Builder(this, body)
                    .setTypography(Typography.TYPOGRAPHY_TITLE3)
                    .setColor(argb(COLOR_TEXT))
                    .setMaxLines(3)
                    .build(),
            )
            .setPrimaryChipContent(
                CompactChip.Builder(this, "Change", openApp, device).build(),
            )
            .build()
    }

    private companion object {
        const val RESOURCES_VERSION = "1"
        // Coarse: switches are infrequent and pushes cover the urgent case.
        const val FRESHNESS_INTERVAL_MS = 10L * 60L * 1000L
        const val CLICK_ID_OPEN = "open_app"

        // PluralWare palette as ARGB ints — ProtoLayout uses int colors, not Compose Color.
        const val COLOR_AMBER = 0xFFE6A12B.toInt()
        const val COLOR_TEXT = 0xFFC9CBD3.toInt()
    }
}
