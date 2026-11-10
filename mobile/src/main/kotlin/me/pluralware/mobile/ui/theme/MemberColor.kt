package me.pluralware.mobile.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import me.pluralware.shared.model.Member

/**
 * A member's PluralKit color (6-digit hex, no `#`), or null when they have
 * none or it doesn't parse.
 */
internal fun Member.parsedColor(): Color? {
    val hex = color?.removePrefix("#")
    if (hex == null || hex.length != 6) return null
    return hex.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
}

/** The member's color, or the theme's outline color for members without one. */
@Composable
@ReadOnlyComposable
internal fun Member.identityColor(): Color = parsedColor() ?: MaterialTheme.colorScheme.outline
