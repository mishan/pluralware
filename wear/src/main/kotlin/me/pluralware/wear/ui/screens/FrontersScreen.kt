package me.pluralware.wear.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import me.pluralware.shared.model.Switch
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.wear.ui.components.CompactPillButton
import me.pluralware.wear.ui.components.EmptyScreen
import me.pluralware.wear.ui.components.ErrorScreen
import me.pluralware.wear.ui.components.LoadingScreen
import me.pluralware.wear.ui.components.MemberChip
import me.pluralware.wear.ui.relativeTo
import me.pluralware.wear.ui.state.UiState
import me.pluralware.wear.ui.viewmodel.FrontersViewModel

@Composable
fun FrontersScreen(
    repository: PluralKitRepository,
    onChangeFronter: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val vm: FrontersViewModel = viewModel(factory = FrontersViewModel.Factory(repository))
    val state by vm.state.collectAsState()

    when (val s = state) {
        UiState.Loading -> LoadingScreen()
        is UiState.Error -> ErrorScreen(message = s.message, onRetry = vm::load)
        is UiState.Content -> FrontersContent(
            switch = s.value,
            onChangeFronter = onChangeFronter,
            onOpenHistory = onOpenHistory,
        )
    }
}

@Composable
private fun FrontersContent(
    switch: Switch?,
    onChangeFronter: () -> Unit,
    onOpenHistory: () -> Unit,
) {
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
            Text(
                text = if (switch.isSwitchOut) "Nobody fronting" else "Currently fronting",
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
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
            items(switch.members) { member ->
                MemberChip(member = member, onClick = onChangeFronter)
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
            CompactPillButton(label = "Change fronter", onClick = onChangeFronter)
        }
        item {
            CompactPillButton(label = "Recent switches", onClick = onOpenHistory)
        }
    }
}
