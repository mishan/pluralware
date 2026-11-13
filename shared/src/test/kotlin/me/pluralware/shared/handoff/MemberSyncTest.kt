package me.pluralware.shared.handoff

import com.google.android.gms.wearable.DataMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import me.pluralware.shared.api.PluralKitClient
import me.pluralware.shared.api.PluralKitToken
import me.pluralware.shared.mock.MockPluralKitClient
import me.pluralware.shared.model.Member
import me.pluralware.shared.model.MemberField
import me.pluralware.shared.model.Switch
import me.pluralware.shared.repository.PluralKitRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class MemberSyncTest {

    private val token = PluralKitToken("token")

    @Test
    fun `a notice round-trips through a DataMap, and an incomplete one reads as nothing`() {
        val notice = MemberSync.noticeOf(token, atEpochMillis = 1_234L)
        val map = DataMap().also { MemberSync.write(notice, it) }

        assertEquals(notice, MemberSync.read(map))
        assertNull(MemberSync.read(DataMap()))
        assertNull(MemberSync.read(DataMap().apply { putString("system", token.fingerprint) }))
    }

    @Test
    fun `the notice names the system without the token`() {
        assertFalse(MemberSync.noticeOf(PluralKitToken("secret-token-value"), 1L).tokenFingerprint.contains("secret"))
    }

    /** PluralKit, counting member list and fronter fetches. */
    private class CountingClient(val pk: MockPluralKitClient = MockPluralKitClient(artificialLatencyMillis = 0)) :
        PluralKitClient by pk {
        var memberFetches = 0
        var fronterFetches = 0
        var forgets = 0
        var holdForget: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        override suspend fun getOwnMembers(): List<Member> = pk.getOwnMembers().also { memberFetches++ }
        override suspend fun getCurrentFronters(): Switch? = pk.getCurrentFronters().also { fronterFetches++ }
        override suspend fun forgetMembers() {
            forgets++
            holdForget?.also { holdForget = null }?.await()
        }
    }

    @Test
    fun `an open app tells the other device each time a member edit may have landed`() = runTest(UnconfinedTestDispatcher()) {
        val client = CountingClient()
        val repository = PluralKitRepository(client)
        var pushes = 0
        val sync = launch { MemberSync.keepInSync(repository, token, { pushes++ }, MemberSync.Progress(), MutableStateFlow(null)) }
        val member = client.pk.getOwnMembers().first()

        repository.updateMember(member.uuid, mapOf(MemberField.Name to "A"))
        repository.updateMember(member.uuid, mapOf(MemberField.Name to "B"))
        repository.updateMember("no-such-member", mapOf(MemberField.Name to "C"))

        assertEquals("two landed, one refused; none on starting", 2, pushes)
        sync.cancel()
    }

    @Test
    fun `an edit on the other device drops this one's member caches and refreshes it`() = runTest(UnconfinedTestDispatcher()) {
        val client = CountingClient()
        val repository = PluralKitRepository(client)
        repository.refreshMembers()
        val notices = MutableStateFlow<MemberSync.Notice?>(null)
        val sync = launch { MemberSync.keepInSync(repository, token, {}, MemberSync.Progress(), notices) }
        // Renamed on the phone.
        val member = client.pk.getOwnMembers().first()
        client.pk.updateMember(member.uuid, mapOf(MemberField.Name to "Renamed"))
        val fetchesBefore = client.fronterFetches

        notices.value = (MemberSync.noticeOf(PluralKitToken("another system"), 1L))
        assertEquals("another system's edit is ignored", fetchesBefore, client.fronterFetches)

        notices.value = (MemberSync.noticeOf(token, 2L))

        assertEquals(1, client.forgets)
        assertEquals(fetchesBefore + 1, client.fronterFetches)
        val members = (repository.refreshMembers() as me.pluralware.shared.repository.PkResult.Success).value
        assertEquals("Renamed", members.first { it.uuid == member.uuid }.name)
        sync.cancel()
    }

    @Test
    fun `an edit from the other device isn't echoed back`() = runTest(UnconfinedTestDispatcher()) {
        val repository = PluralKitRepository(CountingClient())
        val notices = MutableStateFlow<MemberSync.Notice?>(null)
        var pushes = 0
        val sync = launch { MemberSync.keepInSync(repository, token, { pushes++ }, MemberSync.Progress(), notices) }

        notices.value = (MemberSync.noticeOf(token, 1L))

        assertEquals(0, pushes)
        sync.cancel()
    }

    @Test
    fun `a notice that came while the app was in the background is acted on when it comes back, once`() =
        runTest(UnconfinedTestDispatcher()) {
            val client = CountingClient()
            val repository = PluralKitRepository(client)
            val progress = MemberSync.Progress()
            val notices = MutableStateFlow<MemberSync.Notice?>(null)
            launch { MemberSync.keepInSync(repository, token, {}, progress, notices) }.cancel()

            // The watch's screen is off: nothing is collecting when the phone's notice lands.
            notices.value = MemberSync.noticeOf(token, 5L)
            val resumed = launch { MemberSync.keepInSync(repository, token, {}, progress, notices) }
            resumed.cancel()
            launch { MemberSync.keepInSync(repository, token, {}, progress, notices) }.cancel()

            assertEquals(1, client.forgets)
        }

    @Test
    fun `a catch-up cut short by the app going to the background is done again when it comes back`() =
        runTest(UnconfinedTestDispatcher()) {
            val client = CountingClient()
            val repository = PluralKitRepository(client)
            val progress = MemberSync.Progress()
            val notices = MutableStateFlow<MemberSync.Notice?>(MemberSync.noticeOf(token, 5L))
            client.holdForget = kotlinx.coroutines.CompletableDeferred()
            val first = launch { MemberSync.keepInSync(repository, token, {}, progress, notices) }

            first.cancel()
            launch { MemberSync.keepInSync(repository, token, {}, progress, notices) }.cancel()

            assertEquals("tried, cut short, then done", 2, client.forgets)
        }

    @Test
    fun `an edit that landed while the app was in the background is told when it comes back, once`() =
        runTest(UnconfinedTestDispatcher()) {
            val client = CountingClient()
            val repository = PluralKitRepository(client)
            val progress = MemberSync.Progress()
            var pushes = 0
            launch { MemberSync.keepInSync(repository, token, { pushes++ }, progress, MutableStateFlow(null)) }.cancel()

            // The save finished after the user left the app.
            repository.updateMember(client.pk.getOwnMembers().first().uuid, mapOf(MemberField.Name to "Renamed"))
            launch { MemberSync.keepInSync(repository, token, { pushes++ }, progress, MutableStateFlow(null)) }.cancel()
            launch { MemberSync.keepInSync(repository, token, { pushes++ }, progress, MutableStateFlow(null)) }.cancel()

            assertEquals(1, pushes)
        }
}
