package me.pluralwatch.wear.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors

/**
 * Color tokens. Designed for AMOLED watch displays where true black saves power
 * and dark surfaces minimise battery drain in always-on contexts.
 *
 * Primary draws on PluralKit's own brand amber (#da9317) — warm, distinct from
 * the cool blues that dominate Material defaults. Member identity colours come
 * from each member's own colour field at the chip level; this palette is the
 * neutral stage they perform on.
 */
internal object PluralWatchTokens {
    val Amber = Color(0xFFE6A12B)        // Slightly brighter than PluralKit's brand for legibility on small screens.
    val AmberDim = Color(0xFFB07A1F)     // For pressed/secondary states.
    val DeepNight = Color(0xFF000000)    // True black for AMOLED.
    val Slate = Color(0xFF1B1D24)        // Card / chip surfaces.
    val SlateRaised = Color(0xFF272A33)
    val SoftCream = Color(0xFFF6E9D2)    // On-primary text.
    val Mist = Color(0xFFC9CBD3)         // Default on-surface body.
    val MistDim = Color(0xFF7E818C)      // Secondary labels.
    val Coral = Color(0xFFE57676)        // Errors / destructive feedback.
}

internal val PluralWatchColors: Colors = Colors(
    primary = PluralWatchTokens.Amber,
    primaryVariant = PluralWatchTokens.AmberDim,
    secondary = PluralWatchTokens.SlateRaised,
    secondaryVariant = PluralWatchTokens.Slate,
    background = PluralWatchTokens.DeepNight,
    surface = PluralWatchTokens.Slate,
    error = PluralWatchTokens.Coral,
    onPrimary = PluralWatchTokens.DeepNight,
    onSecondary = PluralWatchTokens.Mist,
    onBackground = PluralWatchTokens.Mist,
    onSurface = PluralWatchTokens.Mist,
    onSurfaceVariant = PluralWatchTokens.MistDim,
    onError = PluralWatchTokens.DeepNight,
)
