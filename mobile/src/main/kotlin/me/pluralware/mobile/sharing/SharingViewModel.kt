package me.pluralware.mobile.sharing

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.pluralware.mobile.BuildConfig
import me.pluralware.shared.api.PluralKitClientFactory
import me.pluralware.shared.api.PluralKitToken
import me.pluralware.shared.handoff.SharingHandoff
import me.pluralware.shared.model.Member
import me.pluralware.shared.model.SystemInfo
import me.pluralware.shared.notify.FollowCode
import me.pluralware.shared.notify.Friend
import me.pluralware.shared.notify.Invite
import me.pluralware.shared.notify.NtfyServer
import me.pluralware.shared.notify.NtfyTopics
import me.pluralware.shared.notify.RelayClient
import me.pluralware.shared.notify.RelayConfig
import me.pluralware.shared.notify.RelaySettings
import me.pluralware.shared.notify.SharingConfig
import me.pluralware.shared.notify.SharingStore
import me.pluralware.shared.notify.Vapid
import me.pluralware.shared.repository.TokenStore
import kotlin.coroutines.cancellation.CancellationException

/**
 * The phone's side of sharing switches with friends
 * (docs/notifications-design.md). It owns the [SharingConfig]: every change
 * is saved here and pushed to the watch, which does the sending.
 */
class SharingViewModel(
    private val tokenStore: TokenStore,
    private val sharingStore: SharingStore,
    private val appContext: Context,
    // Seams for tests; the defaults are the real things.
    private val loadSystem: suspend (PluralKitToken) -> Pair<SystemInfo, List<Member>> = ::loadFromPluralKit,
    private val relayClient: RelayClient = RelayClient.create(BuildConfig.VERSION_NAME),
    private val pushToWatch: suspend (SharingConfig) -> Unit = { SharingHandoff.push(appContext, it) },
    private val readWatchStatus: suspend () -> Set<String> = { SharingHandoff.readStatus(appContext) },
) : ViewModel() {

    data class State(
        val loading: Boolean = true,
        val loadError: String? = null,
        val system: SystemInfo? = null,
        val members: List<Member> = emptyList(),
        /** Friends the watch reports as no longer subscribed. */
        val goneFriendIds: Set<String> = emptySet(),
        val followCodeError: String? = null,
        /** Set after adding a Simple-mode friend, so the screen can show what to send them. */
        val newTopicUrl: String? = null,
        /** The relay's last complaint, or null when the last upload worked. */
        val relayError: String? = null,
        /** Whether the relay holds our config (it answers PluralKit's checks once it does). */
        val relayConfigured: Boolean = false,
        /** Removing the relay couldn't reach it; the screen offers "Remove anyway". */
        val relayRemoveFailed: Boolean = false,
        val ntfyError: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val config: StateFlow<SharingConfig> = sharingStore.configFlow

    /** Relays let go of while unreachable; see [SharingStore.orphanedRelays]. */
    val orphanedRelays: StateFlow<List<RelaySettings>> = sharingStore.orphanedRelays

    // Local edits run one at a time, each reading the config the previous one
    // saved. Relay uploads run one at a time on their own lock, each sending
    // the config as it is when its turn comes, so an older upload can never
    // land after a newer one, and no edit waits on the network.
    private val editLock = Mutex()
    private val relayLock = Mutex()

    init {
        load()
        retryOrphans()
    }

    fun load() {
        _state.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            val token = tokenStore.getToken() ?: run {
                _state.update { it.copy(loading = false, loadError = "Connect your PluralKit account first.") }
                return@launch
            }
            try {
                val (system, members) = loadSystem(token)
                _state.update { it.copy(loading = false, system = system, members = members) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadError = e.message ?: "Couldn't load your members.") }
            }
            val watchGone = runCatching { readWatchStatus() }.getOrDefault(emptySet())
            val relayStatus = config.value.relay?.let { relay ->
                runCatching { relayClient.status(relay) }.getOrNull()
            }
            _state.update {
                it.copy(
                    goneFriendIds = watchGone + relayStatus?.gone.orEmpty(),
                    relayConfigured = relayStatus?.configured == true,
                )
            }
            // Members are loaded now, so the relay's name list can be complete.
            requestRelaySync()
        }
    }

    fun toggleMember(uuid: String) = change { config ->
        val shared = config.sharedMemberUuids
        config.copy(sharedMemberUuids = if (uuid in shared) shared - uuid else shared + uuid)
    }

    fun setTitle(title: String) = change { it.copy(title = title.trim().ifEmpty { SharingConfig.DEFAULT_TITLE }) }

    /** The invite link to send a friend for Private mode; makes this system's VAPID key the first time. */
    fun invite(onReady: (String) -> Unit) {
        viewModelScope.launch {
            val withKey = editLock.withLock {
                val current = config.value
                if (current.vapid != null) current else current.copy(vapid = Vapid.generate()).also { save(it) }
            }
            onReady(Invite(system = withKey.title, vapid = withKey.vapid!!.publicKey).link())
        }
    }

    /** Adds a Private-mode friend from the follow code they sent back. Returns whether it worked. */
    fun addFollowCode(text: String, label: String): Boolean {
        val code = FollowCode.parse(text)
        if (code == null) {
            _state.update { it.copy(followCodeError = "That isn't a PluralWare follow code.") }
            return false
        }
        if (config.value.vapid == null) {
            _state.update { it.copy(followCodeError = "Send them an invite first; the follow code answers it.") }
            return false
        }
        _state.update { it.copy(followCodeError = null) }
        val friend = Friend.Private(
            id = Friend.newId(),
            label = label.trim().ifEmpty { code.name },
            followCode = code,
        )
        change { it.copy(friends = it.friends + friend) }
        return true
    }

    fun setNtfyServer(baseUrl: String, accessToken: String) {
        val url = baseUrl.trim()
        if (url.isNotEmpty() && !FollowCode.isPushEndpoint(url)) {
            _state.update { it.copy(ntfyError = "Use the server's https address.") }
            return
        }
        _state.update { it.copy(ntfyError = null) }
        change { it.copy(ntfy = if (url.isEmpty()) null else NtfyServer(url, accessToken.trim().ifEmpty { null })) }
    }

    fun addSimpleFriend(label: String) {
        val server = config.value.ntfy ?: return
        val topic = NtfyTopics.generate()
        val friend = Friend.Simple(id = Friend.newId(), label = label.trim().ifEmpty { "Friend" }, topic = topic)
        change { it.copy(friends = it.friends + friend) }
        _state.update { it.copy(newTopicUrl = NtfyTopics.url(server, topic)) }
    }

    fun dismissNewTopic() = _state.update { it.copy(newTopicUrl = null) }

    fun removeFriend(id: String) = change { config -> config.copy(friends = config.friends.filterNot { it.id == id }) }

    /**
     * Points sharing at a relay the user deployed (docs/notifications-design.md §10).
     * A different address is a different relay: the old one is told to forget
     * us, and the new one starts unconfirmed, with a fresh webhook path.
     */
    fun saveRelay(url: String, adminSecret: String) {
        viewModelScope.launch {
            editLock.withLock {
                val trimmedUrl = url.trim().trimEnd('/')
                val secret = adminSecret.trim()
                val old = config.value.relay
                val next = if (old != null && old.url == trimmedUrl) {
                    old.copy(adminSecret = secret)
                } else {
                    old?.let { letGo(it) }
                    RelaySettings(url = trimmedUrl, adminSecret = secret)
                }
                save(config.value.copy(relay = next))
            }
        }
    }

    /** The token PluralKit showed for the webhook; uploading it lets the relay pass PluralKit's check. */
    fun setSigningToken(token: String) = change { config ->
        config.copy(relay = config.relay?.copy(signingToken = token.trim().ifEmpty { null }))
    }

    /**
     * Hands sending to the relay, or back to the watch. Turning it on waits
     * for the relay to accept the change, and only then tells the watch to
     * stop; otherwise nobody would send. Turning it off tells the watch first,
     * for the same reason.
     */
    fun setRelayEnabled(enabled: Boolean) {
        viewModelScope.launch {
            editLock.withLock {
                val relay = config.value.relay ?: return@withLock
                val next = config.value.copy(relay = relay.copy(enabled = enabled))
                if (!enabled) {
                    save(next)
                    return@withLock
                }
                relayLock.withLock {
                    if (uploadNow(next)) saveLocally(next)
                }
            }
        }
    }

    /** Deletes our settings from the relay, then forgets it. If it can't be reached, asks first. */
    fun removeRelay() {
        viewModelScope.launch {
            editLock.withLock {
                val relay = config.value.relay ?: return@withLock
                try {
                    relayClient.clear(relay)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _state.update {
                        it.copy(
                            relayRemoveFailed = true,
                            relayError = "Couldn't reach the relay to delete your settings from it, so it " +
                                "still has them and may keep sending.",
                        )
                    }
                    return@withLock
                }
                save(config.value.copy(relay = null))
                _state.update { it.copy(relayError = null, relayConfigured = false, relayRemoveFailed = false) }
            }
        }
    }

    /** Stops using an unreachable relay, remembering it to delete from later ([orphanedRelays]). */
    fun removeRelayAnyway() {
        viewModelScope.launch {
            editLock.withLock {
                val relay = config.value.relay ?: return@withLock
                sharingStore.addOrphan(relay)
                save(config.value.copy(relay = null))
                _state.update { it.copy(relayError = null, relayConfigured = false, relayRemoveFailed = false) }
            }
        }
    }

    /** Tries again to delete our settings from relays let go of while unreachable. */
    fun retryOrphans() {
        viewModelScope.launch {
            for (relay in sharingStore.orphanedRelays.value) {
                if (runCatching { relayClient.clear(relay) }.isSuccess) sharingStore.removeOrphan(relay)
            }
        }
    }

    /** Stops trying to reach a relay the user has dealt with themselves (e.g. deleted it). */
    fun forgetOrphan(relay: RelaySettings) {
        viewModelScope.launch { sharingStore.removeOrphan(relay) }
    }

    fun retryRelay() = requestRelaySync()

    private fun change(transform: (SharingConfig) -> SharingConfig) {
        viewModelScope.launch { editLock.withLock { save(transform(config.value)) } }
    }

    /** Saves locally and on the watch, then brings the relay up to date in the background. */
    private suspend fun save(next: SharingConfig) {
        saveLocally(next)
        requestRelaySync()
    }

    private suspend fun saveLocally(next: SharingConfig) {
        sharingStore.set(next)
        // Best-effort: with no watch in reach, the Data Layer delivers it later.
        // The watch gets no relay secrets (forWatch).
        runCatching { pushToWatch(next.forWatch()) }
    }

    /** Asks the relay to forget us; if it can't be reached, remembers it for later. */
    private suspend fun letGo(relay: RelaySettings) {
        if (runCatching { relayClient.clear(relay) }.isFailure) sharingStore.addOrphan(relay)
    }

    private fun requestRelaySync() {
        viewModelScope.launch { relayLock.withLock { uploadNow(config.value) } }
    }

    /**
     * Uploads [config] to its relay; true if the relay has it. Says why not,
     * rather than skipping quietly: an upload before the members load would
     * tell the relay to name nobody, so that waits, visibly.
     */
    private suspend fun uploadNow(config: SharingConfig): Boolean {
        val relay = config.relay ?: return false
        if (relay.signingToken == null) return false
        if (_state.value.system == null) {
            _state.update {
                it.copy(relayError = "Your members haven't loaded, so the relay can't be updated yet. Retry once they have.")
            }
            return false
        }
        val upload = RelayConfig.from(config, _state.value.members) ?: return false
        return try {
            relayClient.upload(relay, upload)
            _state.update { it.copy(relayError = null, relayConfigured = true) }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update {
                it.copy(relayError = (e.message ?: "Couldn't reach the relay.") + " It doesn't have your latest changes.")
            }
            false
        }
    }

    private companion object {
        suspend fun loadFromPluralKit(token: PluralKitToken): Pair<SystemInfo, List<Member>> {
            val client = PluralKitClientFactory.create(
                token = token,
                appVersion = BuildConfig.VERSION_NAME,
                enableLogging = BuildConfig.DEBUG,
            )
            return client.getOwnSystem() to client.getOwnMembers().sortedBy { it.displayLabel.lowercase() }
        }
    }

    class Factory(
        private val tokenStore: TokenStore,
        private val sharingStore: SharingStore,
        private val appContext: Context,
    ) : androidx.lifecycle.ViewModelProvider.Factory by viewModelFactory({
        initializer { SharingViewModel(tokenStore, sharingStore, appContext) }
    })
}
