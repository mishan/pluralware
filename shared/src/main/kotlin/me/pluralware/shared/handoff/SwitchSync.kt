package me.pluralware.shared.handoff

import android.content.Context
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import me.pluralware.shared.api.PluralKitToken
import me.pluralware.shared.model.Switch
import me.pluralware.shared.repository.PluralKitRepository

/**
 * Keeps the phone and the watch on the same switch, in both directions, over
 * the Wearable Data Layer.
 *
 * Whenever an app sees a new current switch, whether it registered the switch
 * or fetched it (one made in Discord, say), it [push]es a [Notice]: which
 * switch, when, and for which system. The other device refreshes from
 * PluralKit when the notice [Notice.differsFrom] what it has. PluralKit stays
 * the source of truth, and no member names pass through or rest in the Data Layer.
 *
 * "Differs", not "is newer": deleting the latest switch, or moving its time,
 * must reach the other device too. The devices still don't echo. Each writes
 * its own DataItem (they're keyed by node) with no timestamp of its own, so
 * writing the same notice twice is a no-op the Data Layer drops, and a device
 * ignores a notice about the switch it already has. A refresh converges both
 * on PluralKit's answer, after which neither has anything new to say.
 */
object SwitchSync {
    const val PATH = "/pluralware/switch"

    private const val KEY_SWITCH = "switch"
    private const val KEY_AT = "at"
    private const val KEY_SYSTEM = "system"

    /** A device's current switch, by uuid and start time, for the system with [tokenFingerprint]. */
    data class Notice(val switchUuid: String, val atEpochMillis: Long, val tokenFingerprint: String) {
        /**
         * Whether this notice is news to a device holding the switch
         * [currentUuid] that started at [currentAtEpochMillis] (both null when
         * it knows of none), signed in with the token fingerprinted [fingerprint].
         * A notice for another system, or for the same switch, isn't.
         */
        fun differsFrom(currentUuid: String?, currentAtEpochMillis: Long?, fingerprint: String): Boolean =
            tokenFingerprint == fingerprint &&
                (switchUuid != currentUuid || atEpochMillis != currentAtEpochMillis)

        fun differsFrom(current: Switch?, fingerprint: String): Boolean =
            differsFrom(current?.uuid, current?.timestamp?.toEpochMilli(), fingerprint)
    }

    fun noticeOf(switch: Switch, token: PluralKitToken) =
        Notice(switch.uuid, switch.timestamp.toEpochMilli(), token.fingerprint)

    /** Shares [switch] as this device's current switch. Throws without Wear OS; callers ignore it. */
    suspend fun push(context: Context, switch: Switch, token: PluralKitToken) {
        val request = PutDataMapRequest.create(PATH).apply {
            write(noticeOf(switch, token), dataMap)
            setUrgent()
        }.asPutDataRequest()
        Wearable.getDataClient(context).putDataItem(request).await()
    }

    internal fun write(notice: Notice, map: DataMap) {
        map.putString(KEY_SWITCH, notice.switchUuid)
        map.putLong(KEY_AT, notice.atEpochMillis)
        map.putString(KEY_SYSTEM, notice.tokenFingerprint)
    }

    fun read(map: DataMap): Notice? {
        val uuid = map.getString(KEY_SWITCH)?.takeIf { it.isNotBlank() } ?: return null
        val system = map.getString(KEY_SYSTEM)?.takeIf { it.isNotBlank() } ?: return null
        val at = map.getLong(KEY_AT, -1L).takeIf { it >= 0 } ?: return null
        return Notice(uuid, at, system)
    }

    /**
     * The other device's notices among changed DataItems, given as their
     * source node and payload, for a listener service. This device's own
     * notices come back to it too and are dropped; if they can't be told
     * apart, [Notice.differsFrom] turns them away, being about the switch
     * this device already has.
     */
    suspend fun fromOtherDevice(context: Context, items: List<Pair<String?, DataMap>>): List<Notice> =
        OtherDevice.payloads(context, items).mapNotNull(::read)

    private val _incoming = MutableSharedFlow<Notice>(extraBufferCapacity = 8)

    /**
     * Notices from the other device, as this process's listener service
     * receives them, for an open app to act on. Not replayed: an app that
     * isn't showing refreshes when it comes back anyway.
     */
    val incoming: SharedFlow<Notice> = _incoming.asSharedFlow()

    fun deliver(notice: Notice) {
        _incoming.tryEmit(notice)
    }

    /**
     * Keeps an open app's [repository] in step with the other device, until
     * canceled: [push]es every new current switch it sees, and refreshes
     * from PluralKit when the other device reports a different one, which
     * reaches the screens through the repository. Run it while the app is
     * showing; on coming back, the current switch is pushed again (a no-op if
     * unchanged) and the screens refresh themselves.
     */
    suspend fun keepInSync(
        repository: PluralKitRepository,
        token: PluralKitToken,
        push: suspend (Switch) -> Unit,
        notices: Flow<Notice> = incoming,
    ) = coroutineScope {
        val fingerprint = token.fingerprint
        launch {
            repository.loadedFronters
                .mapNotNull { it?.switch }
                // The time too: moving a switch keeps its uuid.
                .distinctUntilChangedBy { it.uuid to it.timestamp }
                .collect { switch ->
                    try {
                        push(switch)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        // Best-effort: no watch, or none in reach. The other
                        // device still polls PluralKit.
                    }
                }
        }
        launch {
            notices
                .filter { it.differsFrom(repository.currentFronters.value, fingerprint) }
                .collect { repository.refreshFronters() }
        }
    }
}
