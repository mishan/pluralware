package me.pluralware.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import me.pluralware.shared.handoff.SettingsHandoff
import me.pluralware.shared.handoff.SharingHandoff
import me.pluralware.shared.handoff.TokenHandoff
import me.pluralware.shared.notify.EncryptedSharingStore
import me.pluralware.shared.repository.EncryptedTokenStore
import me.pluralware.shared.settings.LocalSettingsStore
import me.pluralware.wear.complication.FronterSurfaces

/**
 * Receives data the phone pushes over the Wearable Data Layer:
 *  - the PluralKit token ([TokenHandoff.PATH]) — persisted (or cleared, on a
 *    sign-out), then the DataItem is deleted (it's a secret; minimize its
 *    lifetime on disk).
 *  - friend sharing ([SharingHandoff.PATH]) — persisted encrypted, then the
 *    DataItem is deleted, like the token.
 *  - app settings ([SettingsHandoff.PATH]) — persisted and *kept* (latest-wins
 *    state the watch should retain across syncs and reboots).
 *
 * Wear OS binds to this service whenever a relevant DataItem changes. The
 * callback runs on a worker thread, so blocking I/O via [runBlocking] is fine.
 */
class WatchDataListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        // Snapshot into a list before suspending — the buffer is released as
        // soon as this method returns and accessing it after is undefined.
        val changed = events
            .filter { it.type == DataEvent.TYPE_CHANGED }
            .map { it.dataItem.uri to DataMapItem.fromDataItem(it.dataItem) }

        val tokenItems = changed.filter { (uri, _) -> uri.path == TokenHandoff.PATH }
        val settingsItems = changed.filter { (uri, _) -> uri.path == SettingsHandoff.PATH }
        val sharingItems = changed.filter { (uri, _) -> uri.path == SharingHandoff.PATH }
        if (tokenItems.isEmpty() && settingsItems.isEmpty() && sharingItems.isEmpty()) return

        runBlocking {
            if (tokenItems.isNotEmpty()) {
                val store = EncryptedTokenStore.get(applicationContext)
                val dataClient = Wearable.getDataClient(applicationContext)
                tokenItems.forEach { (uri, item) ->
                    when (val message = TokenHandoff.read(item)) {
                        is TokenHandoff.Message.Connect -> store.setToken(message.token)
                        TokenHandoff.Message.SignOut -> {
                            store.clear()
                            // Sharing belongs to the system that just signed out.
                            EncryptedSharingStore.get(applicationContext).clear()
                        }
                        null -> Unit
                    }
                    // Delete even an unreadable item, so nothing token-shaped is
                    // left behind. Best-effort; if it fails the next push replaces it.
                    runCatching { dataClient.deleteDataItems(uri).await() }
                }
                // Paired, re-paired or signed out: the complication and tile
                // must drop the old system's line and ask again.
                FronterSurfaces.onTokenChanged(applicationContext)
            }
            if (sharingItems.isNotEmpty()) {
                // Holds secrets (VAPID key, follow codes): store encrypted, then
                // delete, as for the token. Latest wins.
                val dataClient = Wearable.getDataClient(applicationContext)
                SharingHandoff.read(sharingItems.last().second)?.let {
                    EncryptedSharingStore.get(applicationContext).set(it)
                }
                sharingItems.forEach { (uri, _) -> runCatching { dataClient.deleteDataItems(uri).await() } }
            }
            if (settingsItems.isNotEmpty()) {
                // Latest-wins: apply the most recent settings item; keep the DataItem.
                val (_, item) = settingsItems.last()
                LocalSettingsStore.get(applicationContext)
                    .setRefreshInterval(SettingsHandoff.read(item).refreshInterval)
            }
        }
    }
}
