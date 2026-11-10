package me.pluralware.shared.ui

import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Test

class RelativeTimeTest {

    private val now = Instant.parse("2026-01-15T12:00:00Z")
    private fun ago(d: Duration) = now.minus(d)

    @Test
    fun `relative times bucket by minute, hour, day and week`() {
        assertEquals("just now", ago(Duration.ofSeconds(59)).relativeTo(now))
        assertEquals("1m ago", ago(Duration.ofMinutes(1)).relativeTo(now))
        assertEquals("59m ago", ago(Duration.ofMinutes(59)).relativeTo(now))
        assertEquals("1h ago", ago(Duration.ofHours(1)).relativeTo(now))
        assertEquals("23h ago", ago(Duration.ofHours(23)).relativeTo(now))
        assertEquals("1d ago", ago(Duration.ofDays(1)).relativeTo(now))
        assertEquals("6d ago", ago(Duration.ofDays(6)).relativeTo(now))
        assertEquals("1w ago", ago(Duration.ofDays(7)).relativeTo(now))
        assertEquals("5w ago", ago(Duration.ofDays(40)).relativeTo(now))
    }

    @Test
    fun `a time in the future reads as soon`() {
        assertEquals("soon", now.plusSeconds(60).relativeTo(now))
    }

    @Test
    fun `elapsed times use the same buckets without the ago`() {
        assertEquals("<1m", ago(Duration.ofSeconds(30)).elapsedSince(now))
        assertEquals("<1m", now.plusSeconds(60).elapsedSince(now))
        assertEquals("45m", ago(Duration.ofMinutes(45)).elapsedSince(now))
        assertEquals("3h", ago(Duration.ofHours(3)).elapsedSince(now))
        assertEquals("2d", ago(Duration.ofDays(2)).elapsedSince(now))
        assertEquals("2w", ago(Duration.ofDays(14)).elapsedSince(now))
    }
}
