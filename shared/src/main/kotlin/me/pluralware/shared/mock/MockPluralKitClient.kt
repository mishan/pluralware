package me.pluralware.shared.mock

import kotlinx.coroutines.delay
import me.pluralware.shared.api.PluralKitClient
import me.pluralware.shared.model.Member
import me.pluralware.shared.model.Switch
import me.pluralware.shared.model.SystemInfo
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * In-memory client for UI development. Behaves enough like the real thing
 * (latency, state changes, switch-outs) to surface real UX issues.
 *
 * Not thread-safe. Suitable only for previews and ViewModel tests.
 */
class MockPluralKitClient(
    /** Approximate latency injected into every call. Set to 0 in tests. */
    private val artificialLatencyMillis: Long = 250L,
) : PluralKitClient {

    private val system = SystemInfo(
        id = "exmpl",
        uuid = UUID.randomUUID().toString(),
        name = "Sample System",
        tag = "| Sample",
    )

    // Six members — matches the system sizes the user expects to support.
    private val members: List<Member> = listOf(
        member("alpha", "Alex", pronouns = "she/her", color = "ff6b9d"),
        member("brava", "Bea", pronouns = "they/them", color = "6bcfff"),
        member("charl", "Cam", pronouns = "he/him", color = "9d6bff"),
        member("delta", "Dani", pronouns = "she/they", color = "6bff9d"),
        member("echo0", "Eli", pronouns = "they/them", color = "ffcc6b"),
        member("foxtr", "Frey", pronouns = "any", color = "ff6b6b"),
    )

    // Mutable history, newest first. Seeded with a few past switches.
    private val switches: MutableList<Switch> = mutableListOf(
        Switch(UUID.randomUUID().toString(), now().minus(30, ChronoUnit.MINUTES), listOf(members[0])),
        Switch(UUID.randomUUID().toString(), now().minus(3, ChronoUnit.HOURS), listOf(members[1], members[3])),
        Switch(UUID.randomUUID().toString(), now().minus(8, ChronoUnit.HOURS), listOf(members[2])),
        Switch(UUID.randomUUID().toString(), now().minus(1, ChronoUnit.DAYS), emptyList()), // switch-out
        Switch(UUID.randomUUID().toString(), now().minus(2, ChronoUnit.DAYS), listOf(members[4])),
    )

    override suspend fun getOwnSystem(): SystemInfo {
        delayLike()
        return system
    }

    override suspend fun getOwnMembers(): List<Member> {
        delayLike()
        return members
    }

    override suspend fun getCurrentFronters(): Switch? {
        delayLike()
        return switches.firstOrNull()
    }

    override suspend fun getRecentSwitches(limit: Int): List<Switch> {
        delayLike()
        return switches.take(limit)
    }

    override suspend fun registerSwitch(memberUuids: List<String>): Switch {
        delayLike()
        val resolved = memberUuids.map { uuid -> members.first { it.uuid == uuid } }
        val newSwitch = Switch(UUID.randomUUID().toString(), now(), resolved)
        switches.add(0, newSwitch)
        return newSwitch
    }

    private fun member(id: String, name: String, pronouns: String?, color: String?) = Member(
        id = id,
        uuid = UUID.randomUUID().toString(),
        name = name,
        displayName = null,
        pronouns = pronouns,
        color = color,
        avatarUrl = null,
    )

    private fun now(): Instant = Instant.now()

    private suspend fun delayLike() {
        if (artificialLatencyMillis > 0) delay(artificialLatencyMillis)
    }
}
