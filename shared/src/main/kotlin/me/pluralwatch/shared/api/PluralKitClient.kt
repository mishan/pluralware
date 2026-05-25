package me.pluralwatch.shared.api

import me.pluralwatch.shared.model.Member
import me.pluralwatch.shared.model.Switch
import me.pluralwatch.shared.model.SystemInfo

/**
 * Everything the rest of the app needs from PluralKit, in our domain types.
 *
 * Three implementations planned:
 *  - [PluralKotClient]      — wraps Plural.kt. Used during early development.
 *  - MockPluralKitClient    — fixed sample data. Used to build the UI offline.
 *  - KtorPluralKitClient    — hand-rolled fallback, if Plural.kt becomes a problem.
 *
 * Functions are suspend so they're naturally callable from Compose effects /
 * ViewModel scopes. Errors surface as exceptions; the repository layer turns
 * those into UI-friendly [PluralKitResult]s.
 */
interface PluralKitClient {
    /** GET /systems/@me — used to validate a token. */
    suspend fun getOwnSystem(): SystemInfo

    /** GET /systems/@me/members — populates the picker. Cache-friendly. */
    suspend fun getOwnMembers(): List<Member>

    /**
     * GET /systems/@me/fronters.
     * Returns null if the system has no registered switches (API returns 204).
     */
    suspend fun getCurrentFronters(): Switch?

    /**
     * GET /systems/@me/switches.
     * @param limit defaults to 10; the API caps at 100.
     */
    suspend fun getRecentSwitches(limit: Int = 10): List<Switch>

    /**
     * POST /systems/@me/switches.
     * @param memberUuids empty list = switch-out (nobody fronting).
     */
    suspend fun registerSwitch(memberUuids: List<String>): Switch
}

/** Auth marker. The token is otherwise an opaque string from `pk;token`. */
@JvmInline
value class PluralKitToken(val raw: String) {
    init { require(raw.isNotBlank()) { "PluralKit token must not be blank" } }
}
