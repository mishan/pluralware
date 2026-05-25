package me.pluralwatch.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material.MaterialTheme

@Composable
fun PluralWatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = PluralWatchColors,
        typography = PluralWatchTypography,
        content = content,
    )
}
