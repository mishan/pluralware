package me.pluralware.mobile

import android.content.Context
import android.util.Log
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.pluralware.shared.model.SystemInfo
import me.pluralware.shared.notify.RelaySettings
import me.pluralware.shared.notify.SharingConfig
import me.pluralware.shared.notify.SharingStore
import me.pluralware.shared.repository.TokenStore

/**
 * Signing in and out on the phone, and what happens to friend sharing then.
 *
 * Sharing belongs to a system, so it's kept across a sign-out PluralKit forced
 * (a refreshed token: same system, same friends) and dropped only by a
 * deliberate [disconnect], or when a different system connects ([connected]).
 * [owner] remembers which system the sharing belongs to.
 *
 * Its work runs in [scope], not a screen's: signing in or out replaces the
 * screens. The token is cleared first, so the app signs out at once, and the
 * cleanup after it can't keep the app signed in, or crash it.
 */
class Account(
    private val scope: CoroutineScope,
    private val tokenStore: TokenStore,
    private val sharingStore: SharingStore,
    private val owner: SharingOwner,
    /** Tells a relay to forget this system; false if it couldn't be reached. */
    private val clearRelay: suspend (RelaySettings) -> Boolean,
    private val signOutWatch: suspend () -> Unit,
    private val pushSharingToWatch: suspend (SharingConfig) -> Unit,
) {
    private val _signingOut = MutableStateFlow(false)
    val signingOut: StateFlow<Boolean> = _signingOut.asStateFlow()

    /** Called with the system a token was just validated for, before it's saved. */
    suspend fun connected(system: SystemInfo) {
        val previous = owner.get()
        if (previous != null && previous != system.uuid) {
            // Another system's friends must not hear about this one's switches.
            forgetSharing()
        }
        owner.set(system.uuid)
        // A watch signed out since has dropped its copy; give it back.
        val config = sharingStore.configFlow.value
        if (config.isSharing) step("send sharing to the watch") { pushSharingToWatch(config.forWatch()) }
    }

    /** The user's Disconnect: forget this system entirely, here and on the watch. */
    fun disconnect() = signOut(keepSharing = false)

    /**
     * PluralKit rejected the token. Sign out here and on the watch, but keep
     * sharing, for when the same system connects with its new token.
     */
    fun signOutRejected() = signOut(keepSharing = true)

    private fun signOut(keepSharing: Boolean) {
        if (_signingOut.value) return
        _signingOut.value = true
        scope.launch {
            try {
                step("clear the token") { tokenStore.clear() }
                // Best-effort: with no watch in reach, the Data Layer delivers
                // it on the next sync.
                step("sign the watch out") { signOutWatch() }
                if (!keepSharing) {
                    forgetSharing()
                    step("forget the owner") { owner.set(null) }
                }
            } finally {
                _signingOut.value = false
            }
        }
    }

    private suspend fun forgetSharing() {
        // The relay, if any, is told to forget its copy. Unreachable: keep its
        // admin details (orphans survive clear()) so the sharing screen can
        // delete from it later.
        sharingStore.configFlow.value.relay?.let { relay ->
            val cleared = try {
                clearRelay(relay)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                false
            }
            if (!cleared) step("remember the relay") { sharingStore.addOrphan(relay) }
        }
        step("clear sharing") { sharingStore.clear() }
    }

    private suspend fun step(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "Couldn't $what", e) }
        }
    }

    private companion object {
        const val TAG = "Account"
    }
}

/** Which system the phone's friend sharing belongs to, by system uuid. */
interface SharingOwner {
    suspend fun get(): String?
    suspend fun set(systemUuid: String?)
}

class InMemorySharingOwner(private var value: String? = null) : SharingOwner {
    override suspend fun get() = value
    override suspend fun set(systemUuid: String?) { value = systemUuid }
}

/** Not secret (a system uuid is public in PluralKit), so plain preferences. */
class PrefsSharingOwner(context: Context) : SharingOwner {
    private val prefs = context.applicationContext.getSharedPreferences("pluralware_account", Context.MODE_PRIVATE)

    override suspend fun get(): String? = prefs.getString(KEY, null)

    override suspend fun set(systemUuid: String?) {
        prefs.edit().apply { if (systemUuid == null) remove(KEY) else putString(KEY, systemUuid) }.apply()
    }

    private companion object {
        const val KEY = "sharing_owner"
    }
}
