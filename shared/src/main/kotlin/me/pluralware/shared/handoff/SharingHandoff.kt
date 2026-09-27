package me.pluralware.shared.handoff

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import me.pluralware.shared.notify.SharingConfig

/**
 * Friend sharing over the Wearable Data Layer (docs/notifications-design.md §8).
 *
 * Phone → watch, on [PATH]: the whole [SharingConfig], as the token travels in
 * [TokenHandoff]. It holds secrets, so the watch stores it encrypted and
 * deletes the DataItem, bounding how long it sits in the Data Layer's store.
 *
 * Watch → phone, on [STATUS_PATH]: which friends' subscriptions have gone away,
 * so the sharing screen can say who stopped receiving. Not secret; kept.
 */
object SharingHandoff {
    const val PATH = "/pluralware/sharing"
    const val STATUS_PATH = "/pluralware/sharing-status"

    private const val KEY_CONFIG = "config"
    private const val KEY_GONE = "gone"
    private const val KEY_TIMESTAMP = "ts"

    suspend fun push(context: Context, config: SharingConfig) = put(context, PATH) {
        putString(KEY_CONFIG, config.toJson())
    }

    fun read(item: DataMapItem): SharingConfig? = read(item.dataMap)

    internal fun read(map: DataMap): SharingConfig? =
        map.getString(KEY_CONFIG)?.let { runCatching { SharingConfig.fromJson(it) }.getOrNull() }

    suspend fun pushStatus(context: Context, goneFriendIds: Set<String>) = put(context, STATUS_PATH) {
        putStringArrayList(KEY_GONE, ArrayList(goneFriendIds))
    }

    /** The gone friends any watch has reported. */
    suspend fun readStatus(context: Context): Set<String> {
        val items = Wearable.getDataClient(context)
            .getDataItems(Uri.Builder().scheme("wear").path(STATUS_PATH).build())
            .await()
        return try {
            items.flatMap { DataMapItem.fromDataItem(it).dataMap.getStringArrayList(KEY_GONE).orEmpty() }.toSet()
        } finally {
            items.release()
        }
    }

    private suspend fun put(context: Context, path: String, fill: DataMap.() -> Unit) {
        val request = PutDataMapRequest.create(path).apply {
            dataMap.fill()
            // Same reason as TokenHandoff: a fresh hash, so a repeat still arrives.
            dataMap.putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            setUrgent()
        }.asPutDataRequest()
        Wearable.getDataClient(context).putDataItem(request).await()
    }
}
