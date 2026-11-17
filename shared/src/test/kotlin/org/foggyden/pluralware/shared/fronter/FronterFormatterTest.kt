package org.foggyden.pluralware.shared.fronter

import org.foggyden.pluralware.shared.model.Member
import org.foggyden.pluralware.shared.model.Switch
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class FronterFormatterTest {

    @Test
    fun `null switch reads as no switches yet`() {
        assertEquals("No switches yet", FronterFormatter.body(null))
        assertEquals("No registered switches", FronterFormatter.contentDescription(null))
    }

    @Test
    fun `switch-out reads as switched out`() {
        val switch = switchOf()
        assertEquals("Switched out", FronterFormatter.body(switch))
        assertEquals(
            "Switched out — nobody fronting",
            FronterFormatter.contentDescription(switch),
        )
    }

    @Test
    fun `single fronter is named`() {
        val switch = switchOf(member("Alex"))
        assertEquals("Fronting: Alex", FronterFormatter.body(switch))
        assertEquals("Current fronter: Alex", FronterFormatter.contentDescription(switch))
    }

    @Test
    fun `two fronters are both listed`() {
        val switch = switchOf(member("Alex"), member("Bea"))
        assertEquals("Fronting: Alex, Bea", FronterFormatter.body(switch))
    }

    @Test
    fun `more than two fronters collapse to plus-N in body but not description`() {
        val switch = switchOf(member("Alex"), member("Bea"), member("Cam"), member("Dani"))
        // Body caps at two names plus a count.
        assertEquals("Fronting: Alex, Bea +2", FronterFormatter.body(switch))
        // Accessibility text spells everyone out.
        assertEquals(
            "Current fronter: Alex, Bea, Cam, Dani",
            FronterFormatter.contentDescription(switch),
        )
    }

    @Test
    fun `front order is preserved, never sorted`() {
        val switch = switchOf(member("Zara"), member("Alex"))
        assertEquals("Fronting: Zara, Alex", FronterFormatter.body(switch))
    }

    @Test
    fun `displayName wins over name`() {
        val switch = switchOf(member(name = "Alexander", displayName = "Alex"))
        assertEquals("Fronting: Alex", FronterFormatter.body(switch))
    }

    private fun switchOf(vararg members: Member) =
        Switch(uuid = "switch-uuid", timestamp = Instant.EPOCH, members = members.toList())

    private fun member(name: String, displayName: String? = null) = Member(
        id = name.take(5).lowercase(),
        uuid = "uuid-$name",
        name = name,
        displayName = displayName,
        pronouns = null,
        color = null,
        avatarUrl = null,
    )
}
