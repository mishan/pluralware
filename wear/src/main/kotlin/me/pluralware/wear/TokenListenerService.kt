package me.pluralware.wear

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import me.pluralware.shared.handoff.TokenHandoff
import me.pluralware.shared.repository.EncryptedTokenStore

/**
 * Receives the PluralKit token that the phone wrote via [TokenHandoff].
 *
 * Lifecycle: Wear OS hosts the service in a separate process (or this process,
 * depending on packaging) — Google Play Services binds to it whenever a
 * relevant DataItem changes. The callback runs on a worker thread, so blocking
 * I/O via [runBlocking] is fine.
 *
 * After persisting the token we delete the DataItem. The deletion propagates
 * back to the phone, so the cleartext copy on the phone's local data store is
 * also cleared once the watch acks. If the watch isn't around at write time,
 * the DataItem sits in the cloud-relay until the watch comes online and we
 * process it then.
 */
class TokenListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        // Snapshot the events into a list before suspending — DataEventBuffer
        // is released as soon as this method returns and accessing it after is
        // undefined behaviour.
        val tokenEvents = events
            .filter { it.type == DataEvent.TYPE_CHANGED }
            .filter { it.dataItem.uri.path == TokenHandoff.PATH }
            .mapNotNull { event ->
                val item = DataMapItem.fromDataItem(event.dataItem)
                val token = TokenHandoff.read(item) ?: return@mapNotNull null
                token to event.dataItem.uri
            }
        if (tokenEvents.isEmpty()) return

        val store = EncryptedTokenStore.get(applicationContext)
        val dataClient = Wearable.getDataClient(applicationContext)

        runBlocking {
            tokenEvents.forEach { (token, uri) ->
                store.setToken(token)
                // Best-effort delete; if it fails the next push will replace it.
                runCatching { dataClient.deleteDataItems(uri).await() }
            }
        }
    }
}
