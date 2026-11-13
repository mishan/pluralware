package me.pluralware.mobile.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import me.pluralware.mobile.ui.components.BackTopBar
import me.pluralware.mobile.ui.components.ErrorView
import me.pluralware.mobile.ui.components.LoadingView
import me.pluralware.mobile.ui.components.MemberLine
import me.pluralware.mobile.ui.components.SEARCH_FROM
import me.pluralware.mobile.ui.components.matches
import me.pluralware.shared.model.Member
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.ui.state.PickerState
import me.pluralware.shared.ui.state.UiState
import me.pluralware.shared.ui.viewmodel.PickerViewModel

@Composable
fun PickerScreen(
    repository: PluralKitRepository,
    onBack: () -> Unit,
    onDone: () -> Unit,
    onSignOut: () -> Unit,
) {
    val vm: PickerViewModel = viewModel(factory = PickerViewModel.Factory(repository))
    val state by vm.state.collectAsState()
    LaunchedEffect(Unit) { vm.doneEvents.collect { onDone() } }
    PickerContent(
        state = state,
        onBack = onBack,
        onToggle = vm::toggle,
        onDeselectAll = vm::deselectAll,
        onConfirm = vm::submitSelection,
        onRetry = vm::loadMembers,
        onSignOut = onSignOut,
    )
}

@Composable
internal fun PickerContent(
    state: PickerState,
    onBack: () -> Unit,
    onToggle: (String) -> Unit,
    onDeselectAll: () -> Unit,
    onConfirm: () -> Unit,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
) {
    val members = state.members
    Scaffold(
        topBar = { BackTopBar("Who's fronting?", onBack = onBack) },
        bottomBar = {
            if (members is UiState.Content) ConfirmBar(state, onConfirm)
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (members) {
            UiState.Loading -> LoadingView(modifier)
            is UiState.Error -> ErrorView(members, onRetry = onRetry, onSignOut = onSignOut, modifier = modifier)
            is UiState.Content -> MemberList(
                members = members.value,
                selected = state.selectedUuids,
                enabled = !state.submitting && !state.confirmed,
                onToggle = onToggle,
                onDeselectAll = onDeselectAll,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun MemberList(
    members: List<Member>,
    selected: List<String>,
    enabled: Boolean,
    onToggle: (String) -> Unit,
    onDeselectAll: () -> Unit,
    modifier: Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val shown = if (query.isBlank()) members else members.filter { it.matches(query.trim()) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Only worth the room when there's a list to search.
        if (members.size > SEARCH_FROM) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Search members") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
            }
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = when (selected.size) {
                        0 -> "Nobody selected"
                        else -> "${selected.size} selected · first is primary"
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onDeselectAll, enabled = enabled && selected.isNotEmpty()) {
                    Text("Deselect all")
                }
            }
        }
        items(shown, key = { it.uuid }) { member ->
            val position = selected.indexOf(member.uuid)
            MemberOption(
                member = member,
                // 1-based, so the order being set is the order shown.
                position = if (position >= 0) position + 1 else null,
                enabled = enabled,
                onClick = { onToggle(member.uuid) },
            )
        }
        if (shown.isEmpty()) {
            item {
                Text(
                    text = if (query.isBlank()) {
                        "No members yet. Add them in PluralKit."
                    } else {
                        "No members match “${query.trim()}”."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
    }
}

@Composable
private fun MemberOption(member: Member, position: Int?, enabled: Boolean, onClick: () -> Unit) {
    val isSelected = position != null
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { selected = isSelected },
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerHigh
            },
        ),
    ) {
        MemberLine(
            member = member,
            secondary = member.pronouns,
            modifier = Modifier
                .clickable(enabled = enabled, role = Role.Checkbox, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            trailing = { PositionMark(position) },
        )
    }
}

/** The member's place in the front order, or an empty ring when unselected. */
@Composable
private fun PositionMark(position: Int?) {
    val shape = CircleShape
    Box(
        modifier = Modifier
            .size(28.dp)
            .clip(shape)
            .then(
                if (position != null) {
                    Modifier.background(MaterialTheme.colorScheme.primary)
                } else {
                    Modifier.border(BorderStroke(2.dp, MaterialTheme.colorScheme.outline), shape)
                },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (position != null) {
            Text(
                text = position.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary,
            )
        }
    }
}

/**
 * The primary action, pinned to the bottom so it's always in reach.
 * Confirming with nobody selected registers a switch-out.
 */
@Composable
private fun ConfirmBar(state: PickerState, onConfirm: () -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Button(
            onClick = onConfirm,
            enabled = !state.submitting && !state.confirmed,
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                when {
                    state.confirmed -> "Switched ✓"
                    state.submitting -> "Saving…"
                    state.selectedUuids.isNotEmpty() -> "Confirm switch"
                    else -> "Switch out"
                },
            )
        }
    }
}
