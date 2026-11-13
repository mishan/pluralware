package me.pluralware.mobile.ui.members

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import me.pluralware.mobile.ui.components.BackTopBar
import me.pluralware.mobile.ui.components.ErrorView
import me.pluralware.mobile.ui.components.LoadingView
import me.pluralware.mobile.ui.components.MemberLine
import me.pluralware.mobile.ui.components.MessageView
import me.pluralware.mobile.ui.components.SEARCH_FROM
import me.pluralware.mobile.ui.components.matches
import me.pluralware.shared.model.Member
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.ui.state.UiState

/** The system's members; tap one to edit it. */
@Composable
fun MembersScreen(
    repository: PluralKitRepository,
    onBack: () -> Unit,
    onOpenMember: (String) -> Unit,
    onSignOut: () -> Unit,
    vm: MembersViewModel = viewModel(factory = MembersViewModel.Factory(repository)),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val reloadError by vm.reloadError.collectAsStateWithLifecycle()
    MembersContent(state, reloadError, onBack, onOpenMember, onRetry = vm::load, onSignOut)
}

@Composable
internal fun MembersContent(
    state: UiState<List<Member>>,
    reloadError: String?,
    onBack: () -> Unit,
    onOpenMember: (String) -> Unit,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
) {
    Scaffold(topBar = { BackTopBar("Members", onBack = onBack) }) { padding ->
        val modifier = Modifier.padding(padding)
        when (state) {
            UiState.Loading -> LoadingView(modifier)
            is UiState.Error -> ErrorView(state, onRetry = onRetry, onSignOut = onSignOut, modifier = modifier)
            is UiState.Content ->
                if (state.value.isEmpty()) {
                    MessageView("No members yet", "Add members in PluralKit, then they'll show here.", modifier)
                } else {
                    MemberDirectory(state.value, reloadError, onOpenMember, onRetry, modifier)
                }
        }
    }
}

@Composable
private fun MemberDirectory(
    members: List<Member>,
    reloadError: String?,
    onOpenMember: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val searchable = members.size > SEARCH_FROM
    // A list that shrank below where search starts takes the search away; its filter goes too,
    // or it would hide members with nothing on screen to clear it.
    LaunchedEffect(searchable) { if (!searchable) query = "" }
    val shown = if (query.isBlank()) members else members.filter { it.matches(query.trim()) }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (reloadError != null) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Couldn't refresh: $reloadError",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onRetry) { Text("Retry") }
                }
            }
        }
        if (searchable) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().testTag("member-search"),
                    placeholder = { Text("Search members") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                )
            }
        }
        items(shown, key = { it.uuid }) { member ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            ) {
                MemberLine(
                    member = member,
                    secondary = member.pronouns,
                    modifier = Modifier
                        .clickable(role = Role.Button, onClickLabel = "Edit") { onOpenMember(member.uuid) }
                        .padding(horizontal = 12.dp, vertical = 10.dp)
                        .fillMaxWidth(),
                )
            }
        }
        if (shown.isEmpty()) {
            item {
                Text(
                    text = "No members match “${query.trim()}”.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
    }
}
