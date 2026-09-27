package me.pluralware.shared.notify

import java.time.Instant
import me.pluralware.shared.model.Member
import me.pluralware.shared.model.Switch
import org.junit.Assert.assertEquals
import org.junit.Test

class SwitchAnnouncementTest {

    private fun member(name: String, displayName: String? = null) =
        Member(id = name, uuid = "uuid-$name", name = name, displayName = displayName, pronouns = null, color = null, avatarUrl = null)

    private val alex = member("alex", displayName = "Alex")
    private val bea = member("bea", displayName = "Bea")
    private val cy = member("cy", displayName = "Cy")
    private val hidden = member("hidden")
    private val hidden2 = member("hidden2")

    private fun text(vararg members: Member, shared: Set<Member> = setOf(alex, bea, cy)) =
        SwitchAnnouncement.text(Switch("s", Instant.EPOCH, members.toList()), shared.mapTo(HashSet()) { it.uuid })

    @Test fun `switch-out`() = assertEquals("Switched out", text())
    @Test fun `one named member`() = assertEquals("Alex is fronting", text(alex))
    @Test fun `two named members`() = assertEquals("Alex and Bea are fronting", text(alex, bea))
    @Test fun `three named members take a serial comma`() = assertEquals("Alex, Bea, and Cy are fronting", text(alex, bea, cy))
    @Test fun `front order is kept`() = assertEquals("Bea and Alex are fronting", text(bea, alex))
    @Test fun `display labels are used`() = assertEquals("Alex is fronting", text(alex))

    @Test fun `unshared members read as someone else`() =
        assertEquals("Alex and someone else are fronting", text(alex, hidden))

    @Test fun `how many are hidden is not revealed`() =
        assertEquals("Alex and someone else are fronting", text(alex, hidden, hidden2))

    @Test fun `with nobody named, someone is fronting`() =
        assertEquals("Someone is fronting", text(hidden, hidden2))

    @Test fun `nothing shared names nobody`() =
        assertEquals("Someone is fronting", text(alex, bea, shared = emptySet()))

    @Test fun `payload carries title, text and switch time`() {
        val switch = Switch("s", Instant.parse("2026-09-27T14:02:00Z"), listOf(alex))
        val payload = SwitchPayload.of("Sample", switch, setOf(alex.uuid))
        assertEquals(SwitchPayload("Sample", "Alex is fronting", "2026-09-27T14:02:00Z"), SwitchPayload.fromBytes(payload.toBytes()))
        assertEquals(switch.timestamp, payload.switchedAtInstant)
    }
}
