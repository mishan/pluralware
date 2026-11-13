package me.pluralware.shared.api

import java.security.MessageDigest
import java.time.Instant
import me.pluralware.shared.model.Member
import me.pluralware.shared.model.MemberField
import me.pluralware.shared.model.Switch
import me.pluralware.shared.model.SystemInfo

/**
 * Everything the rest of the app needs from PluralKit, in our domain types.
 *
 * Two implementations:
 *  - [RetrofitPluralKitClient] — production. Hand-rolled wrapper around
 *    PluralKit API v2 using Retrofit + OkHttp + kotlinx-serialization.
 *    Construct via [PluralKitClientFactory.create].
 *  - [me.pluralware.shared.mock.MockPluralKitClient] — fixed sample data with
 *    artificial latency. Drives previews and ViewModel tests offline.
 *
 * Functions are suspend so they're naturally callable from Compose effects /
 * ViewModel scopes. Errors surface as exceptions; the repository layer turns
 * those into UI-friendly [me.pluralware.shared.repository.PkResult]s.
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

    /**
     * PATCH /members/{uuid} with only [changes]: PluralKit leaves a field it
     * isn't sent alone and clears one sent as null. Build [changes] with
     * [me.pluralware.shared.model.MemberEdit.changes]; nothing else should.
     */
    suspend fun updateMember(uuid: String, changes: Map<MemberField, String?>): Member

    /** PATCH /systems/@me/switches/{uuid}: moves when the switch started. */
    suspend fun moveSwitch(uuid: String, to: Instant): Switch

    /** PATCH /systems/@me/switches/{uuid}/members: who the switch is, first as primary. */
    suspend fun setSwitchMembers(uuid: String, memberUuids: List<String>): Switch

    /** DELETE /systems/@me/switches/{uuid}. */
    suspend fun deleteSwitch(uuid: String)
}

/** Auth marker. The token is otherwise an opaque string from `pk;token`. */
@JvmInline
value class PluralKitToken(val raw: String) {
    init { require(raw.isNotBlank()) { "PluralKit token must not be blank" } }

    /**
     * Names the token without revealing it: a truncated SHA-256. Caches and
     * sync notices record it, so data from one system is never taken for
     * another's.
     */
    val fingerprint: String
        get() = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray())
            .take(16).joinToString("") { "%02x".format(it) }
}
