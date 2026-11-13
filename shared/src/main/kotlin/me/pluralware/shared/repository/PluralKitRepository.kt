package me.pluralware.shared.repository

import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
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
    /** Called with a member as PluralKit has it after an edit made here, and the fields it changed. Must not block. */
    private val onMemberEdited: (Member, Set<MemberField>) -> Unit = { _, _ -> },
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

    private val _memberEdits = MutableStateFlow(0)

    /**
     * Counts member edits that may have landed (all but those PluralKit
     * refused), so a screen listing members can reload when it changes, even
     * for an edit that finished after its editor closed.
     */
    val memberEdits: StateFlow<Int> = _memberEdits.asStateFlow()

    private val _membersChanged = MutableStateFlow(0)

    /**
     * Counts every change to members this repository hears of: [memberEdits]
     * here, and edits on the other device ([forgetMembers]), so a screen
     * showing members reloads either way. Not [memberEdits], which counts
     * only this device's own, to be told to the other device.
     */
    val membersChanged: StateFlow<Int> = _membersChanged.asStateFlow()

    // Counts writes that succeeded. A fetch that was in flight while one did
    // may have read PluralKit from before it, so it doesn't replace what the
    // write's own fetch shows.
    private val writes = AtomicInteger()

    // Member fetches by when they started, so an older one landing late (after
    // an edit made elsewhere) doesn't replace a newer one in the cache.
    private val memberFetches = AtomicInteger()
    private var cachedMemberFetch = 0

    suspend fun refreshMembers(force: Boolean = false): PkResult<List<Member>> = runCatchingPk {
        if (!force) membersCacheMutex.withLock { membersCache }?.let { return@runCatchingPk it }
        val writesBefore = writes.get()
        val fetch = memberFetches.incrementAndGet()
        client.getOwnMembers().also { members ->
            membersCacheMutex.withLock {
                if (writes.get() == writesBefore && fetch > cachedMemberFetch) {
                    membersCache = members
                    cachedMemberFetch = fetch
                }
            }
        }
    }

    /** Members were edited elsewhere: drop what's cached about them, so the next look fetches. */
    suspend fun forgetMembers() {
        // Counted as a write, so a member list fetched before the edit isn't cached over it.
        writes.incrementAndGet()
        membersCacheMutex.withLock { membersCache = null }
        client.forgetMembers()
        _membersChanged.update { it + 1 }
    }

    /** The system this token belongs to. Not cached: it's for showing who's signed in. */
    suspend fun ownSystem(): PkResult<SystemInfo> = runCatchingPk { client.getOwnSystem() }

    suspend fun refreshFronters(): PkResult<Switch?> = runCatchingPk { fetchFronters().switch }

    /** A fronters fetch. Not [fresh] when writes kept landing during it: it may predate them. */
    private class Fetched(val switch: Switch?, val fresh: Boolean)

    private suspend fun fetchFronters(): Fetched {
        var attempt = 0
        while (true) {
            val writesBefore = writes.get()
            val switch = client.getCurrentFronters()
            if (writes.get() == writesBefore) {
                setFronters(switch)
                while (true) pendingCorrections.poll()?.invoke(switch) ?: break
                return Fetched(switch, fresh = true)
            }
            if (++attempt == FETCH_ATTEMPTS) {
                // Writes keep landing. Their own fetches came after this one: answer with theirs.
                val loaded = _loadedFronters.value
                return Fetched(if (loaded != null) loaded.switch else switch, fresh = false)
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

    // Each write below is safe to retry when its response was lost: a retry
    // that finds the write already applied counts as a success
    // (docs/system-management-design.md section 9).
    //
    // Writes run in writeScope, not the caller's scope: a screen that goes
    // away mid-write mustn't cut short what follows it (clearing caches,
    // correcting the front). close() cancels them when the session ends.

    /** Sends a member edit ([MemberEdit.changes]), then fetches what it changed. */
    suspend fun updateMember(uuid: String, changes: Map<MemberField, String?>): PkResult<Member> = detached {
        val result = runCatchingPk { sent { client.updateMember(uuid, changes) } }
        // Unless PluralKit refused it, the edit may have landed: cached names may be old.
        if (!result.refusedByPluralKit()) {
            membersCacheMutex.withLock { membersCache = null }
            _memberEdits.update { it + 1 }
            _membersChanged.update { it + 1 }
        }
        if (result is PkResult.Success) {
            onMemberEdited(result.value, changes.keys)
            afterWrite()
        }
        result
    }

    /**
     * Moves when a switch started. It must stay between the switches either
     * side of it, checked against PluralKit just before sending; otherwise
     * the result is a [SwitchTimeOutOfRange] failure.
     */
    suspend fun moveSwitch(uuid: String, to: Instant): PkResult<Unit> = detached {
        val result = runCatchingPk {
            val at = to.truncatedTo(ChronoUnit.MILLIS)
            val recent = client.getRecentSwitches(MAX_SWITCHES)
            val index = recent.indexOfFirst { it.uuid == uuid }
            // Already there: a retry of a move whose response was lost.
            if (index >= 0 && recent[index].timestamp == at) return@runCatchingPk Unit
            if (index >= 0) {
                val after = recent.getOrNull(index + 1)?.timestamp
                val before = recent.getOrNull(index - 1)?.timestamp ?: Instant.now()
                if ((after != null && !at.isAfter(after)) || !at.isBefore(before)) throw SwitchTimeOutOfRange(after, before)
            }
            sent { client.moveSwitch(uuid, to) }
            Unit
        }
        if (result is PkResult.Success) afterWrite()
        result
    }

    /** Sets a switch's members, first as primary. An unchanged list is refused (40004). */
    suspend fun setSwitchMembers(uuid: String, memberUuids: List<String>): PkResult<Unit> = detached {
        // Only a retry of a fix whose response was lost may take "already those members" as done.
        val retrying = unsettledFixes[uuid] == memberUuids
        unsettledFixes[uuid] = memberUuids
        val result = runCatchingPk {
            try {
                sent { client.setSwitchMembers(uuid, memberUuids) }
            } catch (e: PluralKitHttpException) {
                if (e.code != SAME_MEMBERS || !retrying) throw e
            }
            Unit
        }
        if (result is PkResult.Success || result.refusedByPluralKit()) unsettledFixes.remove(uuid)
        if (result is PkResult.Success) afterWrite { front -> if (front?.uuid == uuid) onFrontCorrected(front) }
        result
    }

    /** Deletes a switch: [Deletion.AlreadyGone] if PluralKit no longer had it. */
    suspend fun deleteSwitch(uuid: String): PkResult<Deletion> = detached {
        val retrying = unsettledDeletes.containsKey(uuid)
        var refused = false
        val result = runCatchingPk {
            // Asked fresh, not from a list on screen: someone may have switched since it loaded.
            val isFront = client.getCurrentFronters()?.uuid == uuid
            // A retry finds the front already handed back if the first try landed; remember it was the front.
            unsettledDeletes[uuid] = unsettledDeletes[uuid] == true || isFront
            try {
                sent { client.deleteSwitch(uuid) }
                Deletion.Done
            } catch (e: PluralKitHttpException) {
                refused = e.code !in SWITCH_GONE
                if (refused) throw e
                if (retrying) Deletion.Done else Deletion.AlreadyGone
            }
        }
        val wasFront = unsettledDeletes[uuid] == true
        // Settled only by the delete itself: a front check that failed sent nothing.
        if (result is PkResult.Success || refused) unsettledDeletes.remove(uuid)
        if (result is PkResult.Success) afterWrite(if (wasFront) onFrontCorrected else null)
        result
    }

    /** Stops writes still under way, so nothing is announced for a session that has ended. */
    fun close() {
        writeScope.cancel()
        pendingCorrections.clear()
    }

    private val writeScope = CoroutineScope(SupervisorJob())

    // Writes whose response was lost, so it's not known whether they landed.
    private val unsettledFixes = ConcurrentHashMap<String, List<String>>()
    private val unsettledDeletes = ConcurrentHashMap<String, Boolean>()

    // Corrections whose fetch after the write failed; the next fetch that works delivers them.
    private val pendingCorrections = ConcurrentLinkedQueue<(Switch?) -> Unit>()

    /** Runs [write] in [writeScope], on the caller's dispatcher. The caller can stop waiting; the write goes on. */
    private suspend fun <T> detached(write: suspend () -> T): T {
        val dispatcher = currentCoroutineContext()[ContinuationInterceptor] ?: EmptyCoroutineContext
        return writeScope.async(dispatcher) { write() }.await()
    }

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

    /** Fetches the front after a write and [correct]s with it, now or with the next fetch that works. */
    private suspend fun afterWrite(correct: ((Switch?) -> Unit)? = null) {
        val after = fetchAfterWrite()
        if (correct == null) return
        if (after != null) correct(after.switch) else pendingCorrections += correct
    }

    /**
     * The front after a write, retried while a retry might work. The write is
     * done either way, so a failure only leaves the front showing as it was.
     * Null when it couldn't be fetched.
     */
    private suspend fun fetchAfterWrite(): LoadedFronters? {
        repeat(FETCH_ATTEMPTS) { attempt ->
            try {
                val fetched = fetchFronters()
                // Not known to follow the write: a correction waits for a fetch that is.
                return if (fetched.fresh) LoadedFronters(fetched.switch) else null
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
        val SWITCH_GONE = setOf(20007, 20008)
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

/** How a delete went: [AlreadyGone] when PluralKit no longer had the switch, deleted elsewhere. */
enum class Deletion { Done, AlreadyGone }

/** A switch can't move to a time outside its neighbors: after [after] (if any) and before [before]. */
class SwitchTimeOutOfRange(val after: Instant?, val before: Instant) :
    IllegalArgumentException("A switch's new time must fall between the switches either side of it")

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
