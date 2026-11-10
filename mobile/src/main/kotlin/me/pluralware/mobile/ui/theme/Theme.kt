package me.pluralware.mobile.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * The phone's palette: PluralKit's amber (#da9317) as the seed, like the
 * watch, with warm neutrals instead of Material's default purple-grays.
 * Light and dark follow the system setting. Every surface tone is set, so no
 * baseline purple shows through on cards and sheets.
 */
private val LightColors = lightColorScheme(
    primary = Color(0xFF845400),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDDB5),
    onPrimaryContainer = Color(0xFF2A1800),
    secondary = Color(0xFF6F5B40),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFADEBC),
    onSecondaryContainer = Color(0xFF271904),
    tertiary = Color(0xFF51643F),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD4EABB),
    onTertiaryContainer = Color(0xFF0F2004),
    background = Color(0xFFFFF8F4),
    onBackground = Color(0xFF1F1B16),
    surface = Color(0xFFFFF8F4),
    onSurface = Color(0xFF1F1B16),
    surfaceVariant = Color(0xFFF0E0CF),
    onSurfaceVariant = Color(0xFF4F4539),
    surfaceDim = Color(0xFFE2D8D0),
    surfaceBright = Color(0xFFFFF8F4),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFCF2EA),
    surfaceContainer = Color(0xFFF6ECE4),
    surfaceContainerHigh = Color(0xFFF0E7DE),
    surfaceContainerHighest = Color(0xFFEBE1D9),
    outline = Color(0xFF817567),
    outlineVariant = Color(0xFFD3C4B4),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB955),
    onPrimary = Color(0xFF462A00),
    primaryContainer = Color(0xFF643F00),
    onPrimaryContainer = Color(0xFFFFDDB5),
    secondary = Color(0xFFDDC2A1),
    onSecondary = Color(0xFF3E2D16),
    secondaryContainer = Color(0xFF56442A),
    onSecondaryContainer = Color(0xFFFADEBC),
    tertiary = Color(0xFFB8CEA1),
    onTertiary = Color(0xFF243515),
    tertiaryContainer = Color(0xFF3A4C2A),
    onTertiaryContainer = Color(0xFFD4EABB),
    background = Color(0xFF17130E),
    onBackground = Color(0xFFEBE1D9),
    surface = Color(0xFF17130E),
    onSurface = Color(0xFFEBE1D9),
    surfaceVariant = Color(0xFF4F4539),
    onSurfaceVariant = Color(0xFFD3C4B4),
    surfaceDim = Color(0xFF17130E),
    surfaceBright = Color(0xFF3E3832),
    surfaceContainerLowest = Color(0xFF110E09),
    surfaceContainerLow = Color(0xFF1F1B16),
    surfaceContainer = Color(0xFF231F1A),
    surfaceContainerHigh = Color(0xFF2E2924),
    surfaceContainerHighest = Color(0xFF39342E),
    outline = Color(0xFF9C8F80),
    outlineVariant = Color(0xFF4F4539),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

@Composable
fun PluralWareTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
