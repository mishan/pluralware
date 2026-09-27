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
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import me.pluralware.shared.repository.EncryptedPrefs

/**
 * A system this person follows, on the receiving side of Private mode
 * (docs/notifications-design.md §4.4). One UnifiedPush registration each,
 * keyed by [instance].
 */
@Serializable
data class Follow(
    val instance: String,
    val system: String,
    /** The system's VAPID public key, from the invite. */
    val vapid: String,
    /** How this person is named to the system. */
    val myName: String,
    /** Encoded [FollowCode] to send back, once the distributor has given us an endpoint. */
    val followCode: String? = null,
    /** Why registering failed, if it did. */
    val problem: String? = null,
    val lastText: String? = null,
    /** ISO-8601; when the system's latest switch happened. */
    val lastSwitchedAt: String? = null,
)

/** The receiving side's list of [Follow]s. It holds follow codes, which carry auth secrets. */
interface FollowingStore {
    val follows: StateFlow<List<Follow>>
    suspend fun upsert(follow: Follow)
    suspend fun update(instance: String, transform: (Follow) -> Follow)
    suspend fun remove(instance: String)
}

class InMemoryFollowingStore(initial: List<Follow> = emptyList()) : FollowingStore {
    private val _follows = MutableStateFlow(initial)
    override val follows: StateFlow<List<Follow>> = _follows.asStateFlow()
    override suspend fun upsert(follow: Follow) { _follows.value = _follows.value.upserting(follow) }
    override suspend fun update(instance: String, transform: (Follow) -> Follow) {
        _follows.value = _follows.value.map { if (it.instance == instance) transform(it) else it }
    }
    override suspend fun remove(instance: String) { _follows.value = _follows.value.filterNot { it.instance == instance } }
}

/** Production [FollowingStore], encrypted. Process-wide singleton via [get]. */
class EncryptedFollowingStore private constructor(appContext: Context) : FollowingStore {

    private val mutex = Mutex()
    private val prefs: SharedPreferences = EncryptedPrefs.open(appContext, PREFS_FILE)
    private val _follows = MutableStateFlow(read())
    override val follows: StateFlow<List<Follow>> = _follows.asStateFlow()

    override suspend fun upsert(follow: Follow) = write { it.upserting(follow) }

    override suspend fun update(instance: String, transform: (Follow) -> Follow) = write { list ->
        list.map { if (it.instance == instance) transform(it) else it }
    }

    override suspend fun remove(instance: String) = write { list -> list.filterNot { it.instance == instance } }

    // commit(), not apply(): already on IO, and the push service's process may
    // end as soon as its callback returns.
    private suspend fun write(change: (List<Follow>) -> List<Follow>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val next = change(_follows.value)
            prefs.edit().putString(KEY_FOLLOWS, json.encodeToString(serializer, next)).commit()
            _follows.value = next
        }
    }

    private fun read(): List<Follow> =
        prefs.getString(KEY_FOLLOWS, null)
            ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
            .orEmpty()

    companion object {
        private const val PREFS_FILE = "pluralware_following"
        private const val KEY_FOLLOWS = "follows"
        private val json = Json { ignoreUnknownKeys = true }
        private val serializer = ListSerializer(Follow.serializer())

        @Volatile private var instance: EncryptedFollowingStore? = null

        fun get(context: Context): EncryptedFollowingStore =
            instance ?: synchronized(this) {
                instance ?: EncryptedFollowingStore(context.applicationContext).also { instance = it }
            }
    }
}

private fun List<Follow>.upserting(follow: Follow): List<Follow> =
    if (any { it.instance == follow.instance }) map { if (it.instance == follow.instance) follow else it }
    else this + follow
