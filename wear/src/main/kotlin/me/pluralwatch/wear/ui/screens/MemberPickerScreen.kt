package me.pluralwatch.wear.ui.screens

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
import me.pluralwatch.shared.repository.PluralKitRepository
import me.pluralwatch.wear.ui.components.ErrorScreen
import me.pluralwatch.wear.ui.components.LoadingScreen
import me.pluralwatch.wear.ui.components.MemberChip
import me.pluralwatch.wear.ui.state.PickerState
import me.pluralwatch.wear.ui.state.UiState
import me.pluralwatch.wear.ui.viewmodel.PickerViewModel

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
                onSwitchOut = vm::submitSwitchOut,
            )
        }

        // Confirmation overlay — fades in for ~700ms after a successful switch.
        AnimatedVisibility(
            visible = state.confirmed != null,
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
    onSwitchOut: () -> Unit,
) {
    val members = (state.members as UiState.Content).value
    val listState = rememberScalingLazyListState()
    val anySelected = state.selectedUuids.isNotEmpty()

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
            val isSelected = member.uuid in state.selectedUuids
            MemberChip(
                member = member,
                selected = isSelected,
                showCheckmark = true,
                onClick = { onToggle(member.uuid) },
            )
        }

        item { Spacer(Modifier.height(4.dp)) }

        // Primary action — disabled-looking when nothing's selected, but always tappable
        // (still a valid path: confirm with nothing selected = no-op for UX clarity).
        item {
            Chip(
                onClick = onConfirm,
                modifier = Modifier.fillMaxWidth(),
                enabled = anySelected && !state.submitting,
                label = {
                    Text(
                        text = when {
                            state.submitting -> "Saving…"
                            anySelected -> "Confirm switch"
                            else -> "Select members"
                        },
                        style = MaterialTheme.typography.button,
                    )
                },
                colors = ChipDefaults.primaryChipColors(),
            )
        }

        item {
            Chip(
                onClick = onSwitchOut,
                modifier = Modifier.fillMaxWidth(),
                enabled = !state.submitting,
                label = {
                    Text(
                        text = "Switch out",
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
