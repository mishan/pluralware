package me.pluralware.shared.mock

import kotlinx.coroutines.delay
import me.pluralware.shared.api.PluralKitClient
import me.pluralware.shared.api.PluralKitHttpException
import me.pluralware.shared.model.Member
import me.pluralware.shared.model.MemberField
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
    private val members: MutableList<Member> = mutableListOf(
        member("alpha", "Alex", pronouns = "she/her", color = "ff6b9d"),
        member("brava", "Bea", pronouns = "they/them", color = "6bcfff"),
        member("charl", "Cam", pronouns = "he/him", color = "9d6bff"),
        member("delta", "Dani", pronouns = "she/they", color = "6bff9d"),
        member("echo0", "Eli", pronouns = "they/them", color = "ffcc6b"),
        member("foxtr", "Frey", pronouns = "any", color = "ff6b6b"),
    )

    // Mutable history, newest first. Seeded with a few past switches. Like
    // PluralKit, a switch holds member references, so member edits show in it.
    private class StoredSwitch(val uuid: String, var timestamp: Instant, var memberUuids: List<String>)

    private val switches: MutableList<StoredSwitch> = mutableListOf(
        stored(now().minus(30, ChronoUnit.MINUTES), members[0]),
        stored(now().minus(3, ChronoUnit.HOURS), members[1], members[3]),
        stored(now().minus(8, ChronoUnit.HOURS), members[2]),
        stored(now().minus(1, ChronoUnit.DAYS)), // switch-out
        stored(now().minus(2, ChronoUnit.DAYS), members[4]),
    )

    override suspend fun getOwnSystem(): SystemInfo {
        delayLike()
        return system
    }

    override suspend fun getOwnMembers(): List<Member> {
        delayLike()
        return members.toList()
    }

    override suspend fun getCurrentFronters(): Switch? {
        delayLike()
        return switches.firstOrNull()?.resolved()
    }

    override suspend fun getRecentSwitches(limit: Int): List<Switch> {
        delayLike()
        return switches.take(limit).map { it.resolved() }
    }

    override suspend fun registerSwitch(memberUuids: List<String>): Switch {
        delayLike()
        checkMembers(memberUuids)
        if (memberUuids == switches.firstOrNull()?.memberUuids) {
            throw refused(400, 40004, "Member list identical to current fronter list.")
        }
        val newSwitch = StoredSwitch(UUID.randomUUID().toString(), now(), memberUuids)
        switches.add(0, newSwitch)
        return newSwitch.resolved()
    }

    override suspend fun updateMember(uuid: String, changes: Map<MemberField, String?>): Member {
        delayLike()
        val index = members.indexOfFirst { it.uuid == uuid }
        if (index < 0) throw refused(404, 20002, "Member not found.")
        // Like PluralKit: an empty value clears the field.
        val updated = changes.mapValues { it.value?.ifEmpty { null } }.entries.fold(members[index]) { member, (field, value) ->
            when (field) {
                MemberField.Name -> member.copy(name = value ?: throw nameRequired())
                MemberField.DisplayName -> member.copy(displayName = value)
                MemberField.Pronouns -> member.copy(pronouns = value)
                MemberField.Color -> member.copy(color = value)
                MemberField.Description -> member.copy(description = value)
                MemberField.AvatarUrl -> member.copy(avatarUrl = value)
            }
        }
        members[index] = updated
        return updated
    }

    override suspend fun moveSwitch(uuid: String, to: Instant): Switch {
        delayLike()
        val switch = find(uuid)
        val to = to.truncatedTo(ChronoUnit.MILLIS)
        if (switches.any { it.timestamp == to }) throw refused(400, 40005, "Switch with provided timestamp already exists.")
        switch.timestamp = to
        switches.sortByDescending { it.timestamp }
        return switch.resolved()
    }

    override suspend fun setSwitchMembers(uuid: String, memberUuids: List<String>): Switch {
        delayLike()
        val switch = find(uuid)
        checkMembers(memberUuids)
        if (memberUuids == switch.memberUuids) throw refused(400, 40004, "Member list identical to current fronter list.")
        switch.memberUuids = memberUuids
        return switch.resolved()
    }

    override suspend fun deleteSwitch(uuid: String) {
        delayLike()
        switches.remove(find(uuid))
    }

    private fun find(uuid: String): StoredSwitch =
        switches.firstOrNull { it.uuid == uuid } ?: throw refused(404, 20007, "Switch not found.")

    private fun checkMembers(memberUuids: List<String>) {
        memberUuids.firstOrNull { id -> members.none { it.uuid == id } }
            ?.let { throw refused(404, 20002, "Member '$it' not found.") }
        if (memberUuids.distinct().size != memberUuids.size) throw refused(400, 40003, "Duplicate members in member list.")
    }

    private fun nameRequired() = PluralKitHttpException(
        statusCode = 400, message = "Error parsing JSON model", code = 40001,
        fieldErrors = mapOf("name" to listOf("Member name can not be set to null.")),
    )

    private fun refused(status: Int, code: Int, message: String) =
        PluralKitHttpException(statusCode = status, message = message, code = code)

    private fun stored(at: Instant, vararg fronting: Member) =
        StoredSwitch(UUID.randomUUID().toString(), at, fronting.map { it.uuid })

    private fun StoredSwitch.resolved() =
        Switch(uuid, timestamp, memberUuids.mapNotNull { id -> members.firstOrNull { it.uuid == id } })

    private fun member(id: String, name: String, pronouns: String?, color: String?) = Member(
        id = id,
        uuid = UUID.randomUUID().toString(),
        name = name,
        displayName = null,
        pronouns = pronouns,
        color = color,
        avatarUrl = null,
    )

    // To the millisecond, as PluralKit stores times and the real client sends them.
    private fun now(): Instant = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    private suspend fun delayLike() {
        if (artificialLatencyMillis > 0) delay(artificialLatencyMillis)
    }
}
