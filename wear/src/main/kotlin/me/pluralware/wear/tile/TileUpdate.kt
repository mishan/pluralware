package me.pluralware.wear.tile

import android.content.Context
import androidx.wear.tiles.TileService

/**
 * Ask the system to re-request our fronter tile. Call after a switch is
 * registered so the tile reflects the change immediately rather than waiting for
 * its freshness interval to lapse.
 */
fun requestFronterTileUpdate(context: Context) {
    TileService.getUpdater(context.applicationContext)
        .requestUpdate(FronterTileService::class.java)
}
