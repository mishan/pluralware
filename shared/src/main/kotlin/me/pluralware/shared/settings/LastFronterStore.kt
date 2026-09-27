package me.pluralware.shared.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Last-known fronter line, shared by the watch's glanceable surfaces (the
 * complication and the tile). It does two jobs:
 *  - lets a surface answer from memory while the value is fresh, instead of
 *    each one fetching on every request;
 *  - gives them something useful to show when a fetch fails (offline, etc.)
 *    instead of a blank slot.
 *
 * It belongs to one token: whoever changes the token clears it, so one
 * system's fronters are never shown for another.
 *
 * Implementations:
 *  - [LastFronterStore] (Android SharedPreferences) — production.
 *  - [InMemoryLastFronterCache] — for tests.
 */
interface LastFronterCache {
    /** The cached snapshot, or null if there is none. */
    suspend fun get(): LastFronter?
    suspend fun set(fronter: LastFronter)
    suspend fun clear()
}

/** A formatted fronter line, its screen-reader text, and when it was fetched (epoch millis). */
data class LastFronter(
    val text: String,
    val description: String,
    val fetchedAtEpochMillis: Long,
)

class InMemoryLastFronterCache(private var value: LastFronter? = null) : LastFronterCache {
    override suspend fun get(): LastFronter? = value
    override suspend fun set(fronter: LastFronter) { value = fronter }
    override suspend fun clear() { value = null }
}

/**
 * Production [LastFronterCache].
 *
 * This is display-only text — a member's already-public display name — so unlike
 * [me.pluralware.shared.repository.TokenStore] it lives in plain
 * [SharedPreferences], not encrypted storage.
 *
 * Process-wide singleton via [get] so the complication service and any other
 * surface share one instance, matching [LocalSettingsStore].
 */
class LastFronterStore private constructor(appContext: Context) : LastFronterCache {

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    override suspend fun get(): LastFronter? = withContext(Dispatchers.IO) {
        val text = prefs.getString(KEY_TEXT, null)?.takeIf { it.isNotBlank() }
            ?: return@withContext null
        LastFronter(
            text = text,
            description = prefs.getString(KEY_DESCRIPTION, null) ?: text,
            fetchedAtEpochMillis = prefs.getLong(KEY_FETCHED_AT, 0L),
        )
    }

    override suspend fun set(fronter: LastFronter) = withContext(Dispatchers.IO) {
        prefs.edit()
            .putString(KEY_TEXT, fronter.text)
            .putString(KEY_DESCRIPTION, fronter.description)
            .putLong(KEY_FETCHED_AT, fronter.fetchedAtEpochMillis)
            .apply()
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            // commit(): a surface re-requested right after a sign-out must not
            // read the old system's line back.
            prefs.edit().clear().commit()
        }
    }

    companion object {
        private const val PREFS_FILE = "pluralware_last_fronter"
        private const val KEY_TEXT = "last_fronter_text"
        private const val KEY_DESCRIPTION = "last_fronter_description"
        private const val KEY_FETCHED_AT = "last_fronter_fetched_at"

        @Volatile private var instance: LastFronterStore? = null

        fun get(context: Context): LastFronterStore =
            instance ?: synchronized(this) {
                instance ?: LastFronterStore(context.applicationContext).also { instance = it }
            }
    }
}
