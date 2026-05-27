package me.pluralware.shared.handoff

import android.content.Context
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
 * explicitly deleted. After the watch reads the token it deletes the DataItem
 * (see [TokenListenerService]) — the deletion propagates back to the phone,
 * so the cleartext-on-disk window is bounded by the next sync round-trip.
 *
 * DataClient is chosen over MessageClient because it survives the watch
 * being asleep or out of range at send time: the phone writes once, the
 * watch picks it up on its next sync.
 */
object TokenHandoff {
    /** Path the phone writes to and the watch listens on. */
    const val PATH = "/pluralware/token"

    private const val KEY_TOKEN = "token"
    private const val KEY_TIMESTAMP = "ts"

    /**
     * Push the token to all paired wear nodes. Suspends until the Data Layer
     * has accepted the write locally; sync to the watch happens asynchronously.
     */
    suspend fun push(context: Context, token: PluralKitToken) {
        val request = PutDataMapRequest.create(PATH).apply {
            dataMap.putString(KEY_TOKEN, token.raw)
            // The Data Layer dedupes on payload hash. Writing the same token a
            // second time would otherwise be a no-op (no onDataChanged on the
            // watch). The timestamp forces a fresh hash on every push.
            dataMap.putLong(KEY_TIMESTAMP, System.currentTimeMillis())
            setUrgent()
        }.asPutDataRequest()
        Wearable.getDataClient(context).putDataItem(request).await()
    }

    /** Pull the token out of a DataItem payload. Returns null if absent/blank. */
    fun read(item: DataMapItem): PluralKitToken? {
        val raw = item.dataMap.getString(KEY_TOKEN) ?: return null
        if (raw.isBlank()) return null
        return PluralKitToken(raw)
    }
}
