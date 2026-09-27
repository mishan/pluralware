package me.pluralware.shared.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharingConfigTest {

    @Test
    fun `a config with both kinds of friend round-trips through JSON`() {
        val config = SharingConfig(
            title = "Sample",
            sharedMemberUuids = setOf("uuid-alex"),
            friends = listOf(
                Friend.Private("1", "Sam", FollowCode("Sam", "https://push.example/a", "p", "a")),
                Friend.Simple("2", "Kit", "pw_abc"),
            ),
            vapid = VapidKeys("pub", "priv"),
            ntfy = NtfyServer("https://ntfy.example.org", "tk_x"),
        )
        assertEquals(config, SharingConfig.fromJson(config.toJson()))
    }

    @Test
    fun `topics are pw_ plus twenty lowercase alphanumerics, and don't repeat`() {
        val topics = List(200) { NtfyTopics.generate() }
        topics.forEach { assertTrue(it, Regex("pw_[a-z0-9]{20}").matches(it)) }
        assertEquals(topics.size, topics.toSet().size)
    }

    @Test
    fun `topic URLs join cleanly`() {
        assertEquals("https://ntfy.example.org/pw_x", NtfyTopics.url(NtfyServer("https://ntfy.example.org/"), "pw_x"))
    }

    @Test
    fun `the store forgets gone marks for friends the phone removed`() = kotlinx.coroutines.test.runTest {
        val sam = Friend.Simple("1", "Sam", "pw_a")
        val kit = Friend.Simple("2", "Kit", "pw_b")
        val store = InMemorySharingStore(SharingConfig(friends = listOf(sam, kit)))
        store.markGone(setOf("1", "2"))

        store.set(SharingConfig(friends = listOf(kit)))

        assertEquals(setOf("2"), store.goneFlow.value)
    }
}
