package me.pluralware.wear.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListAnchorType
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import me.pluralware.shared.model.Member
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.settings.SettingsStore
import me.pluralware.wear.ui.components.EmptyScreen
import me.pluralware.wear.ui.components.ErrorScreen
import me.pluralware.wear.ui.components.LoadingScreen
import me.pluralware.wear.ui.components.MemberChip
import me.pluralware.wear.ui.elapsedSince
import me.pluralware.wear.ui.relativeTo
import me.pluralware.wear.ui.state.FronterStreak
import me.pluralware.wear.ui.state.FrontersState
import me.pluralware.wear.ui.state.UiState
import me.pluralware.wear.ui.viewmodel.FrontersViewModel

@Composable
fun FrontersScreen(
    repository: PluralKitRepository,
    settingsStore: SettingsStore,
    onChangeFronter: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val vm: FrontersViewModel = viewModel(
        factory = FrontersViewModel.Factory(repository, settingsStore),
    )
    val state by vm.state.collectAsState()
    val intervalSeconds by vm.refreshIntervalSeconds.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Refresh whenever the screen becomes visible (screen wake / app foreground /
    // returning to this destination), then poll on the configured interval while
    // visible. repeatOnLifecycle cancels the loop when the screen sleeps, so we
    // never touch the network in the background.
    LaunchedEffect(lifecycleOwner, intervalSeconds) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.refresh()
            val seconds = intervalSeconds
            if (seconds > 0) {
                while (isActive) {
                    delay(seconds * 1000L)
                    vm.refresh()
                }
            }
        }
    }

    when (val s = state) {
        UiState.Loading -> LoadingScreen()
        is UiState.Error -> ErrorScreen(message = s.message, onRetry = vm::load)
        is UiState.Content -> FrontersContent(
            state = s.value,
            onRefresh = vm::refresh,
            onChangeFronter = onChangeFronter,
            onOpenHistory = onOpenHistory,
        )
    }
}

@Composable
private fun FrontersContent(
    state: FrontersState,
    onRefresh: () -> Unit,
    onChangeFronter: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val switch = state.switch
    // No registered switches at all on this system — special-case onboarding feel.
    if (switch == null) {
        EmptyScreen(
            title = "No switches yet",
            body = "Tap below to log your first switch.",
            actionLabel = "Switch in",
            onAction = onChangeFronter,
        )
        return
    }

    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        state = listState,
        anchorType = ScalingLazyListAnchorType.ItemCenter,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (switch.isSwitchOut) "Nobody fronting" else "Currently fronting",
                    style = MaterialTheme.typography.caption1,
                    color = MaterialTheme.colors.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.width(6.dp))
                RefreshControl(refreshing = state.refreshing, onClick = onRefresh)
            }
        }

        if (switch.isSwitchOut) {
            item {
                Text(
                    text = "Switched out " + switch.timestamp.relativeTo(),
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onBackground,
                    textAlign = TextAlign.Center,
                )
            }
        } else {
            items(switch.members, key = { it.uuid }) { member ->
                val streak = state.streaks[member.uuid]
                    ?: FronterStreak(since = switch.timestamp, truncated = false)
                CyclingFronterChip(member = member, streak = streak)
            }
            item {
                Text(
                    text = "Since " + switch.timestamp.relativeTo(),
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurfaceVariant,
                )
            }
        }

        item { Spacer(Modifier.height(4.dp)) }

        item {
            Chip(
                onClick = onChangeFronter,
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(
                        text = "Change fronter",
                        style = MaterialTheme.typography.button,
                    )
                },
                colors = ChipDefaults.primaryChipColors(),
            )
        }
        item {
            Chip(
                onClick = onOpenHistory,
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(
                        text = "Recent switches",
                        style = MaterialTheme.typography.button,
                    )
                },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
    }
}

/**
 * Tappable fronter chip that cycles its secondary line between the member's
 * pronouns (default) and "Fronting for Xh".
 *
 * On the home screen we used to navigate to the picker on chip tap, but the
 * "Change fronter" pill below already does that — the chip is more useful
 * as an info surface. Cycle resets when the member or their streak changes.
 *
 * [streak] is this member's continuous-fronting streak across switches —
 * not the start of the current switch. A co-fronter who was already in the
 * previous switch shows their longer streak rather than being reset by a
 * new fronter joining. When [FronterStreak.truncated] is true (their streak
 * predates our history window), the duration is prefixed with ">".
 */
@Composable
private fun CyclingFronterChip(member: Member, streak: FronterStreak) {
    val durationPrefix = if (streak.truncated) ">" else ""
    // null in slot 0 = let MemberChip use its default (pronouns) fallback so
    // members without pronouns show name-only on the default view.
    val slots: List<String?> = listOf(
        null,
        "Fronting for $durationPrefix${streak.since.elapsedSince()}",
    )
    var index by remember(member.uuid, streak) { mutableIntStateOf(0) }
    MemberChip(
        member = member,
        secondaryLabel = slots[index],
        onClick = { index = (index + 1) % slots.size },
    )
}

/**
 * Manual refresh affordance in the home header. Swaps to a spinner (and stops
 * accepting taps) while a refresh is in flight.
 */
@Composable
private fun RefreshControl(refreshing: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(CircleShape)
            .clickable(enabled = !refreshing, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (refreshing) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                indicatorColor = MaterialTheme.colors.primary,
                trackColor = MaterialTheme.colors.surface,
            )
        } else {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Refresh",
                tint = MaterialTheme.colors.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
