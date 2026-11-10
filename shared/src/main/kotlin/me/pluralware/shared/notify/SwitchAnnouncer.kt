package me.pluralware.shared.notify

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.pluralware.shared.model.Switch

/**
 * Tells friends about each switch this app registers
 * (docs/notifications-design.md §7). Wired to the repository's
 * registered-switch hook, so a switch the app merely observes never goes out.
 * The watch and the phone each have one, and each announces only its own
 * switches, so a friend never hears about one twice.
 *
 * Fire and forget: sending runs in a process-wide scope, so leaving the picker
 * doesn't cancel it, and it never holds up the switch itself. Nothing is queued
 * across restarts — Private mode's TTL covers friends who are offline.
 *
 * Friends either device found gone are skipped for a while
 * ([GoneFriends.toSkip]). [onGoneChanged] hears this device's own gone friends
 * whenever they change, for reporting to the other device; it never hears the
 * other's, so nothing echoes.
 */
class SwitchAnnouncer(
    appVersion: String,
    private val onGoneChanged: suspend (Context, Map<String, Long>) -> Unit = { _, _ -> },
) {
    // The handler is the last line of defense: SwitchSharer already turns
    // per-friend errors into outcomes, but nothing here may crash the app.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> Log.w(TAG, "Sharing a switch failed", e) },
    )

    private val sharer by lazy { SwitchSharer.create(appVersion) }

    fun onSwitchRegistered(context: Context, switch: Switch) {
        val appContext = context.applicationContext
        scope.launch {
            val store = EncryptedSharingStore.get(appContext)
            val changed = announce(store, sharer, switch, System.currentTimeMillis())
            if (changed) runCatching { onGoneChanged(appContext, store.goneFlow.value) }
        }
    }

    internal companion object {
        private const val TAG = "SwitchAnnouncer"

        /**
         * Sends [switch] to every friend not recently found gone, here or on the
         * other device, and records who has gone or come back. Records only
         * this device's findings. Returns whether they changed.
         */
        suspend fun announce(
            store: SharingStore,
            sharer: SwitchSharer,
            switch: Switch,
            now: Long,
        ): Boolean {
            val config = store.configFlow.value
            // With the relay on, it tells friends about every switch, this one included.
            if (!config.appSends) return false
            val before = store.goneFlow.value
            val skip = GoneFriends.toSkip(GoneFriends.merged(before, store.peerGoneFlow.value), now)
            val outcomes = sharer.announce(config, switch, skipFriendIds = skip)
            outcomes.forEach { (_, outcome) ->
                if (outcome is SendOutcome.Failed) Log.w(TAG, "Couldn't notify a friend: ${outcome.reason}")
            }
            val gone = outcomes.filterValues { it == SendOutcome.Gone }.keys.mapTo(HashSet()) { it.id }
            // A retried friend who received it is back.
            val back = outcomes.filterValues { it == SendOutcome.Delivered }.keys
                .mapTo(HashSet()) { it.id }.filterTo(HashSet()) { it in before }
            if (gone.isNotEmpty()) store.markGone(gone, now)
            if (back.isNotEmpty()) store.clearGone(back)
            return store.goneFlow.value != before
        }
    }
}
