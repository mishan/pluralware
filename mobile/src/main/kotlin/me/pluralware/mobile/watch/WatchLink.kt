package me.pluralware.mobile.watch

import android.content.Context
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.wearable.Wearable
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import me.pluralware.shared.api.PluralKitToken
import me.pluralware.shared.handoff.SettingsHandoff
import me.pluralware.shared.handoff.SwitchSync
import me.pluralware.shared.handoff.TokenHandoff
import me.pluralware.shared.model.Switch
import me.pluralware.shared.settings.SettingsStore

/** What the phone knows about a watch, for the settings screen. */
sealed interface WatchState {
    data object Checking : WatchState

    /** This phone has no Wear OS support at all. The app doesn't mention watches. */
    data object Unsupported : WatchState

    /**
     * Wear OS is here, but no watch is connected right now. [queued] means the
     * token was handed to the Data Layer anyway, which delivers it to a paired
     * watch when it reconnects.
     */
    data class NotConnected(val queued: Boolean = false) : WatchState

    /** A watch is connected; nothing has been sent to it this run. */
    data class Connected(val watchName: String) : WatchState

    data object Sending : WatchState
    data class Sent(val watchName: String) : WatchState
    data class Failed(val message: String) : WatchState
}

/**
 * The phone's link to a watch, if it has one. The watch is optional: on a
 * phone without Wear OS every call here comes to [WatchState.Unsupported]
 * quietly, and nothing else in the app waits on it.
 *
 * Process-wide, and its sends run in [scope] rather than a screen's: signing
 * in swaps the whole UI out from under the screen that started the send.
 */
class WatchLink(
    private val scope: CoroutineScope,
    /** Names of the watches connected now; throws [ApiException] without Wear OS. */
    private val connectedWatches: suspend () -> List<String>,
    private val pushToken: suspend (PluralKitToken) -> Unit,
    private val pushSettings: suspend () -> Unit,
    private val pushSignOut: suspend () -> Unit,
    private val pushSwitch: suspend (Switch, PluralKitToken) -> Unit = { _, _ -> },
) {
    private val _state = MutableStateFlow<WatchState>(WatchState.Checking)
    val state: StateFlow<WatchState> = _state.asStateFlow()

    // A send waits to find the watch before writing its token. A sign-out in
    // the meantime counts here, under the same lock as the writes, so the
    // send's token can't land after the sign-out and undo it.
    private val writes = Mutex()
    @Volatile private var signOuts = 0

    // Whatever a send comes to is newer than what a check that began before it finds.
    @Volatile private var sends = 0

    /** Looks for a watch again, unless a send is underway or has reported. */
    fun check() {
        val sendsBefore = sends
        scope.launch {
            val current = _state.value
            if (current is WatchState.Sending || current is WatchState.Sent) return@launch
            val found = find()
            if (sends != sendsBefore) return@launch
            _state.value = when (found) {
                Found.Unsupported -> WatchState.Unsupported
                is Found.Failed -> WatchState.Failed(found.message)
                is Found.Watches -> found.names.firstOrNull()
                    ?.let { WatchState.Connected(it) }
                    ?: WatchState.NotConnected()
            }
        }
    }

    /** Sends [token], and the current settings, to the watch if there is one. */
    fun send(token: PluralKitToken) {
        val signOutsBefore = signOuts
        sends++
        scope.launch {
            _state.value = WatchState.Sending
            val names = when (val found = find()) {
                Found.Unsupported -> {
                    _state.value = WatchState.Unsupported
                    return@launch
                }
                is Found.Failed -> {
                    _state.value = WatchState.Failed(found.message)
                    return@launch
                }
                is Found.Watches -> found.names
            }
            _state.value = try {
                writes.withLock {
                    if (signOuts != signOutsBefore) return@launch
                    // Settings first, so a freshly paired watch starts in sync.
                    bestEffort { pushSettings() }
                    pushToken(token)
                }
                names.firstOrNull()?.let { WatchState.Sent(it) } ?: WatchState.NotConnected(queued = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                WatchState.Failed(e.message?.takeIf { it.isNotBlank() } ?: "Couldn't reach your watch.")
            }
        }
    }

    /**
     * Tells a watch about the phone's current switch ([SwitchSync]). Tries
     * once on a phone without Wear OS, then stays quiet. Throws otherwise, for
     * [SwitchSync.keepInSync] to ignore.
     */
    suspend fun shareSwitch(switch: Switch, token: PluralKitToken) {
        if (_state.value == WatchState.Unsupported) return
        try {
            pushSwitch(switch, token)
        } catch (e: Exception) {
            if (e !is CancellationException && e.isWearUnsupported()) {
                _state.value = WatchState.Unsupported
                return
            }
            throw e
        }
    }

    /** Pushes changed settings; best-effort, and silent without a watch. */
    fun settingsChanged() {
        scope.launch { runCatching { pushSettings() } }
    }

    /**
     * Signs the watch out too. Best-effort: with no watch in reach the Data
     * Layer holds the write and delivers it on the next sync.
     */
    suspend fun signOut() {
        writes.withLock {
            signOuts++
            bestEffort { pushSignOut() }
        }
        _state.value = WatchState.Checking
    }

    // Unlike runCatching, lets a cancelled send or sign-out stop here.
    private suspend fun bestEffort(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Unit
        }
    }

    private sealed interface Found {
        data object Unsupported : Found
        data class Failed(val message: String) : Found
        data class Watches(val names: List<String>) : Found
    }

    private suspend fun find(): Found = try {
        Found.Watches(connectedWatches())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (e.isWearUnsupported()) Found.Unsupported
        else Found.Failed(e.message?.takeIf { it.isNotBlank() } ?: "Couldn't look for a watch.")
    }

    companion object {
        fun create(context: Context, settingsStore: SettingsStore, scope: CoroutineScope): WatchLink {
            val appContext = context.applicationContext
            return WatchLink(
                scope = scope,
                connectedWatches = {
                    Wearable.getNodeClient(appContext).connectedNodes.await().map { it.displayName }
                },
                pushToken = { TokenHandoff.push(appContext, it) },
                pushSettings = { SettingsHandoff.push(appContext, settingsStore.getSettings()) },
                pushSignOut = { TokenHandoff.pushSignOut(appContext) },
                pushSwitch = { switch, token -> SwitchSync.push(appContext, switch, token) },
            )
        }
    }
}

/**
 * Phones without the Wear OS app (and emulators without a watch image)
 * answer API_NOT_CONNECTED, "Wearable.API is not available on this device".
 */
internal fun Throwable.isWearUnsupported(): Boolean =
    this is ApiException &&
        (statusCode == CommonStatusCodes.API_NOT_CONNECTED ||
            message.orEmpty().contains("not available", ignoreCase = true))
