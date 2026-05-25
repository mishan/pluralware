package me.pluralwatch.wear.ui

import java.time.Duration
import java.time.Instant

/**
 * Pluralkit's switch timestamps benefit from a glanceable relative format on watch screens.
 *
 * - <1 min  → "just now"
 * - <1 hour → "12m ago"
 * - <1 day  → "3h ago"
 * - <1 week → "2d ago"
 * - else    → "3w ago"
 *
 * Months and years aren't useful contexts for fronting history on a watch;
 * if a user wants to look that far back they'll use the dashboard.
 */
fun Instant.relativeTo(now: Instant = Instant.now()): String {
    val d = Duration.between(this, now)
    return when {
        d.isNegative -> "soon"
        d.toMinutes() < 1 -> "just now"
        d.toHours() < 1 -> "${d.toMinutes()}m ago"
        d.toDays() < 1 -> "${d.toHours()}h ago"
        d.toDays() < 7 -> "${d.toDays()}d ago"
        else -> "${d.toDays() / 7}w ago"
    }
}
