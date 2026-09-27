package me.pluralware.wear.complication

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import me.pluralware.wear.MainActivity

/**
 * LONG_TEXT complication showing the current PluralKit fronter(s).
 *
 * What it shows is decided by [FronterSource], shared with the tile. Tapping
 * opens the app home ([MainActivity]).
 *
 * Freshness: the app pushes an update whenever it sees the fronter change
 * ([FronterSurfaces.publish]), and the manifest's update period catches
 * switches made elsewhere.
 */
class FronterComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        // We only declare LONG_TEXT; defensively ignore anything else.
        if (request.complicationType != ComplicationType.LONG_TEXT) return null

        return longText(FronterSurfaces.source(this).current())
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.LONG_TEXT) return null
        return longText(
            FronterDisplay(
                title = FronterComplicationFormatter.TITLE,
                text = "Fronting: Alex",
                description = "Current fronter: Alex",
            ),
        )
    }

    private fun longText(display: FronterDisplay): ComplicationData =
        LongTextComplicationData.Builder(
            text = PlainComplicationText.Builder(display.text).build(),
            contentDescription = PlainComplicationText.Builder(display.description).build(),
        )
            .setTitle(PlainComplicationText.Builder(display.title).build())
            .setTapAction(openAppIntent())
            .build()

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
