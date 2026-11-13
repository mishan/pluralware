package me.pluralware.shared.mock

import java.time.Instant
import kotlinx.coroutines.test.runTest
import me.pluralware.shared.api.PluralKitHttpException
import me.pluralware.shared.model.MemberField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The mock stands in for PluralKit in ViewModel tests, so its writes must follow PluralKit's rules. */
class MockPluralKitClientTest {

    private val client = MockPluralKitClient(artificialLatencyMillis = 0)

    private suspend fun refusal(call: suspend () -> Unit): Int? =
        (runCatching { call() }.exceptionOrNull() as? PluralKitHttpException)?.code

    @Test
    fun `a member update changes only what it's given, and null or empty clears`() = runTest {
        val alex = client.getOwnMembers().first()
        val cases = listOf(
            mapOf(MemberField.Name to "Alexis") to alex.copy(name = "Alexis"),
            mapOf(MemberField.Pronouns to null) to alex.copy(pronouns = null),
            mapOf(MemberField.Pronouns to "") to alex.copy(pronouns = null),
            mapOf(
                MemberField.Name to "Alexis", MemberField.DisplayName to "Lex", MemberField.Pronouns to "she/they",
                MemberField.Color to "123abc", MemberField.Description to "Hello.",
                MemberField.AvatarUrl to "https://example.com/a.png",
            ) to alex.copy(
                name = "Alexis", displayName = "Lex", pronouns = "she/they", color = "123abc",
                description = "Hello.", avatarUrl = "https://example.com/a.png",
            ),
        )
        for ((changes, expected) in cases) {
            val fresh = MockPluralKitClient(artificialLatencyMillis = 0)
            val uuid = fresh.getOwnMembers().first().uuid

            val updated = fresh.updateMember(uuid, changes)

            val want = expected.copy(uuid = uuid)
            assertEquals(changes.toString(), want, updated)
            assertEquals(changes.toString(), want, fresh.getOwnMembers().first())
        }
    }

    @Test
    fun `history and the current front show a member's edit`() = runTest {
        val fronting = client.getCurrentFronters()!!.members.first()

        client.updateMember(fronting.uuid, mapOf(MemberField.Name to "Renamed"))

        assertEquals("Renamed", client.getCurrentFronters()!!.members.first().name)
        assertEquals("Renamed", client.getRecentSwitches(10).first().members.first().name)
    }

    @Test
    fun `a registered switch becomes the front, its members in order`() = runTest {
        val members = client.getOwnMembers()
        val before = client.getRecentSwitches(100).size

        val switch = client.registerSwitch(listOf(members[2].uuid, members[0].uuid))

        assertEquals(listOf(members[2], members[0]), switch.members)
        assertEquals(switch, client.getCurrentFronters())
        assertEquals(switch, client.getRecentSwitches(1).single())
        assertEquals(before + 1, client.getRecentSwitches(100).size)
    }

    @Test
    fun `moving, changing and deleting a switch`() = runTest {
        val (latest, older) = client.getRecentSwitches(2)
        val members = client.getOwnMembers()
        val newTime = latest.timestamp.minusSeconds(60)

        assertEquals(newTime, client.moveSwitch(latest.uuid, newTime).timestamp)
        assertEquals(
            listOf(members[5].uuid, members[4].uuid),
            client.setSwitchMembers(latest.uuid, listOf(members[5].uuid, members[4].uuid)).members.map { it.uuid },
        )
        client.deleteSwitch(latest.uuid)

        assertEquals(older, client.getCurrentFronters())
    }

    @Test
    fun `a moved switch takes its place in history by time`() = runTest {
        val (latest, older) = client.getRecentSwitches(2)

        client.moveSwitch(older.uuid, latest.timestamp.plusSeconds(60))

        assertEquals(listOf(older.uuid, latest.uuid), client.getRecentSwitches(2).map { it.uuid })
    }

    @Test
    fun `a switch's members can be reordered, or emptied to a switch-out`() = runTest {
        val members = client.getOwnMembers()
        val latest = client.registerSwitch(listOf(members[0].uuid, members[1].uuid))

        val reordered = client.setSwitchMembers(latest.uuid, listOf(members[1].uuid, members[0].uuid))
        val out = client.setSwitchMembers(latest.uuid, emptyList())

        assertEquals(listOf(members[1], members[0]), reordered.members)
        assertEquals(true, out.isSwitchOut)
        assertEquals(true, client.getCurrentFronters()!!.isSwitchOut)
    }

    @Test
    fun `a switch moved between two others lands between them`() = runTest {
        val (first, second, third) = client.getRecentSwitches(3)
        val between = second.timestamp.plusSeconds(60)

        client.moveSwitch(third.uuid, between)

        assertEquals(listOf(first.uuid, third.uuid, second.uuid), client.getRecentSwitches(3).map { it.uuid })
    }

    @Test
    fun `times are kept to the millisecond, as the real client sends them`() = runTest {
        val (latest, older) = client.getRecentSwitches(2)
        val sameMillisecond = older.timestamp.plusNanos(400_000)

        assertEquals(older.timestamp.truncatedTo(java.time.temporal.ChronoUnit.MILLIS), older.timestamp)
        assertEquals(40005, refusal { client.moveSwitch(latest.uuid, sameMillisecond) })
    }

    @Test
    fun `deleting every switch leaves no front`() = runTest {
        client.getRecentSwitches(100).forEach { client.deleteSwitch(it.uuid) }

        assertNull(client.getCurrentFronters())
    }

    @Test
    fun `it refuses what PluralKit refuses, with PluralKit's codes`() = runTest {
        val (latest, older) = client.getRecentSwitches(2)
        val alex = client.getOwnMembers().first()
        val cases = listOf<Pair<Int, suspend () -> Unit>>(
            20002 to { client.updateMember("no-such-member", mapOf(MemberField.Name to "X")) },
            40001 to { client.updateMember(alex.uuid, mapOf(MemberField.Name to null)) },
            40001 to { client.updateMember(alex.uuid, mapOf(MemberField.Name to "")) },
            20002 to { client.registerSwitch(listOf("no-such-member")) },
            20002 to { client.registerSwitch(listOf(alex.id)) },
            20002 to { client.setSwitchMembers(latest.uuid, listOf("no-such-member")) },
            40003 to { client.registerSwitch(listOf(alex.uuid, alex.uuid)) },
            40004 to { client.registerSwitch(latest.members.map { it.uuid }) },
            20007 to { client.deleteSwitch("no-such-switch") },
            20007 to { client.moveSwitch("no-such-switch", Instant.now()) },
            20007 to { client.setSwitchMembers("no-such-switch", emptyList()) },
            40005 to { client.moveSwitch(latest.uuid, older.timestamp) },
            // Its own time too: PluralKit checks every switch, this one included.
            40005 to { client.moveSwitch(latest.uuid, latest.timestamp) },
            40004 to { client.setSwitchMembers(latest.uuid, latest.members.map { it.uuid }) },
            40003 to { client.setSwitchMembers(latest.uuid, listOf(alex.uuid, alex.uuid)) },
        )
        for ((code, call) in cases) assertEquals(code, refusal(call))
        assertEquals(
            mapOf("name" to listOf("Member name can not be set to null.")),
            (runCatching { client.updateMember(alex.uuid, mapOf(MemberField.Name to null)) }
                .exceptionOrNull() as PluralKitHttpException).fieldErrors,
        )
    }
}
