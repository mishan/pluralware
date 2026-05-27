package me.pluralware.wear.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListAnchorType
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import me.pluralware.shared.model.Switch
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.wear.ui.components.EmptyScreen
import me.pluralware.wear.ui.components.ErrorScreen
import me.pluralware.wear.ui.components.LoadingScreen
import me.pluralware.wear.ui.components.MemberBadge
import me.pluralware.wear.ui.relativeTo
import me.pluralware.wear.ui.state.UiState
import me.pluralware.wear.ui.viewmodel.HistoryViewModel

@Composable
fun HistoryScreen(
    repository: PluralKitRepository,
    onDone: () -> Unit,
) {
    val vm: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory(repository))
    val state by vm.state.collectAsState()

    when (val s = state) {
        UiState.Loading -> LoadingScreen()
        is UiState.Error -> ErrorScreen(message = s.message, onRetry = vm::load)
        is UiState.Content -> {
            if (s.value.isEmpty()) {
                EmptyScreen(title = "No switches recorded yet")
            } else {
                HistoryList(
                    switches = s.value,
                    onSwitchBack = { switch -> vm.switchBackTo(switch, onDone) },
                )
            }
        }
    }
}

@Composable
private fun HistoryList(
    switches: List<Switch>,
    onSwitchBack: (Switch) -> Unit,
) {
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
            SwitchRow(switch = switch, onClick = { onSwitchBack(switch) })
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SwitchRow(switch: Switch, onClick: () -> Unit) {
    Chip(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = ChipDefaults.chipColors(
            backgroundColor = MaterialTheme.colors.secondaryVariant,
        ),
        label = {
            Box(modifier = Modifier.fillMaxWidth()) {
                androidx.compose.foundation.layout.Column {
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
                        text = switch.timestamp.relativeTo(),
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurfaceVariant,
                    )
                }
            }
        },
    )
}
