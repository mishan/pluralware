package me.pluralware.shared.notify

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.pluralware.shared.repository.EncryptedPrefs

/**
 * Where a device keeps its [SharingConfig], plus — on the watch — the friends
 * whose push subscriptions have gone away ([goneFriendIds]). Those are the
 * watch's own findings: they survive the phone's next config push, except for
 * friends the phone has since removed.
 */
interface SharingStore {
    val configFlow: StateFlow<SharingConfig>
    val goneFlow: StateFlow<Set<String>>
    suspend fun set(config: SharingConfig)
    suspend fun markGone(friendIds: Set<String>)
    suspend fun clear()
}

class InMemorySharingStore(initial: SharingConfig = SharingConfig()) : SharingStore {
    private val _config = MutableStateFlow(initial)
    private val _gone = MutableStateFlow(emptySet<String>())
    override val configFlow: StateFlow<SharingConfig> = _config.asStateFlow()
    override val goneFlow: StateFlow<Set<String>> = _gone.asStateFlow()
    override suspend fun set(config: SharingConfig) {
        _config.value = config
        _gone.value = _gone.value.retainFriendsOf(config)
    }
    override suspend fun markGone(friendIds: Set<String>) { _gone.value = _gone.value + friendIds }
    override suspend fun clear() {
        _config.value = SharingConfig()
        _gone.value = emptySet()
    }
}

/** Production [SharingStore], encrypted like the token. Process-wide singleton via [get]. */
class EncryptedSharingStore private constructor(appContext: Context) : SharingStore {

    private val mutex = Mutex()
    private val prefs: SharedPreferences = EncryptedPrefs.open(appContext, PREFS_FILE)

    private val _config = MutableStateFlow(readConfig())
    private val _gone = MutableStateFlow(prefs.getStringSet(KEY_GONE, null).orEmpty().toSet())
    override val configFlow: StateFlow<SharingConfig> = _config.asStateFlow()
    override val goneFlow: StateFlow<Set<String>> = _gone.asStateFlow()

    override suspend fun set(config: SharingConfig) = write {
        val gone = _gone.value.retainFriendsOf(config)
        prefs.edit().putString(KEY_CONFIG, config.toJson()).putStringSet(KEY_GONE, gone).commit()
        _config.value = config
        _gone.value = gone
    }

    override suspend fun markGone(friendIds: Set<String>) = write {
        val gone = _gone.value + friendIds
        prefs.edit().putStringSet(KEY_GONE, gone).commit()
        _gone.value = gone
    }

    override suspend fun clear() = write {
        prefs.edit().clear().commit()
        _config.value = SharingConfig()
        _gone.value = emptySet()
    }

    // Writes use commit(), not apply(): they already run on IO, and the watch
    // deletes the handoff DataItem as soon as set() returns.
    private suspend fun write(block: () -> Unit) = withContext(Dispatchers.IO) {
        mutex.withLock { block() }
    }

    private fun readConfig(): SharingConfig =
        prefs.getString(KEY_CONFIG, null)
            ?.let { runCatching { SharingConfig.fromJson(it) }.getOrNull() }
            ?: SharingConfig()

    companion object {
        private const val PREFS_FILE = "pluralware_sharing"
        private const val KEY_CONFIG = "config"
        private const val KEY_GONE = "gone_friend_ids"

        @Volatile private var instance: EncryptedSharingStore? = null

        fun get(context: Context): EncryptedSharingStore =
            instance ?: synchronized(this) {
                instance ?: EncryptedSharingStore(context.applicationContext).also { instance = it }
            }
    }
}

private fun Set<String>.retainFriendsOf(config: SharingConfig): Set<String> {
    val ids = config.friends.mapTo(HashSet()) { it.id }
    return filterTo(HashSet()) { it in ids }
}
