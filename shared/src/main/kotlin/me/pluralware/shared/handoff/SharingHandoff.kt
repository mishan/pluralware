package me.pluralware.shared.handoff

import android.content.Context
import android.net.Uri
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import me.pluralware.shared.notify.EncryptedSharingStore
import me.pluralware.shared.notify.GoneMarks
import me.pluralware.shared.notify.SharingConfig

/**
 * Friend sharing over the Wearable Data Layer (docs/notifications-design.md §8).
 *
 * Phone → watch, on [PATH]: the whole [SharingConfig], as the token travels in
 * [TokenHandoff]. It holds secrets, so the watch stores it encrypted and
 * deletes the DataItem, bounding how long it sits in the Data Layer's store.
 *
 * Both ways, on [STATUS_PATH]: which friends' subscriptions this device found
 * gone, and when. Each device writes its own DataItem with only its own
 * findings; the other stores them apart from its own
 * ([me.pluralware.shared.notify.SharingStore.peerGoneFlow]), skips those
 * friends too, and shows them on the phone's sharing screen. Not secret; kept.
 */
object SharingHandoff {
    const val PATH = "/pluralware/sharing"
    const val STATUS_PATH = "/pluralware/sharing-status"

    private const val KEY_CONFIG = "config"
    // Friend ids alone, as older versions wrote and read them.
    private const val KEY_GONE = "gone"
    // The same as "id@millis" marks, so the other device knows when to retry.
    private const val KEY_GONE_MARKS = "gone_marks"
    private const val KEY_TIMESTAMP = "ts"

    suspend fun push(context: Context, config: SharingConfig) = put(context, PATH) {
        putString(KEY_CONFIG, config.toJson())
    }

    fun read(item: DataMapItem): SharingConfig? = read(item.dataMap)

    internal fun read(map: DataMap): SharingConfig? =
        map.getString(KEY_CONFIG)?.let { runCatching { SharingConfig.fromJson(it) }.getOrNull() }

    /** Reports this device's own gone friends to the other device. */
    suspend fun pushStatus(context: Context, gone: Map<String, Long>) = put(context, STATUS_PATH) {
        writeStatus(gone, this)
    }

    internal fun writeStatus(gone: Map<String, Long>, map: DataMap) {
        map.putStringArrayList(KEY_GONE, ArrayList(gone.keys))
        map.putStringArrayList(KEY_GONE_MARKS, ArrayList(GoneMarks.encode(gone)))
    }

    /**
     * The other device's gone friends from a status payload. An older version
     * sent ids alone; those count as found gone at [receivedAtEpochMillis].
     */
    fun readStatus(map: DataMap, receivedAtEpochMillis: Long): Map<String, Long> {
        map.getStringArrayList(KEY_GONE_MARKS)?.let { return GoneMarks.decode(it) }
        return map.getStringArrayList(KEY_GONE).orEmpty().associateWith { receivedAtEpochMillis }
    }

    /**
     * For a listener service: stores the gone friends the other device just
     * reported, from changed [STATUS_PATH] items (each with its source node),
     * as that device's marks. This device's own report is ignored.
     */
    suspend fun receiveStatus(context: Context, items: List<Pair<String?, DataMap>>) {
        // Taking its own report as the other's would replace the other's marks.
        val reports = OtherDevice.knownPayloads(context, items)
        if (reports.isEmpty()) return
        val now = System.currentTimeMillis()
        EncryptedSharingStore.get(context).setPeerGone(readStatus(reports.last(), now))
    }

    /** The gone friend ids any device has reported, this one's included. */
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
