package me.pluralware.wear.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.wear.ui.components.ErrorScreen
import me.pluralware.wear.ui.components.LoadingScreen
import me.pluralware.wear.ui.components.MemberChip
import me.pluralware.wear.ui.state.PickerState
import me.pluralware.wear.ui.state.UiState
import me.pluralware.wear.ui.viewmodel.PickerViewModel

@Composable
fun MemberPickerScreen(
    repository: PluralKitRepository,
    onDone: () -> Unit,
) {
    val vm: PickerViewModel = viewModel(factory = PickerViewModel.Factory(repository))
    val state by vm.state.collectAsState()

    LaunchedEffect(Unit) {
        vm.doneEvents.collect { onDone() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (val members = state.members) {
            UiState.Loading -> LoadingScreen()
            is UiState.Error -> ErrorScreen(message = members.message, onRetry = vm::loadMembers)
            is UiState.Content -> PickerContent(
                state = state,
                onToggle = vm::toggle,
                onConfirm = vm::submitSelection,
                onDeselectAll = vm::deselectAll,
            )
        }

        // Confirmation overlay — fades in for ~700ms after a successful switch.
        AnimatedVisibility(
            visible = state.confirmed,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            ConfirmedOverlay()
        }
    }
}

@Composable
private fun PickerContent(
    state: PickerState,
    onToggle: (String) -> Unit,
    onConfirm: () -> Unit,
    onDeselectAll: () -> Unit,
) {
    val members = (state.members as UiState.Content).value
    val listState = rememberScalingLazyListState()
    val selectionCount = state.selectedUuids.size
    val anySelected = selectionCount > 0

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
                text = "Who's fronting?",
                style = MaterialTheme.typography.title3,
                color = MaterialTheme.colors.onBackground,
                textAlign = TextAlign.Center,
            )
        }

        items(members, key = { it.uuid }) { member ->
            val position = state.selectedUuids.indexOf(member.uuid)
            val isSelected = position >= 0
            MemberChip(
                member = member,
                selected = isSelected,
                selectionNumber = if (isSelected) position + 1 else null,
                // "proxy" only carries meaning with co-fronting; a lone fronter
                // is trivially the proxy, so don't clutter the chip with the tag.
                isProxy = position == 0 && selectionCount > 1,
                onClick = { onToggle(member.uuid) },
            )
        }

        item { Spacer(Modifier.height(4.dp)) }

        // Primary action. Confirming with nothing selected registers a switch-out
        // (nobody fronting) — that's how you switch out now that the dedicated
        // button is gone, so it stays enabled even when the selection is empty.
        item {
            Chip(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.submitting,
                label = {
                    Text(
                        text = when {
                            state.submitting -> "Saving…"
                            anySelected -> "Confirm switch"
                            else -> "Switch out"
                        },
                        style = MaterialTheme.typography.button,
                    )
                },
                colors = ChipDefaults.primaryChipColors(),
            )
        }

        item {
            Chip(
                onClick = onDeselectAll,
                modifier = Modifier.fillMaxWidth(),
                enabled = anySelected && !state.submitting,
                label = {
                    Text(
                        text = "Deselect all",
                        style = MaterialTheme.typography.button,
                    )
                },
                colors = ChipDefaults.secondaryChipColors(),
            )
        }
    }
}

@Composable
private fun ConfirmedOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "Switched ✓",
            style = MaterialTheme.typography.title2,
            color = MaterialTheme.colors.primary,
            textAlign = TextAlign.Center,
        )
    }
}
