package me.pluralware.shared.notify

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    fun onSwitchRegistered(context: Context, switch: Switch) =
        launch(context) { store -> announce(store, sharer, switch, System.currentTimeMillis()) }

    /** A fix changed who's fronting now; see [correct]. */
    fun onFrontCorrected(context: Context, before: Switch?, front: Switch?) =
        launch(context) { store -> correct(store, sharer, before, front, System.currentTimeMillis()) }

    private fun launch(context: Context, send: suspend (SharingStore) -> Boolean) {
        val appContext = context.applicationContext
        scope.launch {
            val store = EncryptedSharingStore.get(appContext)
            if (send(store)) runCatching { onGoneChanged(appContext, store.goneFlow.value) }
        }
    }

    internal companion object {
        private const val TAG = "SwitchAnnouncer"

        /**
         * Tells friends a fix changed who's fronting from [before] to [front]
         * (null: no switch left), unless what they'd be told is the same.
         */
        suspend fun correct(store: SharingStore, sharer: SwitchSharer, before: Switch?, front: Switch?, now: Long): Boolean {
            val shared = store.configFlow.value.sharedMemberUuids
            if (before != null && SwitchAnnouncement.text(before, shared) == SwitchAnnouncement.textOf(front, shared)) return false
            // Even with the relay on: it only hears of new switches, never fixes.
            return record(store, now) { config, skip -> sharer.announceCorrection(config, front, skip) }
        }

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
            return record(store, now) { config, skip ->
                // With the relay on, it tells friends about every switch, this one included.
                // Read once it's this send's turn: the relay may have taken over meanwhile.
                if (config.appSends) sharer.announce(config, switch, skipFriendIds = skip) else emptyMap()
            }
        }

        // One send at a time, retries included, so a correction can't land before the mistake it corrects.
        private val sending = Mutex()

        private suspend fun record(
            store: SharingStore,
            now: Long,
            send: suspend (SharingConfig, Set<String>) -> Map<Friend, SendOutcome>,
        ): Boolean = sending.withLock {
            val config = store.configFlow.value
            val before = store.goneFlow.value
            val marked = GoneFriends.merged(before, store.peerGoneFlow.value)
            // A Discord webhook that's gone isn't tried again: Discord asks so.
            val skip = GoneFriends.toSkip(marked, now) + config.friends.filter { it is Friend.Discord && it.id in marked }.map { it.id }
            val outcomes = send(config, skip)
            outcomes.forEach { (_, outcome) ->
                if (outcome is SendOutcome.Failed) runCatching { Log.w(TAG, "Couldn't notify a friend: ${outcome.reason}") }
            }
            val gone = outcomes.filterValues { it == SendOutcome.Gone }.keys.mapTo(HashSet()) { it.id }
            // A retried friend who received it is back.
            val back = outcomes.filterValues { it == SendOutcome.Delivered }.keys
                .mapTo(HashSet()) { it.id }.filterTo(HashSet()) { it in before }
            if (gone.isNotEmpty()) store.markGone(gone, now)
            if (back.isNotEmpty()) store.clearGone(back)
            val failed = outcomes.entries.filter { it.value is SendOutcome.Failed }
                .associate { (friend, outcome) -> friend.id to (outcome as SendOutcome.Failed).reason }
            val settled = outcomes.filterValues { it !is SendOutcome.Failed }.keys.mapTo(HashSet()) { it.id }
            if (failed.isNotEmpty() || settled.any { it in store.failuresFlow.value }) store.recordFailures(failed, settled)
            store.goneFlow.value != before
        }
    }
}
