package me.pluralware.wear.sharing

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.pluralware.shared.handoff.SharingHandoff
import me.pluralware.shared.model.Switch
import me.pluralware.shared.notify.EncryptedSharingStore
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val sharer by lazy { SwitchSharer.create(BuildConfig.VERSION_NAME) }

    fun onSwitchRegistered(context: Context, switch: Switch) {
        val appContext = context.applicationContext
        scope.launch {
            val store = EncryptedSharingStore.get(appContext)
            val config = store.configFlow.value
            if (!config.isSharing) return@launch
            val outcomes = sharer.announce(config, switch, skipFriendIds = store.goneFlow.value)
            outcomes.forEach { (friend, outcome) ->
                if (outcome is SendOutcome.Failed) Log.w(TAG, "Couldn't notify a friend: ${outcome.reason}")
            }
            val gone = outcomes.filterValues { it == SendOutcome.Gone }.keys.mapTo(HashSet()) { it.id }
            if (gone.isNotEmpty()) {
                store.markGone(gone)
                // Tell the phone, so the sharing screen can show who stopped receiving.
                runCatching { SharingHandoff.pushStatus(appContext, store.goneFlow.value) }
            }
        }
    }
}
