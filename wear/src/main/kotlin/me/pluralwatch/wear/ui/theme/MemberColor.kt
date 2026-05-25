package me.pluralwatch.wear.ui.theme

import androidx.compose.ui.graphics.Color
import me.pluralwatch.shared.model.Member

/**
 * Parse a PluralKit colour string (6-char hex, no `#`) into a Compose Color.
 * Falls back to [fallback] for null / malformed input.
 */
internal fun parseMemberColor(hex: String?, fallback: Color = PluralWatchTokens.Amber): Color {
    if (hex.isNullOrBlank()) return fallback
    val cleaned = hex.removePrefix("#")
    if (cleaned.length != 6) return fallback
    return runCatching {
        Color(android.graphics.Color.parseColor("#$cleaned"))
    }.getOrDefault(fallback)
}

/**
 * Indicator colour for a member chip — slightly translucent so multiple
 * members reading at once stay legible against the dark background.
 */
internal fun Member.indicatorColor(): Color =
    parseMemberColor(color).copy(alpha = 0.85f)
