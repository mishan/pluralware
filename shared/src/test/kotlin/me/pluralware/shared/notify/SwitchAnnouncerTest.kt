package me.pluralware.shared.notify

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import me.pluralware.shared.preview.PreviewData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchAnnouncerTest {

    private val kit = Friend.Simple("s1", "Kit", "pw_kit")
    private val rue = Friend.Simple("s2", "Rue", "pw_rue")
    private val config = SharingConfig(friends = listOf(kit, rue), ntfy = NtfyServer("https://ntfy.example"))
    private val switch = PreviewData.currentSwitch
    private val sharer = mockk<SwitchSharer>()
    private val now = 1_000_000L

    @Test
    fun `records friends who have gone`() = runTest {
        val store = InMemorySharingStore(config)
        coEvery { sharer.announce(any(), any(), any()) } returns
            mapOf(kit to SendOutcome.Delivered, rue to SendOutcome.Gone)

        val changed = SwitchAnnouncer.announce(store, sharer, switch, now)

        assertTrue(changed)
        assertEquals(mapOf("s2" to now), store.goneFlow.value)
    }

    @Test
    fun `a gone friend who receives again is back`() = runTest {
        val store = InMemorySharingStore(config)
        val longAgo = now - GoneFriends.RETRY_AFTER_MILLIS
        store.markGone(setOf("s2"), longAgo)
        coEvery { sharer.announce(any(), any(), any()) } returns
            mapOf(kit to SendOutcome.Delivered, rue to SendOutcome.Delivered)

        val changed = SwitchAnnouncer.announce(store, sharer, switch, now)

        assertTrue(changed)
        assertTrue(store.goneFlow.value.isEmpty())
    }

    @Test
    fun `skips friends gone within the last day`() = runTest {
        val store = InMemorySharingStore(config)
        store.markGone(setOf("s2"), now - 1)
        coEvery { sharer.announce(any(), any(), any()) } returns mapOf(kit to SendOutcome.Delivered)

        val changed = SwitchAnnouncer.announce(store, sharer, switch, now)

        assertFalse(changed)
        coVerify { sharer.announce(config, switch, setOf("s2")) }
    }

    @Test
    fun `sends nothing while the relay sends`() = runTest {
        val relay = RelaySettings(url = "https://relay.example", adminSecret = "a", webhookPath = "w", enabled = true)
        val store = InMemorySharingStore(config.copy(relay = relay))

        val changed = SwitchAnnouncer.announce(store, sharer, switch, now)

        assertFalse(changed)
        coVerify(exactly = 0) { sharer.announce(any(), any(), any()) }
    }

    @Test
    fun `a correction goes out even while the relay sends, which tells friends only of new switches`() = runTest {
        val relay = RelaySettings(url = "https://relay.example", adminSecret = "a", webhookPath = "w", enabled = true)
        val store = InMemorySharingStore(config.copy(relay = relay))
        store.markGone(setOf("s2"), now - 1)
        coEvery { sharer.announceCorrection(any(), any(), any(), any()) } returns mapOf(kit to SendOutcome.Gone)

        val changed = SwitchAnnouncer.correct(store, sharer, before = switch, front = null, now = now)

        coVerify { sharer.announceCorrection(config.copy(relay = relay), null, setOf("s2"), any()) }
        assertTrue("its gone friends are recorded too", changed)
    }

    @Test
    fun `a correction waits for an announcement still being sent, so the mistake can't land last`() = runTest {
        val store = InMemorySharingStore(config)
        val announcing = kotlinx.coroutines.CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        coEvery { sharer.announce(any(), any(), any()) } coAnswers {
            announcing.await()
            order += "announced"
            emptyMap()
        }
        coEvery { sharer.announceCorrection(any(), any(), any(), any()) } coAnswers {
            order += "corrected"
            emptyMap()
        }

        val first = launch { SwitchAnnouncer.announce(store, sharer, switch, now) }
        testScheduler.runCurrent()
        val second = launch { SwitchAnnouncer.correct(store, sharer, null, switch, now) }
        testScheduler.runCurrent()
        announcing.complete(Unit)
        first.join()
        second.join()

        assertEquals(listOf("announced", "corrected"), order)
    }

    @Test
    fun `an announcement waiting its turn isn't sent if the relay took over meanwhile`() = runTest {
        val store = InMemorySharingStore(config)
        val correcting = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { sharer.announceCorrection(any(), any(), any(), any()) } coAnswers {
            correcting.await()
            emptyMap()
        }

        val first = launch { SwitchAnnouncer.correct(store, sharer, null, switch, now) }
        testScheduler.runCurrent()
        val second = launch { SwitchAnnouncer.announce(store, sharer, switch, now) }
        testScheduler.runCurrent()
        store.set(config.copy(relay = RelaySettings(url = "https://relay.example", adminSecret = "a", webhookPath = "w", enabled = true)))
        correcting.complete(Unit)
        first.join()
        second.join()

        coVerify(exactly = 0) { sharer.announce(any(), any(), any()) }
    }

    @Test
    fun `a correction friends couldn't tell from what they were told isn't sent`() = runTest {
        val m = PreviewData.members
        fun s(vararg members: Int) = me.pluralware.shared.model.Switch("x", java.time.Instant.EPOCH, members.map { m[it] })
        val store = InMemorySharingStore(config.copy(sharedMemberUuids = setOf(m[0].uuid)))
        var sends = 0
        coEvery { sharer.announceCorrection(any(), any(), any(), any()) } answers {
            sends++
            emptyMap()
        }
        val cases = listOf(
            Triple("one hidden member for another", s(1), s(2)) to false,
            Triple("switched out either way", s(), null) to false,
            Triple("a named member joined by a hidden one", s(0), s(0, 1)) to true,
            Triple("what friends were told isn't known", null, s(1)) to true,
        )
        for ((case, sent) in cases) {
            val (what, before, after) = case
            sends = 0
            SwitchAnnouncer.correct(store, sharer, before, after, now)
            assertEquals(what, if (sent) 1 else 0, sends)
        }
    }

    @Test
    fun `skips friends the other device found gone within the last day`() = runTest {
        val store = InMemorySharingStore(config)
        store.setPeerGone(mapOf("s2" to now - 1))
        coEvery { sharer.announce(any(), any(), any()) } returns mapOf(kit to SendOutcome.Delivered)

        val changed = SwitchAnnouncer.announce(store, sharer, switch, now)

        coVerify { sharer.announce(config, switch, setOf("s2")) }
        // The other device's findings are never recorded as this one's.
        assertFalse(changed)
        assertTrue(store.goneFlow.value.isEmpty())
    }

    @Test
    fun `tries a friend the other device found gone over a day ago`() = runTest {
        val store = InMemorySharingStore(config)
        store.setPeerGone(mapOf("s2" to now - GoneFriends.RETRY_AFTER_MILLIS))
        val skipped = slot<Set<String>>()
        coEvery { sharer.announce(any(), any(), capture(skipped)) } returns
            mapOf(kit to SendOutcome.Delivered, rue to SendOutcome.Delivered)

        SwitchAnnouncer.announce(store, sharer, switch, now)

        assertEquals(emptySet<String>(), skipped.captured)
    }

    @Test
    fun `a friend found gone here again is a change to report`() = runTest {
        val store = InMemorySharingStore(config)
        store.markGone(setOf("s2"), now - GoneFriends.RETRY_AFTER_MILLIS)
        coEvery { sharer.announce(any(), any(), any()) } returns
            mapOf(kit to SendOutcome.Delivered, rue to SendOutcome.Gone)

        val changed = SwitchAnnouncer.announce(store, sharer, switch, now)

        assertTrue(changed)
        assertEquals(mapOf("s2" to now), store.goneFlow.value)
    }
}
