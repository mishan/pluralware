package me.pluralware.mobile.ui.screens

import java.time.Instant
import java.time.ZoneOffset
import me.pluralware.shared.model.Member
import me.pluralware.shared.model.Switch
import org.junit.Assert.assertEquals
import org.junit.Test

class SwitchFixTest {

    private fun member(name: String) = Member(name.lowercase(), "uuid-$name", name, null, null, null, null)
    private val sam = member("Sam")
    private val jo = member("Jo")
    private val kit = member("Kit")
    private val now = Instant.parse("2026-01-15T18:00:00Z")
    // Newer JDKs put a narrow no-break space before AM/PM.
    private fun String.spaced() = replace('\u202F', ' ')
    private fun at(time: String) = Instant.parse("2026-01-15T${time}:00Z")

    // Newest first, as PluralKit lists them.
    private val switches = listOf(
        Switch("s1", at("16:00"), listOf(jo)),
        Switch("s2", at("14:40"), listOf(sam)),
        Switch("s3", at("12:00"), emptyList()),
        Switch("s4", Instant.parse("2026-01-14T09:15:00Z"), listOf(sam, jo, kit)),
    )

    @Test
    fun `a switch can move only between the switches either side of it`() {
        val cases = listOf(
            Triple("the latest, just after the one before", 0, at("14:41")) to true,
            Triple("the latest, up to now", 0, now.minusSeconds(1)) to true,
            Triple("the latest, not into the future", 0, now.plusSeconds(60)) to false,
            Triple("not onto the switch before", 1, at("12:00")) to false,
            Triple("not before the switch before", 1, at("11:00")) to false,
            Triple("not onto the switch after", 1, at("16:00")) to false,
            Triple("anywhere in between", 1, at("15:59")) to true,
            Triple("the oldest known, any time before the next", 3, Instant.parse("2020-01-01T00:00:00Z")) to true,
        )
        for ((case, allowed) in cases) {
            val (what, index, to) = case
            assertEquals(what, allowed, SwitchWindow.of(switches, index).allows(to, now))
        }
    }

    @Test
    fun `deleting says what the front becomes`() {
        val kitAt3 = Switch("k", at("15:00"), listOf(kit))
        val cases = listOf(
            Triple("the latest hands the front back", switches, 0) to "Front goes back to Sam, since 2:40 PM",
            Triple("the latest, back to a switch-out", switches.drop(1), 0) to "Front goes back to nobody, since 12:00 PM",
            Triple("the only switch", switches.take(1), 0) to "Nobody will be fronting",
            Triple("an older one lets the one before it run on", listOf(switches[0], kitAt3, switches[1]), 1) to
                "Sam's front will run on until 4:00 PM",
            Triple("several members run on", switches, 2) to "Sam, Jo, and Kit's front will run on until 2:40 PM",
            Triple("after a switch-out", switches, 1) to "Nobody will be fronting until 4:00 PM",
            Triple("the oldest", switches, 3) to "Nobody will be fronting until 12:00 PM",
        )
        for ((case, expected) in cases) {
            val (what, list, index) = case
            assertEquals(what, expected, deleteOutcome(list, index, now, ZoneOffset.UTC).spaced())
        }
    }

    @Test
    fun `a time on another day says which day`() {
        val older = listOf(Switch("s1", at("16:00"), listOf(jo)), switches[3])

        assertEquals(
            "Front goes back to Sam, Jo, and Kit, since Jan 14, 2026, 9:15 AM",
            deleteOutcome(older, 0, now, ZoneOffset.UTC).spaced(),
        )
    }
}
