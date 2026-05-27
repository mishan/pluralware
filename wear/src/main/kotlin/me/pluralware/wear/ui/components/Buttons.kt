package me.pluralware.wear.ui.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme

/**
 * A small pill-shaped action — used in error / empty screens where a full
 * Chip would dominate the layout.
 */
@Composable
fun CompactPillButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    CompactChip(
        onClick = onClick,
        modifier = modifier.height(28.dp),
        label = {
            androidx.wear.compose.material.Text(
                text = label,
                style = MaterialTheme.typography.button,
            )
        },
        colors = androidx.wear.compose.material.ChipDefaults.chipColors(
            backgroundColor = MaterialTheme.colors.primary,
            contentColor = MaterialTheme.colors.onPrimary,
        ),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    )
}
