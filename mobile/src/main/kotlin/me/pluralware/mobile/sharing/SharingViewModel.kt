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
import me.pluralware.shared.handoff.SharingHandoff
import me.pluralware.shared.model.Member
import me.pluralware.shared.model.SystemInfo
import me.pluralware.shared.notify.FollowCode
import me.pluralware.shared.notify.Friend
import me.pluralware.shared.notify.Invite
import me.pluralware.shared.notify.NtfyServer
import me.pluralware.shared.notify.NtfyTopics
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
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val config: StateFlow<SharingConfig> = sharingStore.configFlow

    init {
        load()
    }

    fun load() {
        _state.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            val token = tokenStore.getToken() ?: run {
                _state.update { it.copy(loading = false, loadError = "Connect your PluralKit account first.") }
                return@launch
            }
            try {
                val client = PluralKitClientFactory.create(
                    token = token,
                    appVersion = BuildConfig.VERSION_NAME,
                    enableLogging = BuildConfig.DEBUG,
                )
                val system = client.getOwnSystem()
                val members = client.getOwnMembers().sortedBy { it.displayLabel.lowercase() }
                _state.update { it.copy(loading = false, system = system, members = members) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadError = e.message ?: "Couldn't load your members.") }
            }
            val gone = runCatching { SharingHandoff.readStatus(appContext) }.getOrDefault(emptySet())
            _state.update { it.copy(goneFriendIds = gone) }
        }
    }

    fun toggleMember(uuid: String) = change { config ->
        val shared = config.sharedMemberUuids
        config.copy(sharedMemberUuids = if (uuid in shared) shared - uuid else shared + uuid)
    }

    fun setTitle(title: String) = change { it.copy(title = title.trim().ifEmpty { SharingConfig.DEFAULT_TITLE }) }

    /** The text to send a friend for Private mode; makes this system's VAPID key the first time. */
    fun invite(onReady: (String) -> Unit) {
        viewModelScope.launch {
            val withKey = editLock.withLock {
                val current = config.value
                if (current.vapid != null) current else current.copy(vapid = Vapid.generate()).also { save(it) }
            }
            onReady(Invite(system = withKey.title, vapid = withKey.vapid!!.publicKey).encode())
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

    fun setNtfyServer(baseUrl: String, accessToken: String) = change {
        val url = baseUrl.trim()
        it.copy(ntfy = if (url.isEmpty()) null else NtfyServer(url, accessToken.trim().ifEmpty { null }))
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

    // Serializes edits: each reads the config the previous one saved.
    private val editLock = Mutex()

    private fun change(transform: (SharingConfig) -> SharingConfig) {
        viewModelScope.launch { editLock.withLock { save(transform(config.value)) } }
    }

    private suspend fun save(next: SharingConfig) {
        sharingStore.set(next)
        // Best-effort: with no watch in reach, the Data Layer delivers it later.
        runCatching { SharingHandoff.push(appContext, next) }
    }

    class Factory(
        private val tokenStore: TokenStore,
        private val sharingStore: SharingStore,
        private val appContext: Context,
    ) : androidx.lifecycle.ViewModelProvider.Factory by viewModelFactory({
        initializer { SharingViewModel(tokenStore, sharingStore, appContext) }
    })
}
