package me.pluralwatch.shared.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.pluralwatch.shared.api.PluralKitClient
import me.pluralwatch.shared.model.Member
import me.pluralwatch.shared.model.Switch

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
) {
    private val membersCacheMutex = Mutex()
    private var membersCache: List<Member>? = null

    private val _currentFronters = MutableStateFlow<Switch?>(null)
    val currentFronters: StateFlow<Switch?> = _currentFronters.asStateFlow()

    suspend fun refreshMembers(force: Boolean = false): PkResult<List<Member>> = runCatchingPk {
        membersCacheMutex.withLock {
            val cached = membersCache
            if (!force && cached != null) return@withLock cached
            client.getOwnMembers().also { membersCache = it }
        }
    }

    suspend fun refreshFronters(): PkResult<Switch?> = runCatchingPk {
        client.getCurrentFronters().also { _currentFronters.value = it }
    }

    suspend fun recentSwitches(limit: Int = 10): PkResult<List<Switch>> =
        runCatchingPk { client.getRecentSwitches(limit) }

    /** Registers a switch and updates [currentFronters] on success. */
    suspend fun registerSwitch(memberUuids: List<String>): PkResult<Switch> =
        runCatchingPk {
            client.registerSwitch(memberUuids).also { _currentFronters.value = it }
        }

    /** Convenience: switch-out (empty switch). */
    suspend fun switchOut(): PkResult<Switch> = registerSwitch(emptyList())

    private inline fun <T> runCatchingPk(block: () -> T): PkResult<T> = try {
        PkResult.Success(block())
    } catch (e: Exception) {
        PkResult.Failure(e)
    }
}

/**
 * Two-state result. We could use kotlin.Result, but a sealed type plays nicer
 * with Compose `when` exhaustiveness checks and gives us room to add states
 * like `Unauthorized` later without breaking call sites.
 */
sealed interface PkResult<out T> {
    data class Success<T>(val value: T) : PkResult<T>
    data class Failure(val error: Throwable) : PkResult<Nothing>
}
