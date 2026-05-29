package me.pluralware.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import me.pluralware.shared.handoff.SettingsHandoff
import me.pluralware.shared.handoff.TokenHandoff
import me.pluralware.shared.repository.EncryptedTokenStore
import me.pluralware.shared.settings.LocalSettingsStore

/**
 * Receives data the phone pushes over the Wearable Data Layer:
 *  - the PluralKit token ([TokenHandoff.PATH]) — persisted, then the DataItem
 *    is deleted (it's a secret; minimise its lifetime on disk).
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
        if (tokenItems.isEmpty() && settingsItems.isEmpty()) return

        runBlocking {
            if (tokenItems.isNotEmpty()) {
                val store = EncryptedTokenStore.get(applicationContext)
                val dataClient = Wearable.getDataClient(applicationContext)
                tokenItems.forEach { (uri, item) ->
                    val token = TokenHandoff.read(item) ?: return@forEach
                    store.setToken(token)
                    // Best-effort delete; if it fails the next push will replace it.
                    runCatching { dataClient.deleteDataItems(uri).await() }
                }
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
