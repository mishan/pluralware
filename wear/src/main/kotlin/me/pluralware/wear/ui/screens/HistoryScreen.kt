package me.pluralware.wear.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListAnchorType
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import me.pluralware.shared.model.Switch
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.wear.ui.LocalClock
import me.pluralware.wear.ui.components.EmptyScreen
import me.pluralware.wear.ui.components.ErrorScreen
import me.pluralware.wear.ui.components.LoadingScreen
import me.pluralware.wear.ui.components.MemberBadge
import me.pluralware.shared.ui.relativeTo
import me.pluralware.shared.ui.state.UiState
import me.pluralware.shared.ui.viewmodel.HistoryViewModel

@Composable
fun HistoryScreen(
    repository: PluralKitRepository,
    onSignOut: () -> Unit,
) {
    val vm: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory(repository))
    val state by vm.state.collectAsState()

    when (val s = state) {
        UiState.Loading -> LoadingScreen()
        is UiState.Error -> ErrorScreen(s, onRetry = vm::load, onSignOut = onSignOut)
        is UiState.Content -> {
            if (s.value.isEmpty()) {
                EmptyScreen(title = "No switches recorded yet")
            } else {
                HistoryList(switches = s.value)
            }
        }
    }
}

@Composable
private fun HistoryList(switches: List<Switch>) {
    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 8.dp),
        state = listState,
        anchorType = ScalingLazyListAnchorType.ItemCenter,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text(
                text = "Recent switches",
                style = MaterialTheme.typography.title3,
                color = MaterialTheme.colors.onBackground,
                textAlign = TextAlign.Center,
            )
        }

        // Skip the most recent (it's the current state, already shown on home).
        items(switches.drop(1), key = { it.uuid }) { switch ->
            SwitchRow(switch = switch)
        }
    }
}

/**
 * Not a Chip: history is for looking back, so rows don't react to taps, and a
 * Chip clips its contents, which cuts the descenders off the time below the
 * badges. This grows to fit them.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SwitchRow(switch: Switch) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(MaterialTheme.colors.secondaryVariant)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (switch.isSwitchOut) {
            Text(
                text = "Switched out",
                style = MaterialTheme.typography.button,
                color = MaterialTheme.colors.onSurfaceVariant,
            )
        } else {
            // Wrap badges across as many lines as needed — long names
            // and >2 fronters would otherwise clip on small round watches.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                switch.members.forEach { m ->
                    MemberBadge(member = m)
                }
            }
        }
        Text(
            text = switch.timestamp.relativeTo(LocalClock.current.instant()),
            style = MaterialTheme.typography.caption2,
            color = MaterialTheme.colors.onSurfaceVariant,
        )
    }
}
