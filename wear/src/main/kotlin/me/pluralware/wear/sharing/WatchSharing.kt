package me.pluralware.wear.sharing

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.pluralware.shared.handoff.SharingHandoff
import me.pluralware.shared.model.Switch
import me.pluralware.shared.notify.EncryptedSharingStore
import me.pluralware.shared.notify.GoneFriends
import me.pluralware.shared.notify.SendOutcome
import me.pluralware.shared.notify.SwitchSharer
import me.pluralware.wear.BuildConfig

/**
 * Tells friends about each switch this watch registers
 * (docs/notifications-design.md §7). Wired to the repository's
 * registered-switch hook, so a switch the watch merely observes never goes out.
 *
 * Fire and forget: sending runs in a process-wide scope, so leaving the picker
 * doesn't cancel it, and it never holds up the switch itself. Nothing is queued
 * across restarts — Private mode's TTL covers friends who are offline.
 */
object WatchSharing {
    private const val TAG = "WatchSharing"

    // The handler is the last line of defense: SwitchSharer already turns
    // per-friend errors into outcomes, but nothing here may crash the app.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> Log.w(TAG, "Sharing a switch failed", e) },
    )

    private val sharer by lazy { SwitchSharer.create(BuildConfig.VERSION_NAME) }

    fun onSwitchRegistered(context: Context, switch: Switch) {
        val appContext = context.applicationContext
        scope.launch {
            val store = EncryptedSharingStore.get(appContext)
            val config = store.configFlow.value
            if (!config.isSharing) return@launch
            val now = System.currentTimeMillis()
            val before = store.goneFlow.value
            val outcomes = sharer.announce(config, switch, skipFriendIds = GoneFriends.toSkip(before, now))
            outcomes.forEach { (_, outcome) ->
                if (outcome is SendOutcome.Failed) Log.w(TAG, "Couldn't notify a friend: ${outcome.reason}")
            }
            val gone = outcomes.filterValues { it == SendOutcome.Gone }.keys.mapTo(HashSet()) { it.id }
            // A retried friend who received it is back.
            val back = outcomes.filterValues { it == SendOutcome.Delivered }.keys
                .mapTo(HashSet()) { it.id }.filterTo(HashSet()) { it in before }
            if (gone.isNotEmpty()) store.markGone(gone, now)
            if (back.isNotEmpty()) store.clearGone(back)
            if (store.goneFlow.value.keys != before.keys) {
                // Tell the phone, so the sharing screen can show who stopped receiving.
                runCatching { SharingHandoff.pushStatus(appContext, store.goneFlow.value.keys) }
            }
        }
    }
}
