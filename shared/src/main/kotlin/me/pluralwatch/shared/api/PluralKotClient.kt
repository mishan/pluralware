package me.pluralwatch.shared.api

import me.pluralwatch.shared.model.Member
import me.pluralwatch.shared.model.Switch
import me.pluralwatch.shared.model.SystemInfo
import java.time.Instant

/**
 * [PluralKitClient] backed by the Plural.kt library (com.kotlindiscord.pluralkot:PluralKot).
 *
 * NOTE: Plural.kt was archived upstream in Aug 2024 with v1.0.0 from Mar 2021.
 * It still targets PluralKit API v2, but the surface area may lag the current API.
 * The wrapper isolates that risk — if we outgrow Plural.kt, swap this one class.
 *
 * VERIFY-AT-WIRING: the exact Plural.kt method names below (`system()`, `members()`,
 * `fronters()`, `switches()`, `registerSwitch()`) are inferred from the README example
 * `pk.system()`. Before first run, open the `PluralKit` class in the dependency and
 * confirm the names match — fix here if not.
 */
@Suppress("UNUSED_PARAMETER") // Until we uncomment the real wiring.
class PluralKotClient(token: PluralKitToken) : PluralKitClient {

    // Uncomment once Plural.kt's actual API is confirmed:
    // private val pk = com.kotlindiscord.pluralkot.PluralKit(token.raw)

    override suspend fun getOwnSystem(): SystemInfo {
        TODO(
            "Wire to pk.system() — confirm method name in Plural.kt source, " +
                "then map fields to SystemInfo."
        )
    }

    override suspend fun getOwnMembers(): List<Member> {
        TODO("Wire to pk.members() or equivalent; map each to our Member.")
    }

    override suspend fun getCurrentFronters(): Switch? {
        TODO(
            "Wire to pk.fronters(); return null when the API returns 204 " +
                "(no switches registered yet)."
        )
    }

    override suspend fun getRecentSwitches(limit: Int): List<Switch> {
        TODO("Wire to pk.switches(limit = limit). Map to our Switch.")
    }

    override suspend fun registerSwitch(memberUuids: List<String>): Switch {
        TODO(
            "Wire to pk.registerSwitch(memberUuids) or equivalent. " +
                "Empty list must produce a switch-out."
        )
    }

    // --- Mapping helpers go here once we know Plural.kt's exact types. ---
    // private fun com.kotlindiscord.pluralkot.entities.Member.toDomain() = Member(...)
    // private fun com.kotlindiscord.pluralkot.entities.Switch.toDomain() = Switch(...)
}
