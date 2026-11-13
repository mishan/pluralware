package me.pluralware.shared

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.pluralware.shared.api.PluralKitClient
import me.pluralware.shared.api.PluralKitHttpException
import me.pluralware.shared.mock.MockPluralKitClient
import me.pluralware.shared.model.Member
import me.pluralware.shared.model.MemberField
import me.pluralware.shared.model.Switch
import me.pluralware.shared.repository.Deletion
import me.pluralware.shared.repository.PkResult
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.repository.SwitchTimeOutOfRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluralKitRepositoryWritesTest {

    private val client = FlakyClient()
    private val corrected = mutableListOf<Switch?>()
    private val registered = mutableListOf<Switch>()
    private val repository = PluralKitRepository(client, onFrontCorrected = { corrected += it }) { registered += it }

    private suspend fun members() = client.pk.getOwnMembers()
    private suspend fun history() = client.pk.getRecentSwitches(100)

    private fun PkResult<*>.isSuccess() = this is PkResult.Success

    // --- Members -------------------------------------------------------------------------------

    @Test
    fun `a member edit shows everywhere at once`() = runTest {
        repository.refreshFronters()
        val fronting = repository.currentFronters.value!!.members.first()
        val cachedBefore = (repository.refreshMembers() as PkResult.Success).value

        val result = repository.updateMember(fronting.uuid, mapOf(MemberField.Name to "Renamed"))

        assertEquals("Renamed", (result as PkResult.Success).value.name)
        assertEquals("Renamed", repository.currentFronters.value!!.members.first().name)
        assertEquals("Renamed", repository.loadedFronters.value!!.switch!!.members.first().name)
        val cachedAfter = (repository.refreshMembers() as PkResult.Success).value
        assertTrue("the member cache was dropped", cachedAfter !== cachedBefore)
        assertEquals("Renamed", cachedAfter.first { it.uuid == fronting.uuid }.name)
    }

    @Test
    fun `a member edit is never announced as a new or corrected front`() = runTest {
        val member = members().first()

        repository.updateMember(member.uuid, mapOf(MemberField.Pronouns to "xe/xem"))

        assertEquals(emptyList<Switch?>(), corrected)
        assertEquals(emptyList<Switch>(), registered)
    }

    @Test
    fun `a member edit that fails changes nothing here`() = runTest {
        repository.refreshFronters()
        val before = repository.currentFronters.value

        val result = repository.updateMember("no-such-member", mapOf(MemberField.Name to "X"))

        assertEquals(20002, ((result as PkResult.Failure).error as PluralKitHttpException).code)
        assertEquals(before, repository.currentFronters.value)
    }

    // --- Switches: what changes and who hears about it ---------------------------------------------

    @Test
    fun `fixing the current switch's members corrects the front and tells friends`() = runTest {
        val latest = history().first()
        val newMembers = listOf(members()[4].uuid, members()[5].uuid)

        assertTrue(repository.setSwitchMembers(latest.uuid, newMembers).isSuccess())

        assertEquals(newMembers, repository.currentFronters.value!!.members.map { it.uuid })
        assertEquals(listOf(repository.currentFronters.value), corrected)
        assertEquals(emptyList<Switch>(), registered)
    }

    @Test
    fun `fixing an older switch's members tells nobody`() = runTest {
        val older = history()[1]

        assertTrue(repository.setSwitchMembers(older.uuid, listOf(members()[5].uuid)).isSuccess())

        assertEquals(emptyList<Switch?>(), corrected)
        assertEquals(listOf(members()[5].uuid), history()[1].members.map { it.uuid })
    }

    @Test
    fun `deleting the current switch hands the front back and tells friends`() = runTest {
        val (latest, older) = history()

        assertTrue(repository.deleteSwitch(latest.uuid).isSuccess())

        assertEquals(older, repository.currentFronters.value)
        assertEquals(listOf<Switch?>(older), corrected)
    }

    @Test
    fun `deleting an older switch tells nobody`() = runTest {
        val older = history()[2]

        assertTrue(repository.deleteSwitch(older.uuid).isSuccess())

        assertEquals(emptyList<Switch?>(), corrected)
        assertTrue(history().none { it.uuid == older.uuid })
    }

    @Test
    fun `deleting the last switch tells friends nobody is fronting`() = runTest {
        history().drop(1).forEach { client.pk.deleteSwitch(it.uuid) }

        assertTrue(repository.deleteSwitch(history().single().uuid).isSuccess())

        assertNull(repository.currentFronters.value)
        assertNotNull("loaded, with no switches", repository.loadedFronters.value)
        assertEquals(listOf<Switch?>(null), corrected)
    }

    @Test
    fun `deleting a switch-out hands the front back to the switch before it`() = runTest {
        val before = repository.registerSwitch(listOf(members()[2].uuid))
        val out = client.pk.registerSwitch(emptyList())

        assertTrue(repository.deleteSwitch(out.uuid).isSuccess())

        assertEquals(listOf<Switch?>((before as PkResult.Success).value), corrected)
    }

    @Test
    fun `a delete that can't check the front first deletes nothing`() = runTest {
        val latest = history().first()
        client.fronterFetchFailures = 1

        assertTrue(repository.deleteSwitch(latest.uuid) is PkResult.Failure)

        assertEquals(latest, history().first())
        assertEquals(emptyList<Switch?>(), corrected)
    }

    @Test
    fun `a front changed in Discord isn't taken for a correction`() = runTest {
        // The screen still shows the old latest switch; someone switched elsewhere since.
        val shownLatest = history().first()
        repository.refreshFronters()
        client.pk.registerSwitch(listOf(members()[5].uuid))

        assertTrue(repository.deleteSwitch(shownLatest.uuid).isSuccess())
        assertTrue(repository.setSwitchMembers(history()[1].uuid, listOf(members()[3].uuid)).isSuccess())

        assertEquals(emptyList<Switch?>(), corrected)
        assertEquals(listOf(members()[5].uuid), repository.currentFronters.value!!.members.map { it.uuid })
    }

    @Test
    fun `moving a switch updates the front's time and tells nobody`() = runTest {
        val (latest, older) = history()
        val to = older.timestamp.plusSeconds(60)

        assertTrue(repository.moveSwitch(latest.uuid, to).isSuccess())

        assertEquals(to, repository.currentFronters.value!!.timestamp)
        assertEquals(emptyList<Switch?>(), corrected)
    }

    @Test
    fun `PluralKit's refusals come back as failures with its code`() = runTest {
        val (latest, older) = history()
        val cases = listOf<Pair<Int, suspend () -> PkResult<*>>>(
            20007 to { repository.moveSwitch("no-such-switch", Instant.now()) },
            40003 to { repository.setSwitchMembers(latest.uuid, listOf(members()[0].uuid, members()[0].uuid)) },
            20002 to { repository.setSwitchMembers(latest.uuid, listOf("no-such-member")) },
        )
        for ((code, call) in cases) {
            val result = call()
            assertEquals(code, ((result as PkResult.Failure).error as PluralKitHttpException).code)
        }
        assertEquals(emptyList<Switch?>(), corrected)
    }

    // --- A response lost after PluralKit applied the write; the user taps Retry -----------------

    @Test
    fun `retrying a lost member edit or members fix succeeds, whatever PluralKit says the second time`() = runTest {
        val members = members()
        val cases = listOf<Pair<String, suspend () -> PkResult<*>>>(
            // The same values again: PluralKit accepts.
            "member edit" to { repository.updateMember(members[1].uuid, mapOf(MemberField.Name to "Bee")) },
            // Already those members: 40004.
            "members fix" to { repository.setSwitchMembers(history()[1].uuid, listOf(members[5].uuid)) },
            // The switch already has that time, so PluralKit says it's taken: 40005.
        )
        for ((what, call) in cases) {
            client.loseNextResponse = true
            assertTrue("$what: first try fails", call() is PkResult.Failure)
        }
        // Each retry repeats exactly what the user asked for.
        assertTrue(repository.updateMember(members[1].uuid, mapOf(MemberField.Name to "Bee")).isSuccess())
        assertTrue(repository.setSwitchMembers(history()[1].uuid, listOf(members[5].uuid)).isSuccess())
        assertEquals("the members fix's retry was refused as already done", listOf<Int?>(40004), client.refusals)
    }

    @Test
    fun `retrying a lost move finds it landed, and doesn't send it again`() = runTest {
        val older = history()[1]
        val to = older.timestamp.minusSeconds(60)
        client.loseNextResponse = true
        assertTrue(repository.moveSwitch(older.uuid, to) is PkResult.Failure)

        assertTrue("the retry", repository.moveSwitch(older.uuid, to).isSuccess())

        assertEquals(to, history()[1].timestamp)
        assertEquals("PluralKit wasn't asked again", emptyList<Int?>(), client.refusals)
    }

    @Test
    fun `a move must stay between the switches either side of it, checked against PluralKit just before`() = runTest {
        val (latest, older, oldest) = history()
        val hour = 3_600L
        val cases = listOf(
            Triple(older, oldest.timestamp.plusSeconds(hour), true),
            Triple(older, latest.timestamp.minusSeconds(60), true),
            Triple(older, oldest.timestamp, false),
            Triple(older, oldest.timestamp.minusSeconds(60), false),
            Triple(older, latest.timestamp, false),
            Triple(older, latest.timestamp.plusSeconds(60), false),
            Triple(latest, Instant.now().minusSeconds(60), true),
            Triple(latest, Instant.now().plusSeconds(hour), false),
        )
        for ((switch, to, allowed) in cases) {
            val before = history()

            val result = repository.moveSwitch(switch.uuid, to)

            if (allowed) {
                assertTrue("$to", result.isSuccess())
                client.pk.moveSwitch(switch.uuid, switch.timestamp)
            } else {
                val refused = (result as PkResult.Failure).error as SwitchTimeOutOfRange
                val index = before.indexOfFirst { it.uuid == switch.uuid }
                assertEquals("$to", before.getOrNull(index + 1)?.timestamp, refused.after)
                assertEquals("$to", before.getOrNull(index - 1)?.timestamp, refused.before.takeIf { index > 0 })
                assertEquals("not sent", before, history())
            }
        }
    }

    @Test
    fun `a move PluralKit refuses as a taken time stays refused`() = runTest {
        val older = history()[1]
        client.refuseNextWrite = PluralKitHttpException(400, "Switch with provided timestamp already exists.", code = 40005)

        val result = repository.moveSwitch(older.uuid, older.timestamp.minusSeconds(60))

        assertEquals(40005, ((result as PkResult.Failure).error as PluralKitHttpException).code)
    }

    @Test
    fun `retrying a lost delete of the current switch succeeds`() = runTest {
        val (latest, older) = history()
        client.loseNextResponse = true
        assertTrue(repository.deleteSwitch(latest.uuid) is PkResult.Failure)

        assertTrue("the retry", repository.deleteSwitch(latest.uuid).isSuccess())

        assertEquals(older, repository.currentFronters.value)
        // The retry found the front already handed back; friends still hear of it.
        assertEquals(listOf<Switch?>(older), corrected)
    }

    @Test
    fun `a move that can't check its neighbors isn't sent`() = runTest {
        val older = history()[1]
        client.recentSwitchesFail = true

        val result = repository.moveSwitch(older.uuid, older.timestamp.minusSeconds(60))

        assertTrue((result as PkResult.Failure).error is IOException)
        assertEquals(older, client.pk.getRecentSwitches(2)[1])
    }

    @Test
    fun `retrying a lost members fix of the current switch still tells friends`() = runTest {
        val latest = history().first()
        val fix = listOf(members()[4].uuid)
        client.loseNextResponse = true
        assertTrue(repository.setSwitchMembers(latest.uuid, fix) is PkResult.Failure)

        assertTrue("the retry", repository.setSwitchMembers(latest.uuid, fix).isSuccess())

        assertEquals(fix, corrected.single()!!.members.map { it.uuid })
    }

    // --- The fetch after a write ---------------------------------------------------------------

    @Test
    fun `a fetch after a write that fails is tried again`() = runTest {
        val latest = history().first()
        client.fronterFetchFailures = 2

        assertTrue(repository.setSwitchMembers(latest.uuid, listOf(members()[4].uuid)).isSuccess())

        assertEquals(listOf(members()[4].uuid), repository.currentFronters.value!!.members.map { it.uuid })
        assertEquals(1, corrected.size)
    }

    @Test
    fun `a write that worked is a success even if the fetch after it never does`() = runTest {
        val latest = history().first()
        client.fronterFetchFailures = 100
        client.now = { currentTime }

        assertTrue(repository.setSwitchMembers(latest.uuid, listOf(members()[4].uuid)).isSuccess())

        assertEquals("the edit is in PluralKit", listOf(members()[4].uuid), history().first().members.map { it.uuid })
        assertEquals(emptyList<Switch?>(), corrected)
        // Three tries, half a second then a second apart.
        assertEquals(listOf(0L, 500L, 1_500L), client.fetchTimes)
    }

    @Test
    fun `a correction whose fetch failed goes out with the next fetch that works`() = runTest {
        val cases = listOf<Pair<String, suspend () -> PkResult<*>>>(
            "members fix" to { repository.setSwitchMembers(history().first().uuid, listOf(members()[4].uuid)) },
            "delete" to { repository.deleteSwitch(history().first().uuid) },
        )
        for ((what, write) in cases) {
            corrected.clear()
            client.failFetchesAfterWrite = 3
            assertTrue(what, write().isSuccess())
            client.failFetchesAfterWrite = 0
            assertEquals(what, emptyList<Switch?>(), corrected)

            repository.refreshFronters()
            repository.refreshFronters()

            assertEquals(what, listOf(client.pk.getCurrentFronters()), corrected)
        }
    }

    @Test
    fun `the fetch after a write is retried only for failures that might pass`() = runTest {
        val cases = listOf(
            PluralKitHttpException(401, "Unauthorized") to 1,
            PluralKitHttpException(429, "Too Many Requests") to 1,
            PluralKitHttpException(503, "Service Unavailable") to 3,
            IOException("Network unreachable") to 3,
        )
        for ((failure, fetches) in cases) {
            val latest = history().first()
            client.fronterFetchError = failure
            val before = client.fronterFetches

            val other = members().first { m -> latest.members.none { it.uuid == m.uuid } }
            assertTrue(repository.setSwitchMembers(latest.uuid, listOf(other.uuid)).isSuccess())

            assertEquals(failure.toString(), fetches, client.fronterFetches - before)
            client.fronterFetchError = null
        }
    }

    @Test
    fun `a write still lands, and is followed up, when the screen goes away while it's sent`() = runTest {
        val latest = history().first()
        val member = members()[0]
        repository.refreshMembers()
        val cases = listOf<Pair<String, suspend () -> Unit>>(
            "members fix" to { repository.setSwitchMembers(latest.uuid, listOf(members()[4].uuid)) },
            "member edit" to { repository.updateMember(member.uuid, mapOf(MemberField.Name to "Renamed")) },
        )
        for ((what, write) in cases) {
            val hold = CompletableDeferred<Unit>()
            client.holdNextWrite = hold
            val caller = launch { write() }
            runCurrent()

            caller.cancel()
            hold.complete(Unit)
            advanceUntilIdle()
        }

        assertEquals(listOf(members()[4].uuid), corrected.single()!!.members.map { it.uuid })
        assertEquals("Renamed", (repository.refreshMembers() as PkResult.Success).value.first { it.uuid == member.uuid }.name)
    }

    @Test
    fun `closing the repository stops writes still under way, so nothing is announced for them`() = runTest {
        val latest = history().first()
        client.failFetchesAfterWrite = 1
        launch { repository.setSwitchMembers(latest.uuid, listOf(members()[4].uuid)) }
        runCurrent()

        repository.close()
        advanceUntilIdle()
        client.failFetchesAfterWrite = 0
        repository.refreshFronters()

        assertEquals(emptyList<Switch?>(), corrected)
    }

    @Test
    fun `an unchanged member list is refused when it's the first try, and nobody is told`() = runTest {
        val latest = history().first()

        val result = repository.setSwitchMembers(latest.uuid, latest.members.map { it.uuid })

        assertEquals(40004, ((result as PkResult.Failure).error as PluralKitHttpException).code)
        assertEquals(emptyList<Switch?>(), corrected)
    }

    @Test
    fun `a delete says whether it deleted the switch or found it already gone`() = runTest {
        val (latest, older, oldest) = history()
        client.pk.deleteSwitch(oldest.uuid)
        client.loseNextResponse = true
        repository.deleteSwitch(older.uuid)

        assertEquals("deleted elsewhere", Deletion.AlreadyGone, (repository.deleteSwitch(oldest.uuid) as PkResult.Success).value)
        assertEquals("our lost try", Deletion.Done, (repository.deleteSwitch(older.uuid) as PkResult.Success).value)
        assertEquals("a first try", Deletion.Done, (repository.deleteSwitch(latest.uuid) as PkResult.Success).value)
    }

    @Test
    fun `a 404 that isn't PluralKit saying the switch is gone is a failure`() = runTest {
        val latest = history().first()
        client.refuseNextWrite = PluralKitHttpException(404, "Not Found")

        val result = repository.deleteSwitch(latest.uuid)

        assertEquals(404, ((result as PkResult.Failure).error as PluralKitHttpException).statusCode)
        assertEquals(latest, history().first())
    }

    @Test
    fun `a write that worked still corrects the front if the screen goes away while it's fetched`() = runTest {
        val latest = history().first()
        client.fronterFetchFailures = 1
        val write = launch { repository.setSwitchMembers(latest.uuid, listOf(members()[4].uuid)) }
        runCurrent()

        write.cancel()
        advanceUntilIdle()

        assertEquals(listOf(members()[4].uuid), corrected.single()!!.members.map { it.uuid })
    }

    @Test
    fun `a write whose response was lost may have landed, so a fetch from before it is set aside`() =
        runTest(UnconfinedTestDispatcher()) {
            val latest = history().first()
            val fix = listOf(members()[4].uuid)
            val hold = CompletableDeferred<Unit>()
            client.holdFronterFetch = hold
            launch { repository.refreshFronters() }

            client.loseNextResponse = true
            assertTrue(repository.setSwitchMembers(latest.uuid, fix) is PkResult.Failure)
            hold.complete(Unit)

            assertEquals(fix, repository.currentFronters.value!!.members.map { it.uuid })
        }

    @Test
    fun `a switch registered while a fetch was out isn't overwritten by it`() = runTest(UnconfinedTestDispatcher()) {
        val hold = CompletableDeferred<Unit>()
        client.holdFronterFetch = hold
        launch { repository.refreshFronters() }

        val registered = (repository.registerSwitch(listOf(members()[5].uuid)) as PkResult.Success).value
        hold.complete(Unit)

        assertEquals(registered, repository.currentFronters.value)
    }

    @Test
    fun `a write PluralKit refuses for any other reason is a failure, and nothing follows it`() = runTest {
        val latest = history().first()
        val cases = listOf<Pair<String, suspend () -> PkResult<*>>>(
            "member edit" to { repository.updateMember(members()[0].uuid, mapOf(MemberField.Name to "X")) },
            "move" to { repository.moveSwitch(latest.uuid, latest.timestamp.minusSeconds(30)) },
            "members fix" to { repository.setSwitchMembers(latest.uuid, listOf(members()[4].uuid)) },
            "delete" to { repository.deleteSwitch(latest.uuid) },
        )
        for (status in listOf(401, 500)) for ((what, call) in cases) {
            client.refuseNextWrite = PluralKitHttpException(status, "Refused")
            val fetchesBefore = client.fronterFetches

            val result = call()

            assertEquals("$what, $status", status, ((result as PkResult.Failure).error as PluralKitHttpException).statusCode)
            // A delete asks for the front first; nothing fetches after a failed write.
            assertEquals("$what, $status", if (what == "delete") 1 else 0, client.fronterFetches - fetchesBefore)
        }
        assertEquals(latest, history().first())
        assertEquals(emptyList<Switch?>(), corrected)
    }

    @Test
    fun `a fetch that started before a write can't show what the write replaced`() = runTest(UnconfinedTestDispatcher()) {
        val latest = history().first()
        val fix = listOf(members()[4].uuid)
        val hold = CompletableDeferred<Unit>()
        client.holdFronterFetch = hold

        launch { repository.refreshFronters() }
        repository.setSwitchMembers(latest.uuid, fix)
        hold.complete(Unit)

        assertEquals(fix, repository.currentFronters.value!!.members.map { it.uuid })
        assertEquals(fix, repository.loadedFronters.value!!.switch!!.members.map { it.uuid })
    }

    @Test
    fun `a fronters fetch gives up waiting for writes to stop, after three tries`() = runTest {
        var writing = false
        client.duringFronterFetch = {
            // An edit lands during every fetch this test makes (not those the edit itself makes).
            if (!writing) {
                writing = true
                repository.updateMember(members()[0].uuid, mapOf(MemberField.Pronouns to "try ${client.fronterFetches}"))
                writing = false
            }
        }

        val result = repository.refreshFronters()

        assertEquals(client.pk.getCurrentFronters(), (result as PkResult.Success).value)
        assertEquals("three tries, each with an edit and its own fetch", 6, client.fronterFetches)
    }

    @Test
    fun `forcing a member refresh fetches again`() = runTest {
        val first = (repository.refreshMembers() as PkResult.Success).value

        val forced = (repository.refreshMembers(force = true) as PkResult.Success).value

        assertTrue(first !== forced)
    }

    @Test
    fun `a member list fetched before an edit can't be cached over it`() = runTest(UnconfinedTestDispatcher()) {
        val member = members().first()
        val hold = CompletableDeferred<Unit>()
        client.holdMemberFetch = hold

        launch { repository.refreshMembers(force = true) }
        repository.updateMember(member.uuid, mapOf(MemberField.Name to "Renamed"))
        hold.complete(Unit)

        val cached = (repository.refreshMembers() as PkResult.Success).value
        assertEquals("Renamed", cached.first { it.uuid == member.uuid }.name)
    }
}
