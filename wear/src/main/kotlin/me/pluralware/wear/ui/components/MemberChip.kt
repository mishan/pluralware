package me.pluralware.wear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import me.pluralware.shared.model.Member
import me.pluralware.wear.ui.theme.indicatorColor

/**
 * Member chip with a coloured identity stripe on the leading edge.
 *
 * Why a stripe and not a full chip background: at typical viewing angles on a
 * watch, glanceability matters more than decoration. The stripe puts identity
 * in your peripheral vision while keeping the label on a neutral surface where
 * it stays readable across all member colours.
 */
@Composable
fun MemberChip(
    member: Member,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    showCheckmark: Boolean = false,
) {
    Chip(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = ChipDefaults.chipColors(
            backgroundColor = if (selected) {
                MaterialTheme.colors.surface
            } else {
                MaterialTheme.colors.secondaryVariant
            },
        ),
        label = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ColorStripe(color = member.indicatorColor())
                Spacer(Modifier.width(10.dp))
                Column(member = member, modifier = Modifier.weight(1f))
                if (showCheckmark && selected) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colors.primary,
                    )
                }
            }
        },
    )
}

@Composable
private fun ColorStripe(color: Color) {
    Box(
        modifier = Modifier
            .width(4.dp)
            .height(28.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color),
    )
}

@Composable
private fun Column(member: Member, modifier: Modifier) {
    androidx.compose.foundation.layout.Column(modifier = modifier) {
        Text(
            text = member.displayLabel,
            style = MaterialTheme.typography.button,
            color = MaterialTheme.colors.onSurface,
            maxLines = 1,
        )
        val pronouns = member.pronouns
        if (!pronouns.isNullOrBlank()) {
            Text(
                text = pronouns,
                style = MaterialTheme.typography.caption2,
                color = MaterialTheme.colors.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/** Compact static badge form — used in the recent switches summary, not a button. */
@Composable
fun MemberBadge(member: Member, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(member.indicatorColor()),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = member.displayLabel,
            style = MaterialTheme.typography.caption1,
            color = MaterialTheme.colors.onSurface,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}
