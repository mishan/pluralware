package me.pluralware.wear.complication

import android.app.PendingIntent
import android.content.Intent
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import me.pluralware.shared.api.PluralKitClientFactory
import me.pluralware.shared.repository.EncryptedTokenStore
import me.pluralware.shared.repository.PkResult
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.settings.LastFronterStore
import me.pluralware.wear.BuildConfig
import me.pluralware.wear.MainActivity

/**
 * LONG_TEXT complication showing the current PluralKit fronter(s).
 *
 * Reads the same process-wide token the rest of the app uses
 * ([EncryptedTokenStore]) and fetches fronters through a [PluralKitRepository].
 * Tapping opens the app home ([MainActivity]).
 *
 * Freshness is push-based (see [requestFronterComplicationUpdate]); this service
 * just answers each request with the latest it can fetch, falling back to the
 * last-known snapshot ([LastFronterStore]) when a fetch fails.
 */
class FronterComplicationService : SuspendingComplicationDataSourceService() {

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        // We only declare LONG_TEXT; defensively ignore anything else.
        if (request.complicationType != ComplicationType.LONG_TEXT) return null

        val token = EncryptedTokenStore.get(applicationContext).getToken()
            ?: return lastKnownOr(placeholder = "Tap to set up")

        val repo = PluralKitRepository(
            PluralKitClientFactory.create(token = token, enableLogging = BuildConfig.DEBUG),
        )
        return when (val result = repo.refreshFronters()) {
            is PkResult.Success -> {
                val body = FronterComplicationFormatter.body(result.value)
                val timestamp = result.value?.timestamp?.toEpochMilli() ?: System.currentTimeMillis()
                LastFronterStore.get(applicationContext).set(body, timestamp)
                longText(
                    body = body,
                    description = FronterComplicationFormatter.contentDescription(result.value),
                )
            }
            // Offline / token rotation / transient error: show the last good value
            // rather than a blank slot.
            is PkResult.Failure -> lastKnownOr(placeholder = "Unavailable")
        }
    }

    override fun getPreviewData(type: ComplicationType): ComplicationData? {
        if (type != ComplicationType.LONG_TEXT) return null
        return longText(body = "Fronting: Alex", description = "Current fronter: Alex")
    }

    /** Render the cached fronter if present, else a tappable [placeholder]. */
    private suspend fun lastKnownOr(placeholder: String): ComplicationData {
        val cached = LastFronterStore.get(applicationContext).get()
        return if (cached != null) {
            longText(body = cached.text, description = "Last known fronter: ${cached.text}")
        } else {
            longText(body = placeholder, description = placeholder)
        }
    }

    private fun longText(body: String, description: String): ComplicationData =
        LongTextComplicationData.Builder(
            text = PlainComplicationText.Builder(body).build(),
            contentDescription = PlainComplicationText.Builder(description).build(),
        )
            .setTitle(PlainComplicationText.Builder(FronterComplicationFormatter.TITLE).build())
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
