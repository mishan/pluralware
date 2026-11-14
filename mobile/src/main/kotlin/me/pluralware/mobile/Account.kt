package me.pluralware.mobile

import android.content.Context
import android.util.Log
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.pluralware.shared.api.PluralKitToken
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
    /** Held for every write to the relay, so none lands after it's cleared. */
    private val relayWrites: Mutex = Mutex(),
) {
    private val _signingOut = MutableStateFlow(false)
    val signingOut: StateFlow<Boolean> = _signingOut.asStateFlow()

    // A connection made while a sign-out is still cleaning up would have its
    // sharing and owner wiped by that cleanup.
    private val lifecycle = Mutex()

    /** Called with the system a token was just validated for, before it's saved. */
    suspend fun connected(system: SystemInfo) = lifecycle.withLock {
        val previous = owner.get()
        if (previous != system.uuid) {
            // Another system's friends (or unowned ones, which may be another
            // system's) must not hear about this one's switches.
            check(forgetSharing()) { "Couldn't clear the last system's sharing. Try again." }
            // The watch may still have it: the new token replaces a sign-out
            // it hasn't received yet.
            step("clear the watch's sharing") { pushSharingToWatch(SharingConfig()) }
        }
        owner.set(system.uuid)
        // A watch signed out since has dropped its copy; give it back.
        val config = sharingStore.configFlow.value
        if (config.isSharing) step("send sharing to the watch") { pushSharingToWatch(config.forWatch()) }
    }

    /**
     * Sharing saved before its owner was recorded (0.1.0) belongs to the system
     * of the token it was saved under: [lookUp] asks PluralKit which that is.
     * If it can't say, the sharing stays unowned, and [connected] drops it.
     */
    suspend fun claimUnowned(lookUp: suspend (PluralKitToken) -> SystemInfo) {
        if (owner.get() != null || !sharingStore.configFlow.value.isSharing) return
        val token = tokenStore.getToken() ?: return
        val system = try {
            lookUp(token)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "Couldn't look up whose sharing this is", e) }
            return
        }
        // Not held during the lookup, so a slow network can't hold up connecting.
        lifecycle.withLock {
            if (owner.get() == null && tokenStore.getToken() == token) owner.set(system.uuid)
        }
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
                lifecycle.withLock {
                    // Still signed in, so leave the rest for Disconnect to try again.
                    if (!step("clear the token") { tokenStore.clear() }) return@withLock
                    // Best-effort: with no watch in reach, the Data Layer delivers
                    // it on the next sync.
                    step("sign the watch out") { signOutWatch() }
                    if (!keepSharing) {
                        forgetSharing()
                        step("forget the owner") { owner.set(null) }
                    }
                }
            } finally {
                _signingOut.value = false
            }
        }
    }

    /** False if sharing couldn't be cleared. */
    private suspend fun forgetSharing(): Boolean = relayWrites.withLock {
        // The relay, if any, is told to forget its copy. Unreachable: keep its
        // admin details (orphans survive clear()) so the sharing screen can
        // delete from it later.
        sharingStore.configFlow.value.relay?.let { relay ->
            val cleared = try {
                clearRelay(relay)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                runCatching { Log.w(TAG, "Couldn't clear the relay", e) }
                false
            }
            if (!cleared) step("remember the relay") { sharingStore.addOrphan(relay) }
        }
        step("clear sharing") { sharingStore.clear() }
    }

    private suspend fun step(what: String, block: suspend () -> Unit): Boolean =
        try {
            block()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { Log.w(TAG, "Couldn't $what", e) }
            false
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
