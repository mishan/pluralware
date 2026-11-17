package me.pluralware.wear

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.tasks.await
import me.pluralware.shared.handoff.SettingsHandoff
import me.pluralware.shared.handoff.SharingHandoff
import me.pluralware.shared.handoff.MemberSync
import me.pluralware.shared.handoff.SwitchSync
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
 *  - friends the phone found gone ([SharingHandoff.STATUS_PATH]) — stored
 *    apart from the watch's own, so its sends skip them too. Kept.
 *  - the phone's current switch ([SwitchSync.PATH]) — handed to the open app,
 *    if any, and used to refresh the complication and tile. Kept: it's the
 *    phone's latest state, and holds no names or secrets.
 *  - a member edited on the phone ([MemberSync.PATH]) — the same: the open
 *    app drops its member caches, and the complication and tile refresh.
 *
 * Wear OS binds to this service whenever a relevant DataItem changes;
 * [catchUpWithPhone] covers items written before the app was installed.
 */
class WatchDataListenerService : WearableListenerService() {

    override fun onDataChanged(events: DataEventBuffer) {
        // Snapshot into a list before suspending — the buffer is released as
        // soon as this method returns and accessing it after is undefined.
        val changed = events
            .filter { it.type == DataEvent.TYPE_CHANGED }
            .map { it.dataItem.uri to DataMapItem.fromDataItem(it.dataItem) }

        // The callback runs on a worker thread, so blocking is fine.
        runBlocking { receiveFromPhone(applicationContext, changed) }
    }
}

/** Acts on what the phone wrote: see [WatchDataListenerService]. */
internal suspend fun receiveFromPhone(context: Context, changed: List<Pair<Uri, DataMapItem>>) {
    val tokenItems = changed.filter { (uri, _) -> uri.path == TokenHandoff.PATH }
    val settingsItems = changed.filter { (uri, _) -> uri.path == SettingsHandoff.PATH }
    val sharingItems = changed.filter { (uri, _) -> uri.path == SharingHandoff.PATH }
    val statusItems = changed.filter { (uri, _) -> uri.path == SharingHandoff.STATUS_PATH }
    val switchItems = changed.filter { (uri, _) -> uri.path == SwitchSync.PATH }
    val memberItems = changed.filter { (uri, _) -> uri.path == MemberSync.PATH }
    if (listOf(tokenItems, settingsItems, sharingItems, statusItems, switchItems, memberItems).all { it.isEmpty() }) return

    if (tokenItems.isNotEmpty()) {
        val store = EncryptedTokenStore.get(context)
        val dataClient = Wearable.getDataClient(context)
        tokenItems.forEach { (uri, item) ->
            when (val message = TokenHandoff.read(item)) {
                is TokenHandoff.Message.Connect -> store.setToken(message.token)
                TokenHandoff.Message.SignOut -> {
                    store.clear()
                    // Sharing belongs to the system that just signed out.
                    EncryptedSharingStore.get(context).clear()
                }
                null -> Unit
            }
            // Delete even an unreadable item, so nothing token-shaped is
            // left behind. Best-effort; if it fails the next push replaces it.
            runCatching { dataClient.deleteDataItems(uri).await() }
        }
        // Paired, re-paired or signed out: the complication and tile
        // must drop the old system's line and ask again.
        FronterSurfaces.onTokenChanged(context)
    }
    if (sharingItems.isNotEmpty()) {
        // Holds secrets (VAPID key, follow codes): store encrypted, then
        // delete, as for the token. Latest wins.
        val dataClient = Wearable.getDataClient(context)
        SharingHandoff.read(sharingItems.last().second)?.let {
            EncryptedSharingStore.get(context).set(it)
        }
        sharingItems.forEach { (uri, _) -> runCatching { dataClient.deleteDataItems(uri).await() } }
    }
    if (statusItems.isNotEmpty()) {
        // After the config above, so marks for friends it removes are dropped.
        SharingHandoff.receiveStatus(context, statusItems.map { (uri, item) -> uri.host to item.dataMap })
    }
    if (settingsItems.isNotEmpty()) {
        // Latest-wins: apply the most recent settings item; keep the DataItem.
        val (_, item) = settingsItems.last()
        LocalSettingsStore.get(context)
            .setRefreshInterval(SettingsHandoff.read(item).refreshInterval)
    }
    // Last, each on its own: they read encrypted stores, and a throw in
    // one mustn't cost the items before it or the other, which the
    // Data Layer won't deliver again.
    if (switchItems.isNotEmpty()) {
        val items = switchItems.map { (uri, item) -> uri.host to item.dataMap }
        runCatching {
            SwitchSync.fromOtherDevice(context, items).forEach { notice ->
                SwitchSync.deliver(notice)
                FronterSurfaces.onRemoteSwitch(context, notice)
            }
        }
    }
    if (memberItems.isNotEmpty()) {
        val items = memberItems.map { (uri, item) -> uri.host to item.dataMap }
        runCatching {
            MemberSync.fromOtherDevice(context, items).forEach { notice ->
                MemberSync.deliver(notice)
                FronterSurfaces.onRemoteMemberEdit(context, notice)
            }
        }
    }
}

/**
 * Acts on a token, sharing or settings the phone wrote before this app could
 * hear of it: a watch app installed after the phone connected sees no change
 * for an item already on the watch.
 */
suspend fun catchUpWithPhone(context: Context) {
    val dataClient = Wearable.getDataClient(context)
    val items = listOf(TokenHandoff.PATH, SharingHandoff.PATH, SettingsHandoff.PATH).flatMap { path ->
        // Each on its own, so one failed query doesn't cost the others; the next launch asks again.
        try {
            val buffer = dataClient.getDataItems(Uri.Builder().scheme("wear").path(path).build()).await()
            try {
                buffer.map { it.uri to DataMapItem.fromDataItem(it) }
            } finally {
                buffer.release()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    }
    if (items.isNotEmpty()) receiveFromPhone(context, items)
}
