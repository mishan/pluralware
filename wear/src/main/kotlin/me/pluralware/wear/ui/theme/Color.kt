package me.pluralware.wear.ui.theme

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
internal object PluralWareTokens {
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

internal val PluralWareColors: Colors = Colors(
    primary = PluralWareTokens.Amber,
    primaryVariant = PluralWareTokens.AmberDim,
    secondary = PluralWareTokens.SlateRaised,
    secondaryVariant = PluralWareTokens.Slate,
    background = PluralWareTokens.DeepNight,
    surface = PluralWareTokens.Slate,
    error = PluralWareTokens.Coral,
    onPrimary = PluralWareTokens.DeepNight,
    onSecondary = PluralWareTokens.Mist,
    onBackground = PluralWareTokens.Mist,
    onSurface = PluralWareTokens.Mist,
    onSurfaceVariant = PluralWareTokens.MistDim,
    onError = PluralWareTokens.DeepNight,
)
