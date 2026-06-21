package me.pluralware.shared.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Last-known fronter snapshot, cached so the complication can render something
 * useful when a refresh fails (offline, mid-token-rotation, etc.) instead of a
 * blank slot.
 *
 * This is display-only text — a member's already-public display name — so unlike
 * [me.pluralware.shared.repository.TokenStore] it lives in plain
 * [SharedPreferences], not encrypted storage.
 *
 * Process-wide singleton via [get] so the complication service and any other
 * surface share one instance, matching [LocalSettingsStore].
 */
class LastFronterStore private constructor(appContext: Context) {

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    /** The cached snapshot, or null if we've never recorded a successful fetch. */
    suspend fun get(): LastFronter? = withContext(Dispatchers.IO) {
        val text = prefs.getString(KEY_TEXT, null)?.takeIf { it.isNotBlank() }
            ?: return@withContext null
        LastFronter(
            text = text,
            updatedAtEpochMillis = prefs.getLong(KEY_UPDATED_AT, 0L),
        )
    }

    /** Overwrite the snapshot after a successful fronter fetch. */
    suspend fun set(text: String, updatedAtEpochMillis: Long) = withContext(Dispatchers.IO) {
        prefs.edit()
            .putString(KEY_TEXT, text)
            .putLong(KEY_UPDATED_AT, updatedAtEpochMillis)
            .apply()
    }

    companion object {
        private const val PREFS_FILE = "pluralware_last_fronter"
        private const val KEY_TEXT = "last_fronter_text"
        private const val KEY_UPDATED_AT = "last_fronter_updated_at"

        @Volatile private var instance: LastFronterStore? = null

        fun get(context: Context): LastFronterStore =
            instance ?: synchronized(this) {
                instance ?: LastFronterStore(context.applicationContext).also { instance = it }
            }
    }
}

/** A cached fronter line plus when it was recorded (epoch millis). */
data class LastFronter(
    val text: String,
    val updatedAtEpochMillis: Long,
)
