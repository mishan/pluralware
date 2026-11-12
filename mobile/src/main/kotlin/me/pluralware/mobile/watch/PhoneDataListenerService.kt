package me.pluralware.mobile.watch

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking
import me.pluralware.shared.handoff.SharingHandoff
import me.pluralware.shared.handoff.SwitchSync

/**
 * Receives what the watch shares over the Wearable Data Layer:
 *  - its current switch ([SwitchSync.PATH]), handed to the app, which
 *    refreshes if it's showing. With the app closed there's nothing to
 *    update: it fetches when opened.
 *  - friends it found gone ([SharingHandoff.STATUS_PATH]), stored apart from
 *    the phone's own, so the phone's sends skip them too and the sharing
 *    screen can say who stopped receiving.
 */
class PhoneDataListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        // Snapshot before the buffer is released when this returns.
        val changed = events
            .filter { it.type == DataEvent.TYPE_CHANGED }
            .map { Triple(it.dataItem.uri.path, it.dataItem.uri.host, DataMapItem.fromDataItem(it.dataItem).dataMap) }
        fun on(path: String) = changed.filter { it.first == path }.map { it.second to it.third }
        val switches = on(SwitchSync.PATH)
        val status = on(SharingHandoff.STATUS_PATH)
        if (switches.isEmpty() && status.isEmpty()) return
        // The callback runs on a worker thread, so blocking here is fine.
        runBlocking {
            if (status.isNotEmpty()) SharingHandoff.receiveStatus(applicationContext, status)
            if (switches.isNotEmpty()) SwitchSync.fromOtherDevice(applicationContext, switches).forEach(SwitchSync::deliver)
        }
    }
}
