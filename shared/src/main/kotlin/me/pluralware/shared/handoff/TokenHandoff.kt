package me.pluralware.shared.handoff

import android.content.Context
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import me.pluralware.shared.api.PluralKitToken

/**
 * Phone→watch token transport over the Wearable Data Layer.
 *
 * The Data Layer authenticates and encrypts traffic between paired devices,
 * and stores the DataItem in the on-device data store on both sides until
 * explicitly deleted. After the watch reads the item it deletes it (see
 * `WatchDataListenerService` in `:wear`) — the deletion propagates back to the
 * phone, so the cleartext-on-disk window is bounded by the next sync round-trip.
 *
 * Signing out travels the same path: [pushSignOut] overwrites the item with a
 * sign-out marker. A DataItem is keyed by its source node and path, so that
 * write also replaces any token the watch hasn't picked up yet — a stale
 * token can't arrive after the user disconnected, and it doesn't linger on
 * the phone when no watch ever collects it.
 *
 * DataClient is chosen over MessageClient because it survives the watch
 * being asleep or out of range at send time: the phone writes once, the
 * watch picks it up on its next sync.
 */
object TokenHandoff {
    /** Path the phone writes to and the watch listens on. */
    const val PATH = "/pluralware/token"

    private const val KEY_TOKEN = "token"
    private const val KEY_SIGNED_OUT = "signed_out"
    private const val KEY_TIMESTAMP = "ts"

    /** What a token DataItem asks the watch to do. */
    sealed interface Message {
        data class Connect(val token: PluralKitToken) : Message
        data object SignOut : Message
    }

    /**
     * Push the token to all paired wear nodes. Suspends until the Data Layer
     * has accepted the write locally; sync to the watch happens asynchronously.
     */
    suspend fun push(context: Context, token: PluralKitToken) = put(context) {
        putString(KEY_TOKEN, token.raw)
    }

    /** Tell the watch to forget its token, superseding any undelivered [push]. */
    suspend fun pushSignOut(context: Context) = put(context) {
        putBoolean(KEY_SIGNED_OUT, true)
    }

    private suspend fun put(context: Context, fill: DataMap.() -> Unit) {
        val request = PutDataMapRequest.create(PATH).apply {
            dataMap.fill()
            // The Data Layer dedupes on payload hash. Writing the same token a
            // second time would otherwise be a no-op (no onDataChanged on the
            // watch). The timestamp forces a fresh hash on every write.
            dataMap.putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            setUrgent()
        }.asPutDataRequest()
        Wearable.getDataClient(context).putDataItem(request).await()
    }

    /** Decode a DataItem payload. Returns null if it carries neither a token nor a sign-out. */
    fun read(item: DataMapItem): Message? = read(item.dataMap)

    internal fun read(map: DataMap): Message? {
        if (map.getBoolean(KEY_SIGNED_OUT, false)) return Message.SignOut
        val raw = map.getString(KEY_TOKEN)
        if (raw.isNullOrBlank()) return null
        return Message.Connect(PluralKitToken(raw))
    }
}
