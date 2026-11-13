package me.pluralware.shared.repository

import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.pluralware.shared.api.PluralKitClient
import me.pluralware.shared.api.PluralKitHttpException
import me.pluralware.shared.model.Member
import me.pluralware.shared.model.MemberEdit
import me.pluralware.shared.model.MemberField
import me.pluralware.shared.model.Switch
import me.pluralware.shared.model.SystemInfo
import java.io.IOException
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * UI-facing wrapper around a [PluralKitClient].
 *
 * Responsibilities:
 *  - Convert exceptions into [PkResult] for predictable UI handling.
 *  - Cache the member list (members change rarely; cheap win).
 *  - Expose current-fronter state as a [StateFlow] so multiple surfaces
 *    (main screen, tile, complication) can observe a single source of truth.
 *
 * Intentionally NOT a singleton in code — wired through DI in the apps so tests
 * can inject [MockPluralKitClient] freely.
 */
class PluralKitRepository(
    private val client: PluralKitClient,
    /**
     * Called when an edit changed who's fronting now, with the corrected
     * current switch: null when no switches are left. Decided from what
     * PluralKit returned after the edit, never from a list the screen showed.
     * Must not block; launch and return.
     */
    private val onFrontCorrected: (Switch?) -> Unit = {},
    /**
     * Called with each switch this repository registers — never with ones it
     * merely observes on a refresh. Friend sharing hangs off it: telling
     * friends about a switch the watch only noticed would announce old news
     * (docs/notifications-design.md §7). Must not block; launch and return.
     */
    private val onSwitchRegistered: (Switch) -> Unit = {},
) {
    private val membersCacheMutex = Mutex()
    private var membersCache: List<Member>? = null

    private val _currentFronters = MutableStateFlow<Switch?>(null)
    val currentFronters: StateFlow<Switch?> = _currentFronters.asStateFlow()

    private val _loadedFronters = MutableStateFlow<LoadedFronters?>(null)

    /**
     * [currentFronters] with "not loaded yet" told apart from "no switches":
     * null until the first successful fetch or registration, then
     * [LoadedFronters], whose switch is null for a system that has never
     * switched (PluralKit's 204).
     */
    val loadedFronters: StateFlow<LoadedFronters?> = _loadedFronters.asStateFlow()

    // Counts writes that succeeded. A fetch that was in flight while one did
    // may have read PluralKit from before it, so it doesn't replace what the
    // write's own fetch shows.
    private val writes = AtomicInteger()

    suspend fun refreshMembers(force: Boolean = false): PkResult<List<Member>> = runCatchingPk {
        if (!force) membersCacheMutex.withLock { membersCache }?.let { return@runCatchingPk it }
        val writesBefore = writes.get()
        client.getOwnMembers().also { members ->
            membersCacheMutex.withLock { if (writes.get() == writesBefore) membersCache = members }
        }
    }

    /** The system this token belongs to. Not cached: it's for showing who's signed in. */
    suspend fun ownSystem(): PkResult<SystemInfo> = runCatchingPk { client.getOwnSystem() }

    suspend fun refreshFronters(): PkResult<Switch?> = runCatchingPk { fetchFronters() }

    private suspend fun fetchFronters(): Switch? {
        var attempt = 0
        while (true) {
            val writesBefore = writes.get()
            val switch = client.getCurrentFronters()
            if (writes.get() == writesBefore) return switch.also(::setFronters)
            if (++attempt == FETCH_ATTEMPTS) {
                // Writes keep landing. Their own fetches came after this one: answer with theirs.
                val loaded = _loadedFronters.value
                return if (loaded != null) loaded.switch else switch
            }
        }
    }

    suspend fun recentSwitches(limit: Int = 10): PkResult<List<Switch>> =
        runCatchingPk { client.getRecentSwitches(limit) }

    /** Registers a switch and updates [currentFronters] on success. */
    suspend fun registerSwitch(memberUuids: List<String>): PkResult<Switch> =
        runCatchingPk {
            sent { client.registerSwitch(memberUuids) }.also(::setFronters)
        }.also { if (it is PkResult.Success) onSwitchRegistered(it.value) }

    /** Convenience: switch-out (empty switch). */
    suspend fun switchOut(): PkResult<Switch> = registerSwitch(emptyList())

    // Each write below is safe to retry when its response was lost: when
    // PluralKit refuses the retry, it's checked for having already applied it
    // (docs/system-management-design.md section 9).

    /** Sends a member edit ([MemberEdit.changes]), then fetches what it changed. */
    suspend fun updateMember(uuid: String, changes: Map<MemberField, String?>): PkResult<Member> {
        val result = runCatchingPk { sent { client.updateMember(uuid, changes) } }
        // Unless PluralKit refused it, the edit may have landed: cached names may be old.
        if (!result.refusedByPluralKit()) membersCacheMutex.withLock { membersCache = null }
        if (result is PkResult.Success) afterWrite()
        return result
    }

    /** Moves when a switch started. The caller keeps it between its neighbors. */
    suspend fun moveSwitch(uuid: String, to: Instant): PkResult<Unit> {
        val result = runCatchingPk {
            try {
                sent { client.moveSwitch(uuid, to) }
            } catch (e: PluralKitHttpException) {
                // Every switch's time is taken, its own included: an applied move refuses its retry.
                if (e.code != TIME_TAKEN || !isAt(uuid, to)) throw e
            }
            Unit
        }
        if (result is PkResult.Success) afterWrite()
        return result
    }

    /** Sets a switch's members, first as primary. Callers don't send an unchanged list. */
    suspend fun setSwitchMembers(uuid: String, memberUuids: List<String>): PkResult<Unit> {
        val result = runCatchingPk {
            try {
                sent { client.setSwitchMembers(uuid, memberUuids) }
            } catch (e: PluralKitHttpException) {
                // Already those members: an earlier try was applied.
                if (e.code != SAME_MEMBERS) throw e
            }
            Unit
        }
        if (result is PkResult.Success) afterWrite { if (it.switch?.uuid == uuid) onFrontCorrected(it.switch) }
        return result
    }

    suspend fun deleteSwitch(uuid: String): PkResult<Unit> {
        val result = runCatchingPk {
            // Asked fresh, not from a list on screen: someone may have switched since it loaded.
            if (client.getCurrentFronters()?.uuid == uuid) frontDeletes += uuid
            try {
                sent { client.deleteSwitch(uuid) }
            } catch (e: PluralKitHttpException) {
                // Already gone: an earlier try was applied.
                if (e.statusCode != 404) throw e
            }
        }
        if (result.refusedByPluralKit()) frontDeletes -= uuid
        if (result !is PkResult.Success) return result
        val wasFront = frontDeletes.remove(uuid)
        afterWrite { if (wasFront) onFrontCorrected(it.switch) }
        return result
    }

    // Deletes of the front whose outcome isn't settled. A retry of one whose
    // response was lost finds the front already handed back, and must still
    // tell friends.
    private val frontDeletes: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

    /**
     * Runs a write. It counts as landed unless PluralKit refused it: when the
     * response is lost, PluralKit may still have applied it.
     */
    private inline fun <T> sent(write: () -> T): T {
        try {
            return write().also { writes.incrementAndGet() }
        } catch (e: PluralKitHttpException) {
            throw e
        } catch (e: Throwable) {
            writes.incrementAndGet()
            throw e
        }
    }

    private fun PkResult<*>.refusedByPluralKit() = this is PkResult.Failure && error is PluralKitHttpException

    /** Whether switch [uuid] starts at [to]; false if that can't be told. */
    private suspend fun isAt(uuid: String, to: Instant): Boolean = try {
        val at = to.truncatedTo(ChronoUnit.MILLIS)
        client.getRecentSwitches(MAX_SWITCHES).any { it.uuid == uuid && it.timestamp == at }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        false
    }

    /**
     * Fetches the front after a write, then [correct]s with it. Finishes even
     * if the caller goes away: the write is done, and friends must still hear
     * of a corrected front.
     */
    private suspend fun afterWrite(correct: (LoadedFronters) -> Unit = {}) = withContext(NonCancellable) {
        fetchAfterWrite()?.let(correct)
    }

    /**
     * The front after a write, retried while a retry might work. The write is
     * done either way, so a failure only leaves the front showing as it was.
     * Null when it couldn't be fetched.
     */
    private suspend fun fetchAfterWrite(): LoadedFronters? {
        repeat(FETCH_ATTEMPTS) { attempt ->
            try {
                return LoadedFronters(fetchFronters())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not a rejected token, nor PluralKit asking us to slow down.
                val mightPass = e is IOException || (e is PluralKitHttpException && e.statusCode >= 500)
                if (!mightPass || attempt == FETCH_ATTEMPTS - 1) return null
                delay(RETRY_DELAY_MILLIS shl attempt)
            }
        }
        return null
    }

    private companion object {
        const val FETCH_ATTEMPTS = 3
        const val RETRY_DELAY_MILLIS = 500L
        const val MAX_SWITCHES = 100
        const val SAME_MEMBERS = 40004
        const val TIME_TAKEN = 40005
    }

    private fun setFronters(switch: Switch?) {
        _currentFronters.value = switch
        _loadedFronters.value = LoadedFronters(switch)
    }

    private inline fun <T> runCatchingPk(block: () -> T): PkResult<T> = try {
        PkResult.Success(block())
    } catch (e: CancellationException) {
        // A canceled caller must stay canceled, not see a Failure it might retry.
        throw e
    } catch (e: Exception) {
        PkResult.Failure(e)
    }
}

/** See [PluralKitRepository.loadedFronters]. */
data class LoadedFronters(val switch: Switch?)

/**
 * Two-state result. We could use kotlin.Result, but a sealed type plays nicer
 * with Compose `when` exhaustiveness checks and gives us room to add states
 * like `Unauthorized` later without breaking call sites.
 */
sealed interface PkResult<out T> {
    data class Success<T>(val value: T) : PkResult<T>
    data class Failure(val error: Throwable) : PkResult<Nothing> {
        /** PluralKit rejected the token; retrying won't help until it's replaced. */
        val isUnauthorized: Boolean
            get() = (error as? PluralKitHttpException)?.isUnauthorized == true
    }
}
