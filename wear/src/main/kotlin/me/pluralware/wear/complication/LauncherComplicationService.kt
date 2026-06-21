package me.pluralware.wear.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import me.pluralware.wear.MainActivity
import me.pluralware.wear.R

/**
 * A *launcher* complication — a one-tap shortcut into the app, not a data view.
 *
 * ~7 characters of SHORT_TEXT can't meaningfully show a fronter (that's what the
 * LONG_TEXT [FronterComplicationService] and the tile are for), so this offers
 * the other use of a small slot: a static glyph/label that opens PluralWare. No
 * token and no network are needed, so it renders instantly in any slot.
 *
 * Supports SHORT_TEXT (glyph + "PW") and MONOCHROMATIC_IMAGE (glyph only).
 */
class LauncherComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? =
        dataFor(request.complicationType)

    override fun getPreviewData(type: ComplicationType): ComplicationData? = dataFor(type)

    private fun dataFor(type: ComplicationType): ComplicationData? = when (type) {
        ComplicationType.SHORT_TEXT -> shortText()
        ComplicationType.MONOCHROMATIC_IMAGE -> iconOnly()
        else -> null
    }

    private fun shortText(): ComplicationData =
        ShortTextComplicationData.Builder(
            text = PlainComplicationText.Builder(LABEL).build(),
            contentDescription = PlainComplicationText.Builder(DESCRIPTION).build(),
        )
            .setMonochromaticImage(glyph())
            .setTapAction(openAppIntent())
            .build()

    private fun iconOnly(): ComplicationData =
        MonochromaticImageComplicationData.Builder(
            monochromaticImage = glyph(),
            contentDescription = PlainComplicationText.Builder(DESCRIPTION).build(),
        )
            .setTapAction(openAppIntent())
            .build()

    private fun glyph(): MonochromaticImage =
        MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_complication)).build()

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // Distinct request code from FronterComplicationService's intent so the
        // two PendingIntents don't alias.
        return PendingIntent.getActivity(
            this,
            1,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private companion object {
        const val LABEL = "PW"
        const val DESCRIPTION = "Open PluralWare"
    }
}
