package me.pluralware.wear.tile

import androidx.concurrent.futures.SuspendToFutureAdapter
import androidx.wear.protolayout.ActionBuilders
import androidx.wear.protolayout.ColorBuilders.argb
import androidx.wear.protolayout.DeviceParametersBuilders.DeviceParameters
import androidx.wear.protolayout.LayoutElementBuilders.LayoutElement
import androidx.wear.protolayout.ModifiersBuilders.Clickable
import androidx.wear.protolayout.ModifiersBuilders.Modifiers
import androidx.wear.protolayout.ModifiersBuilders.Semantics
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
import me.pluralware.wear.MainActivity
import me.pluralware.wear.complication.FronterDisplay
import me.pluralware.wear.complication.FronterSurfaces

/**
 * A tile showing the current fronter(s) with a "Change" chip that opens the app.
 *
 * What it shows is decided by the same [me.pluralware.wear.complication.FronterSource]
 * as the complication, so the two never disagree.
 *
 * Freshness is twofold: a coarse [FRESHNESS_INTERVAL_MS] catches switches made
 * off the watch, and [FronterSurfaces.publish] refreshes it the moment the app
 * sees the fronter change.
 */
class FronterTileService : TileService() {

    override fun onTileRequest(
        requestParams: RequestBuilders.TileRequest,
    ): ListenableFuture<Tile> = SuspendToFutureAdapter.launchFuture {
        val display = FronterSurfaces.source(this@FronterTileService).current()
        Tile.Builder()
            .setResourcesVersion(RESOURCES_VERSION)
            .setFreshnessIntervalMillis(FRESHNESS_INTERVAL_MS)
            .setTileTimeline(
                Timeline.fromLayoutElement(layout(display, requestParams.deviceConfiguration)),
            )
            .build()
    }

    override fun onTileResourcesRequest(
        requestParams: RequestBuilders.ResourcesRequest,
    ): ListenableFuture<Resources> = SuspendToFutureAdapter.launchFuture {
        // No bundled images; just version the (empty) resource set.
        Resources.Builder().setVersion(RESOURCES_VERSION).build()
    }

    private fun layout(display: FronterDisplay, device: DeviceParameters): LayoutElement {
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
                Text.Builder(this, display.title)
                    .setTypography(Typography.TYPOGRAPHY_CAPTION1)
                    .setColor(argb(COLOR_AMBER))
                    .build(),
            )
            .setContent(
                Text.Builder(this, display.text)
                    // Read out every fronter, not the "+N" the line collapses to.
                    .setModifiers(
                        Modifiers.Builder()
                            .setSemantics(
                                Semantics.Builder().setContentDescription(display.description).build(),
                            )
                            .build(),
                    )
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
