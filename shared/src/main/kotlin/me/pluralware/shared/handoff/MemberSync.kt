package me.pluralware.shared.handoff

import android.content.Context
import com.google.android.gms.wearable.DataMap
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.Wearable
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import me.pluralware.shared.api.PluralKitToken
import me.pluralware.shared.repository.PluralKitRepository

/**
 * Tells the other device that a member was edited, so it drops what it
 * cached about members and fetches again. Carries no names: only which
 * system, and when, so each notice differs from the last.
 */
object MemberSync {
    const val PATH = "/pluralware/members"

    data class Notice(val tokenFingerprint: String, val atEpochMillis: Long)

    /**
     * What one session has told the other device and been told by it, so a
     * sync that stopped (the app went to the background) catches up when it
     * starts again, rather than missing what happened meanwhile.
     */
    class Progress {
        /** The repository's [PluralKitRepository.memberEdits] as last told to the other device. */
        @Volatile internal var toldEdits = 0

        /** The newest notice from the other device acted on, by [Notice.atEpochMillis]. */
        @Volatile internal var handledAt = -1L
    }

    fun noticeOf(token: PluralKitToken, atEpochMillis: Long) = Notice(token.fingerprint, atEpochMillis)

    private const val KEY_SYSTEM = "system"
    private const val KEY_AT = "at"

    /** Tells the other device. Throws without Wear OS; callers ignore it. */
    suspend fun push(context: Context, token: PluralKitToken, atEpochMillis: Long = System.currentTimeMillis()) {
        val request = PutDataMapRequest.create(PATH).apply {
            write(noticeOf(token, atEpochMillis), dataMap)
            setUrgent()
        }.asPutDataRequest()
        Wearable.getDataClient(context).putDataItem(request).await()
    }

    internal fun write(notice: Notice, map: DataMap) {
        map.putString(KEY_SYSTEM, notice.tokenFingerprint)
        map.putLong(KEY_AT, notice.atEpochMillis)
    }

    fun read(map: DataMap): Notice? {
        val system = map.getString(KEY_SYSTEM)?.takeIf { it.isNotBlank() } ?: return null
        val at = map.getLong(KEY_AT, -1L).takeIf { it >= 0 } ?: return null
        return Notice(system, at)
    }

    suspend fun fromOtherDevice(context: Context, items: List<Pair<String?, DataMap>>): List<Notice> =
        OtherDevice.payloads(context, items).mapNotNull(::read)

    private val _incoming = MutableStateFlow<Notice?>(null)

    /**
     * The newest notice this process's listener service received. Kept, not
     * just passed on: an app in the background acts on it when it comes back.
     */
    val incoming: StateFlow<Notice?> = _incoming.asStateFlow()

    fun deliver(notice: Notice) {
        _incoming.value = notice
    }

    /**
     * Keeps an open app's [repository] in step with the other device's member
     * edits, until canceled: [push]es when one of this app's edits may have
     * landed, and drops its member caches and refreshes when the other device
     * reports one for this system.
     */
    suspend fun keepInSync(
        repository: PluralKitRepository,
        token: PluralKitToken,
        push: suspend () -> Unit,
        progress: Progress,
        notices: Flow<Notice?> = incoming,
    ) = coroutineScope {
        launch {
            // Including edits that landed while this was stopped.
            repository.memberEdits.collect { edits ->
                if (edits == progress.toldEdits) return@collect
                try {
                    push()
                    progress.toldEdits = edits
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Best-effort: no watch, or none in reach. It polls PluralKit anyway.
                }
            }
        }
        launch {
            notices.filterNotNull()
                .filter { it.tokenFingerprint == token.fingerprint && it.atEpochMillis > progress.handledAt }
                .collect {
                    progress.handledAt = it.atEpochMillis
                    repository.forgetMembers()
                    repository.refreshFronters()
                }
        }
    }
}
