package me.pluralware.wear.ui.theme

import androidx.compose.runtime.Composable
import androidx.wear.compose.material.MaterialTheme

@Composable
fun PluralWareTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colors = PluralWareColors,
        typography = PluralWareTypography,
        content = content,
    )
}
