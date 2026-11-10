package me.pluralware.shared.notify

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
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
}
