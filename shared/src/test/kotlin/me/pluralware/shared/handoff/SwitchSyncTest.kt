package me.pluralware.shared.handoff

import com.google.android.gms.wearable.DataMap
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import me.pluralware.shared.api.PluralKitClient
import me.pluralware.shared.api.PluralKitToken
import me.pluralware.shared.model.Switch
import me.pluralware.shared.preview.PreviewData
import me.pluralware.shared.repository.PluralKitRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchSyncTest {

    private val token = PluralKitToken("token")
    private val older = Switch("older", Instant.parse("2026-01-15T12:00:00Z"), listOf(PreviewData.members[0]))
    private val newer = Switch("newer", Instant.parse("2026-01-15T13:00:00Z"), listOf(PreviewData.members[1]))

    @Test
    fun `a notice round-trips through a DataMap`() {
        val notice = SwitchSync.noticeOf(newer, token)
        val map = DataMap().also { SwitchSync.write(notice, it) }
        assertEquals(notice, SwitchSync.read(map))
    }

    @Test
    fun `a notice from an app too old to send members reads without them`() {
        val notice = SwitchSync.noticeOf(newer, token)
        val map = DataMap().also { SwitchSync.write(notice.copy(membersHash = null), it) }

        assertEquals(notice.copy(membersHash = null), SwitchSync.read(map))
        assertTrue(notice.membersHash!!.isNotEmpty())
    }

    @Test
    fun `the members hash follows the members and their order, and names nobody`() {
        val two = older.copy(members = PreviewData.members.take(2))
        val swapped = two.copy(members = two.members.reversed())
        val renamed = two.copy(members = two.members.map { it.copy(name = "Renamed") })

        assertTrue(SwitchSync.membersHash(two) != SwitchSync.membersHash(swapped))
        assertTrue(SwitchSync.membersHash(two) != SwitchSync.membersHash(older))
        assertEquals("the same members, whatever they're called", SwitchSync.membersHash(two), SwitchSync.membersHash(renamed))
        assertFalse(PreviewData.members.any { SwitchSync.membersHash(two).contains(it.name, ignoreCase = true) })
    }

    @Test
    fun `an incomplete payload reads as nothing`() {
        assertNull(SwitchSync.read(DataMap()))
        assertNull(SwitchSync.read(DataMap().apply { putString("switch", "s1") }))
    }

    @Test
    fun `a notice is news when it names a different switch for this system`() {
        val moved = newer.copy(timestamp = newer.timestamp.minusSeconds(60))
        val other = PluralKitToken("other")
        listOf(
            Case("a newer switch", newer, token, older, true),
            Case("a switch, with none known", newer, token, null, true),
            Case("an older one, as after deleting the latest", older, token, newer, true),
            Case("a moved one, with the same uuid", moved, token, newer, true),
            Case("the same switch: no echo", newer, token, newer, false),
            Case("the same switch, its members fixed", newer.copy(members = PreviewData.members.take(2)), token, newer, true),
            Case("another system's switch", newer, other, older, false),
        ).forEach { (name, switch, from, current, news) ->
            assertEquals(name, news, SwitchSync.noticeOf(switch, from).differsFrom(current, token.fingerprint))
        }
    }

    @Test
    fun `without members on either side, a notice is compared by switch and time only`() {
        val fixed = newer.copy(members = PreviewData.members.take(2))
        val fromOldApp = SwitchSync.noticeOf(fixed, token).copy(membersHash = null)

        assertFalse(fromOldApp.differsFrom(newer, token.fingerprint))
        assertFalse(SwitchSync.noticeOf(fixed, token).differsFrom(newer.uuid, newer.timestamp.toEpochMilli(), null, token.fingerprint))
    }

    private data class Case(val name: String, val switch: Switch, val from: PluralKitToken, val current: Switch?, val news: Boolean)

    @Test
    fun `the notice names the system without the token`() {
        val notice = SwitchSync.noticeOf(newer, PluralKitToken("secret-token-value"))
        assertFalse(notice.tokenFingerprint.contains("secret"))
    }

    @Test
    fun `an open app pushes each new current switch once`() = runTest(UnconfinedTestDispatcher()) {
        val client = mockk<PluralKitClient>()
        val moved = newer.copy(timestamp = newer.timestamp.plusSeconds(60))
        val fixed = moved.copy(members = PreviewData.members.take(2))
        coEvery { client.getCurrentFronters() } returnsMany listOf(older, older, newer, moved, fixed)
        val repository = PluralKitRepository(client)
        val pushed = mutableListOf<Switch>()
        val sync = launch { SwitchSync.keepInSync(repository, token, { pushed += it }, MutableSharedFlow()) }

        repeat(5) { repository.refreshFronters() }

        assertEquals(listOf(older, newer, moved, fixed), pushed)
        sync.cancel()
    }

    @Test
    fun `a failed push doesn't stop later ones`() = runTest(UnconfinedTestDispatcher()) {
        val client = mockk<PluralKitClient>()
        coEvery { client.getCurrentFronters() } returnsMany listOf(older, newer)
        val repository = PluralKitRepository(client)
        val pushed = mutableListOf<String>()
        val sync = launch {
            SwitchSync.keepInSync(repository, token, {
                if (it == older) error("no watch in reach")
                pushed += it.uuid
            }, MutableSharedFlow())
        }

        repeat(2) { repository.refreshFronters() }

        assertEquals(listOf("newer"), pushed)
        sync.cancel()
    }

    @Test
    fun `a different switch from the other device refreshes, an echo doesn't`() = runTest(UnconfinedTestDispatcher()) {
        val client = mockk<PluralKitClient>()
        coEvery { client.getCurrentFronters() } returnsMany listOf(older, newer)
        val repository = PluralKitRepository(client)
        repository.refreshFronters()
        val notices = MutableSharedFlow<SwitchSync.Notice>()
        val sync = launch { SwitchSync.keepInSync(repository, token, {}, notices) }

        notices.emit(SwitchSync.noticeOf(older, token))
        notices.emit(SwitchSync.noticeOf(newer, PluralKitToken("another system")))
        coVerify(exactly = 1) { client.getCurrentFronters() }

        notices.emit(SwitchSync.noticeOf(newer, token))
        coVerify(exactly = 2) { client.getCurrentFronters() }
        assertEquals("newer", repository.currentFronters.value?.uuid)
        sync.cancel()
    }

    @Test
    fun `two devices converge without echoing`() = runTest(UnconfinedTestDispatcher()) {
        // PluralKit's answer, as both devices see it.
        var current = older
        fun device(): PluralKitRepository {
            val client = mockk<PluralKitClient>()
            coEvery { client.getCurrentFronters() } answers { current }
            return PluralKitRepository(client)
        }
        val phone = device()
        val watch = device()
        val toWatch = MutableSharedFlow<SwitchSync.Notice>()
        val toPhone = MutableSharedFlow<SwitchSync.Notice>()
        var pushes = 0
        val syncs = listOf(
            launch { SwitchSync.keepInSync(phone, token, { pushes++; toWatch.emit(SwitchSync.noticeOf(it, token)) }, toPhone) },
            launch { SwitchSync.keepInSync(watch, token, { pushes++; toPhone.emit(SwitchSync.noticeOf(it, token)) }, toWatch) },
        )
        phone.refreshFronters()
        assertEquals(older, watch.currentFronters.value)

        current = newer
        watch.refreshFronters()

        assertEquals(newer, phone.currentFronters.value)
        // older and newer, once from each device: nothing bounced back.
        assertEquals(4, pushes)
        syncs.forEach { it.cancel() }
    }
}
