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
 * Where a device keeps its [SharingConfig], and the friends whose push
 * subscriptions have gone away (friend id → when found gone, epoch millis).
 *
 * [goneFlow] holds this device's own findings, from its own sends.
 * [peerGoneFlow] holds the other device's, as it last reported them
 * ([setPeerGone]). They're kept apart so a device only ever reports its own:
 * nothing it learned from the other comes back to it, and a friend the other
 * device clears stays cleared. Both survive the phone's next config push,
 * except for friends the phone has since removed. [GoneFriends] decides when
 * to try one again, across both.
 */
interface SharingStore {
    val configFlow: StateFlow<SharingConfig>
    val goneFlow: StateFlow<Map<String, Long>>
    val peerGoneFlow: StateFlow<Map<String, Long>>

    /**
     * Relays the phone let go of (removed, replaced, or disconnected from) but
     * couldn't reach to delete their copy. They still hold follow codes and the
     * VAPID key, and may still send, so the phone keeps their admin details
     * until a delete gets through. Survives [clear].
     */
    val orphanedRelays: StateFlow<List<RelaySettings>>

    suspend fun set(config: SharingConfig)
    suspend fun markGone(friendIds: Set<String>, atEpochMillis: Long)
    suspend fun clearGone(friendIds: Set<String>)

    /** Replaces the other device's gone friends with what it reported now. */
    suspend fun setPeerGone(gone: Map<String, Long>)
    suspend fun addOrphan(relay: RelaySettings)
    suspend fun removeOrphan(relay: RelaySettings)
    suspend fun clear()
}

/**
 * Friends whose subscription came back 404/410 are skipped, but not forever:
 * one misattributed error (a misconfigured push server, a proxy) would
 * otherwise mute someone until they were removed and added again. Each gets
 * one try a day; a delivery clears the mark.
 */
object GoneFriends {
    const val RETRY_AFTER_MILLIS = 24L * 60 * 60 * 1000

    fun toSkip(gone: Map<String, Long>, nowEpochMillis: Long): Set<String> =
        gone.filterValues { nowEpochMillis - it in 0 until RETRY_AFTER_MILLIS }.keys

    /** Both devices' marks, keeping the later one where both marked a friend. */
    fun merged(own: Map<String, Long>, peer: Map<String, Long>): Map<String, Long> =
        own + peer.mapValues { (id, at) -> own[id]?.let { maxOf(it, at) } ?: at }

    /** Only marks for friends still in [config]; shared by both stores. */
    internal fun retain(gone: Map<String, Long>, config: SharingConfig): Map<String, Long> {
        val ids = config.friends.mapTo(HashSet()) { it.id }
        return gone.filterKeys { it in ids }
    }
}

class InMemorySharingStore(initial: SharingConfig = SharingConfig()) : SharingStore {
    private val _config = MutableStateFlow(initial)
    private val _gone = MutableStateFlow(emptyMap<String, Long>())
    private val _peerGone = MutableStateFlow(emptyMap<String, Long>())
    private val _orphans = MutableStateFlow(emptyList<RelaySettings>())
    override val configFlow: StateFlow<SharingConfig> = _config.asStateFlow()
    override val goneFlow: StateFlow<Map<String, Long>> = _gone.asStateFlow()
    override val peerGoneFlow: StateFlow<Map<String, Long>> = _peerGone.asStateFlow()
    override val orphanedRelays: StateFlow<List<RelaySettings>> = _orphans.asStateFlow()
    override suspend fun setPeerGone(gone: Map<String, Long>) {
        _peerGone.value = GoneFriends.retain(gone, _config.value)
    }
    override suspend fun addOrphan(relay: RelaySettings) {
        if (relay !in _orphans.value) _orphans.value = _orphans.value + relay
    }
    override suspend fun removeOrphan(relay: RelaySettings) { _orphans.value = _orphans.value - relay }
    override suspend fun set(config: SharingConfig) {
        _config.value = config
        _gone.value = GoneFriends.retain(_gone.value, config)
        _peerGone.value = GoneFriends.retain(_peerGone.value, config)
    }
    override suspend fun markGone(friendIds: Set<String>, atEpochMillis: Long) {
        _gone.value = _gone.value + friendIds.associateWith { atEpochMillis }
    }
    override suspend fun clearGone(friendIds: Set<String>) { _gone.value = _gone.value - friendIds }
    override suspend fun clear() {
        // Like the real store: orphaned relays outlive a sign-out.
        _config.value = SharingConfig()
        _gone.value = emptyMap()
        _peerGone.value = emptyMap()
    }
}

/** Production [SharingStore], encrypted like the token. Process-wide singleton via [get]. */
class EncryptedSharingStore private constructor(appContext: Context) : SharingStore {

    private val mutex = Mutex()
    private val prefs: SharedPreferences = EncryptedPrefs.open(appContext, PREFS_FILE)

    private val _config = MutableStateFlow(readConfig())
    private val _gone = MutableStateFlow(readGone(KEY_GONE))
    private val _peerGone = MutableStateFlow(readGone(KEY_PEER_GONE))
    private val _orphans = MutableStateFlow(readOrphans())
    override val configFlow: StateFlow<SharingConfig> = _config.asStateFlow()
    override val goneFlow: StateFlow<Map<String, Long>> = _gone.asStateFlow()
    override val peerGoneFlow: StateFlow<Map<String, Long>> = _peerGone.asStateFlow()
    override val orphanedRelays: StateFlow<List<RelaySettings>> = _orphans.asStateFlow()

    override suspend fun addOrphan(relay: RelaySettings) =
        if (relay in _orphans.value) Unit else writeOrphans(_orphans.value + relay)

    override suspend fun removeOrphan(relay: RelaySettings) = writeOrphans(_orphans.value - relay)

    private suspend fun writeOrphans(orphans: List<RelaySettings>) = write {
        prefs.edit().putString(KEY_ORPHANS, orphansJson.encodeToString(orphansSerializer, orphans)).commit()
        _orphans.value = orphans
    }

    private fun readOrphans(): List<RelaySettings> =
        prefs.getString(KEY_ORPHANS, null)
            ?.let { runCatching { orphansJson.decodeFromString(orphansSerializer, it) }.getOrNull() }
            .orEmpty()

    override suspend fun set(config: SharingConfig) = write {
        val gone = GoneFriends.retain(_gone.value, config)
        val peerGone = GoneFriends.retain(_peerGone.value, config)
        prefs.edit()
            .putString(KEY_CONFIG, config.toJson())
            .putStringSet(KEY_GONE, gone.encoded())
            .putStringSet(KEY_PEER_GONE, peerGone.encoded())
            .commit()
        _config.value = config
        _gone.value = gone
        _peerGone.value = peerGone
    }

    override suspend fun markGone(friendIds: Set<String>, atEpochMillis: Long) =
        writeGone(_gone.value + friendIds.associateWith { atEpochMillis })

    override suspend fun clearGone(friendIds: Set<String>) = writeGone(_gone.value - friendIds)

    private suspend fun writeGone(gone: Map<String, Long>) = write {
        prefs.edit().putStringSet(KEY_GONE, gone.encoded()).commit()
        _gone.value = gone
    }

    override suspend fun setPeerGone(gone: Map<String, Long>) = write {
        val kept = GoneFriends.retain(gone, _config.value)
        prefs.edit().putStringSet(KEY_PEER_GONE, kept.encoded()).commit()
        _peerGone.value = kept
    }

    private fun Map<String, Long>.encoded(): Set<String> = GoneMarks.encode(this).toHashSet()

    private fun readGone(key: String): Map<String, Long> = GoneMarks.decode(prefs.getStringSet(key, null).orEmpty())

    override suspend fun clear() = write {
        // Orphaned relays outlive a sign-out: they're exactly what it leaves behind.
        prefs.edit().remove(KEY_CONFIG).remove(KEY_GONE).remove(KEY_PEER_GONE).commit()
        _config.value = SharingConfig()
        _gone.value = emptyMap()
        _peerGone.value = emptyMap()
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
        private const val KEY_GONE = "gone_friends"
        private const val KEY_PEER_GONE = "peer_gone_friends"
        private const val KEY_ORPHANS = "orphaned_relays"
        private val orphansJson = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        private val orphansSerializer = kotlinx.serialization.builtins.ListSerializer(RelaySettings.serializer())

        @Volatile private var instance: EncryptedSharingStore? = null

        fun get(context: Context): EncryptedSharingStore =
            instance ?: synchronized(this) {
                instance ?: EncryptedSharingStore(context.applicationContext).also { instance = it }
            }
    }
}

/**
 * Gone marks as "id@millis" strings: what SharedPreferences and the Data
 * Layer both carry easily. Entries that don't parse are dropped.
 */
object GoneMarks {
    fun encode(gone: Map<String, Long>): List<String> = gone.map { (id, at) -> "$id@$at" }

    fun decode(entries: Collection<String>): Map<String, Long> =
        entries.mapNotNull { entry ->
            val at = entry.substringAfterLast('@', "").toLongOrNull() ?: return@mapNotNull null
            val id = entry.substringBeforeLast('@').takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            id to at
        }.toMap()
}
