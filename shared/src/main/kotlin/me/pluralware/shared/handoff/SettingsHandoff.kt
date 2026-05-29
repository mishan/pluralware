package me.pluralware.shared.handoff

import android.content.Context
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import me.pluralware.shared.settings.AppSettings
import me.pluralware.shared.settings.RefreshInterval

/**
 * Phone→watch transport for [AppSettings] over the Wearable Data Layer.
 *
 * Mirrors [TokenHandoff], with one deliberate difference: the watch *keeps* the
 * settings DataItem instead of deleting it after reading. Settings are
 * latest-wins state the watch should retain, not a one-shot secret — and a
 * newly-paired watch picks up the current settings from the synced DataItem.
 */
object SettingsHandoff {
    /** Path the phone writes to and the watch listens on. */
    const val PATH = "/pluralware/settings"

    private const val KEY_POLL_SECONDS = "poll_seconds"

    /** Push the current settings to all paired wear nodes. */
    suspend fun push(context: Context, settings: AppSettings) {
        val request = PutDataMapRequest.create(PATH).apply {
            dataMap.putInt(KEY_POLL_SECONDS, settings.refreshInterval.seconds)
            setUrgent()
        }.asPutDataRequest()
        Wearable.getDataClient(context).putDataItem(request).await()
    }

    /** Pull settings out of a DataItem payload, falling back to defaults. */
    fun read(item: DataMapItem): AppSettings {
        val seconds = item.dataMap.getInt(KEY_POLL_SECONDS, RefreshInterval.DEFAULT.seconds)
        return AppSettings(refreshInterval = RefreshInterval.fromSeconds(seconds))
    }
}
