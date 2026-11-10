package me.pluralware.mobile.watch

import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.runBlocking
import me.pluralware.shared.handoff.SwitchSync

/**
 * Receives the watch's current switch ([SwitchSync.PATH]) and hands it to the
 * app, which refreshes if it's showing. With the app closed there's nothing
 * to update: it fetches when opened.
 */
class PhoneDataListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        // Snapshot before the buffer is released when this returns.
        val changed = events
            .filter { it.type == DataEvent.TYPE_CHANGED && it.dataItem.uri.path == SwitchSync.PATH }
            .map { it.dataItem.uri.host to DataMapItem.fromDataItem(it.dataItem).dataMap }
        if (changed.isEmpty()) return
        // The callback runs on a worker thread, so blocking here is fine.
        runBlocking { SwitchSync.fromOtherDevice(applicationContext, changed) }.forEach(SwitchSync::deliver)
    }
}
