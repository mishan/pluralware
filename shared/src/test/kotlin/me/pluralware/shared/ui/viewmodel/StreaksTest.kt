package me.pluralware.shared.ui.viewmodel

import java.time.Instant
import me.pluralware.shared.model.Switch
import me.pluralware.shared.preview.PreviewData
import me.pluralware.shared.ui.state.FronterStreak
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreaksTest {

    private val a = PreviewData.members[0]
    private val b = PreviewData.members[1]
    private val c = PreviewData.members[2]
    private fun at(hour: Int) = Instant.parse("2026-01-15T00:00:00Z").plusSeconds(hour * 3600L)

    private val now = Switch("s4", at(4), listOf(a, b))
    private val history = listOf(
        now,
        Switch("s3", at(3), listOf(a, c)),
        Switch("s2", at(2), listOf(a)),
        Switch("s1", at(1), listOf(b)),
    )

    @Test
    fun `a fronter's streak runs back to the first switch of their unbroken run`() {
        val streaks = computeStreaks(now, history, historyCapped = false)

        assertEquals(FronterStreak(at(2)), streaks[a.uuid])
    }

    @Test
    fun `a fronter who just joined starts at the current switch, despite an older run`() {
        val streaks = computeStreaks(now, history, historyCapped = false)

        assertEquals(FronterStreak(at(4)), streaks[b.uuid])
    }

    @Test
    fun `a run reaching the end of capped history is marked truncated`() {
        val streaks = computeStreaks(now, history.take(3), historyCapped = true)

        assertEquals(FronterStreak(at(2), truncated = true), streaks[a.uuid])
        assertEquals(FronterStreak(at(4)), streaks[b.uuid])
    }

    @Test
    fun `reaching the end of history that isn't capped is the real start`() {
        val streaks = computeStreaks(now, history.take(3), historyCapped = false)

        assertEquals(FronterStreak(at(2)), streaks[a.uuid])
    }

    @Test
    fun `without the current switch in history, every streak starts now`() {
        val streaks = computeStreaks(now, history.drop(1), historyCapped = false)

        assertEquals(FronterStreak(at(4)), streaks[a.uuid])
        assertEquals(FronterStreak(at(4)), streaks[b.uuid])
    }

    @Test
    fun `no switch and a switch-out have no streaks`() {
        assertTrue(computeStreaks(null, history, historyCapped = false).isEmpty())
        val out = Switch("out", at(5), emptyList())
        assertTrue(computeStreaks(out, listOf(out) + history, historyCapped = false).isEmpty())
    }
}
