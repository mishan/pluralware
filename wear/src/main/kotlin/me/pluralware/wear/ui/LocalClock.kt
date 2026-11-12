package me.pluralware.wear.ui

import androidx.compose.runtime.staticCompositionLocalOf
import java.time.Clock

/**
 * The clock the screens tell time by: how long ago a switch was, how long
 * someone has fronted, and in which time zone. The system's, unless a
 * caller provides another: the README's screenshots provide a fixed one, so
 * what they show doesn't change with the day they're taken.
 */
val LocalClock = staticCompositionLocalOf<Clock> { Clock.systemDefaultZone() }
