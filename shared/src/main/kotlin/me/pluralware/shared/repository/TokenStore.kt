package me.pluralware.shared.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.pluralware.shared.api.PluralKitToken

/**
 * Where the PluralKit token lives. Implementations:
 *  - [EncryptedTokenStore] (Android, EncryptedSharedPreferences) — production.
 *  - [InMemoryTokenStore] — for tests and previews.
 *
 * Exposed both as suspend accessors (for one-shot reads/writes) and as a
 * [StateFlow] (so UI on the watch can recompose when the phone pushes a token).
 */
interface TokenStore {
    val tokenFlow: StateFlow<PluralKitToken?>
    suspend fun getToken(): PluralKitToken?
    suspend fun setToken(token: PluralKitToken)
    suspend fun clear()
}

class InMemoryTokenStore(initial: PluralKitToken? = null) : TokenStore {
    private val _flow = MutableStateFlow(initial)
    override val tokenFlow: StateFlow<PluralKitToken?> = _flow.asStateFlow()
    override suspend fun getToken(): PluralKitToken? = _flow.value
    override suspend fun setToken(token: PluralKitToken) { _flow.value = token }
    override suspend fun clear() { _flow.value = null }
}

/**
 * Production [TokenStore] backed by encrypted preferences ([EncryptedPrefs]).
 *
 * Singleton-per-process via [get] so the wear listener service and the
 * activity share one in-memory [tokenFlow] — writes from the service are
 * observed live by the activity without a manual reload.
 *
 * Reads/writes hit a single small prefs file with AES-256 keys + values.
 * All I/O runs on [Dispatchers.IO] under a mutex so concurrent setToken /
 * clear calls from different surfaces can't interleave.
 */
class EncryptedTokenStore private constructor(
    appContext: Context,
) : TokenStore {

    private val mutex = Mutex()
    private val prefs: SharedPreferences = EncryptedPrefs.open(appContext, PREFS_FILE)

    private val _flow = MutableStateFlow(prefs.readToken())
    override val tokenFlow: StateFlow<PluralKitToken?> = _flow.asStateFlow()

    override suspend fun getToken(): PluralKitToken? = _flow.value

    override suspend fun setToken(token: PluralKitToken) = withContext(Dispatchers.IO) {
        mutex.withLock {
            // commit(), not apply(): the watch deletes the handoff DataItem as
            // soon as this returns, so the token must be on disk by then.
            prefs.edit().putString(KEY_TOKEN, token.raw).commit()
            _flow.value = token
        }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        mutex.withLock {
            prefs.edit().remove(KEY_TOKEN).commit()
            _flow.value = null
        }
    }

    private fun SharedPreferences.readToken(): PluralKitToken? =
        getString(KEY_TOKEN, null)
            ?.takeIf { it.isNotBlank() }
            ?.let(::PluralKitToken)

    companion object {
        private const val PREFS_FILE = "pluralware_token_store"
        private const val KEY_TOKEN = "pluralkit_token"

        @Volatile private var instance: EncryptedTokenStore? = null

        /** Process-wide singleton. Application context is held — never an activity. */
        fun get(context: Context): EncryptedTokenStore =
            instance ?: synchronized(this) {
                instance ?: EncryptedTokenStore(context.applicationContext).also { instance = it }
            }
    }
}
