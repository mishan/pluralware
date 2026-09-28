package me.pluralware.shared.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.pluralware.shared.repository.EncryptedPrefs

/**
 * Last-known fronter line, shared by the watch's glanceable surfaces (the
 * complication and the tile). It does two jobs:
 *  - lets a surface answer from memory while the value is fresh, instead of
 *    each one fetching on every request;
 *  - gives them something useful to show when a fetch fails (offline, etc.)
 *    instead of a blank slot.
 *
 * It belongs to one token. Whoever changes the token clears it, and each line
 * records the token it came from ([LastFronter.tokenId]). So a fetch that was
 * already in flight during a re-pair can't put one system's fronters back on
 * another's watch face.
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

/**
 * A formatted fronter line, its screen-reader text, when it was fetched
 * (epoch millis), and which token it was fetched with ([tokenId], a hash, never
 * the token itself). A line from another token is never shown.
 */
data class LastFronter(
    val text: String,
    val description: String,
    val fetchedAtEpochMillis: Long,
    val tokenId: String,
)

class InMemoryLastFronterCache(private var value: LastFronter? = null) : LastFronterCache {
    override suspend fun get(): LastFronter? = value
    override suspend fun set(fronter: LastFronter) { value = fronter }
    override suspend fun clear() { value = null }
}

/**
 * Production [LastFronterCache].
 *
 * It holds display names, including members private in PluralKit, since the
 * system's own token sees them all, so it's encrypted like the token. It's
 * opened lazily, on the IO dispatcher every accessor already uses, which keeps
 * the Keystore work off the thread a complication request arrives on.
 *
 * Process-wide singleton via [get] so the complication service and any other
 * surface share one instance, matching [LocalSettingsStore].
 */
class LastFronterStore private constructor(appContext: Context) : LastFronterCache {

    private val prefs: SharedPreferences by lazy { EncryptedPrefs.open(appContext, PREFS_FILE) }

    override suspend fun get(): LastFronter? = withContext(Dispatchers.IO) {
        val text = prefs.getString(KEY_TEXT, null)?.takeIf { it.isNotBlank() }
            ?: return@withContext null
        LastFronter(
            text = text,
            description = prefs.getString(KEY_DESCRIPTION, null) ?: text,
            fetchedAtEpochMillis = prefs.getLong(KEY_FETCHED_AT, 0L),
            tokenId = prefs.getString(KEY_TOKEN_ID, null) ?: return@withContext null,
        )
    }

    override suspend fun set(fronter: LastFronter) = withContext(Dispatchers.IO) {
        prefs.edit()
            .putString(KEY_TEXT, fronter.text)
            .putString(KEY_DESCRIPTION, fronter.description)
            .putLong(KEY_FETCHED_AT, fronter.fetchedAtEpochMillis)
            .putString(KEY_TOKEN_ID, fronter.tokenId)
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
        private const val KEY_TOKEN_ID = "last_fronter_token_id"

        @Volatile private var instance: LastFronterStore? = null

        fun get(context: Context): LastFronterStore =
            instance ?: synchronized(this) {
                instance ?: LastFronterStore(context.applicationContext).also { instance = it }
            }
    }
}
