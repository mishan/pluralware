package me.pluralware.mobile.ui.members

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.flow.filterNotNull
import me.pluralware.mobile.ui.components.BackTopBar
import me.pluralware.mobile.ui.components.ErrorView
import me.pluralware.mobile.ui.components.LoadingView
import me.pluralware.mobile.ui.components.MemberLine
import me.pluralware.mobile.ui.components.MessageView
import me.pluralware.mobile.ui.components.SEARCH_FROM
import me.pluralware.mobile.ui.components.matches
import me.pluralware.shared.model.Member
import me.pluralware.shared.model.MemberField
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
    val addState by vm.addState.collectAsStateWithLifecycle()
    val openMember by rememberUpdatedState(onOpenMember)
    val lifecycle = LocalLifecycleOwner.current
    LaunchedEffect(vm, lifecycle) {
        // Only while the screen shows, which navigating needs: one added meanwhile waits.
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            vm.toOpen.filterNotNull().collect {
                openMember(it)
                vm.opened()
            }
        }
    }
    MembersContent(
        state, reloadError, onBack, onOpenMember, onRetry = vm::load, onSignOut,
        addState = addState, onStartAdd = vm::startAdd, onAdd = vm::add, onAddDismissed = vm::addDismissed,
    )
}

@Composable
internal fun MembersContent(
    state: UiState<List<Member>>,
    reloadError: String?,
    onBack: () -> Unit,
    onOpenMember: (String) -> Unit,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    addState: AddState = AddState(),
    onStartAdd: () -> Unit = {},
    onAdd: (String) -> Unit = {},
    onAddDismissed: () -> Unit = {},
) {
    Scaffold(
        topBar = { BackTopBar("Members", onBack = onBack) },
        floatingActionButton = {
            if (state is UiState.Content) {
                ExtendedFloatingActionButton(
                    onClick = onStartAdd,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Add member") },
                )
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (state) {
            UiState.Loading -> LoadingView(modifier)
            is UiState.Error -> ErrorView(state, onRetry = onRetry, onSignOut = onSignOut, modifier = modifier)
            is UiState.Content ->
                if (state.value.isEmpty()) {
                    MessageView("No members yet", "Add one with Add member, or in PluralKit.", modifier)
                } else {
                    MemberDirectory(state.value, reloadError, onOpenMember, onRetry, modifier)
                }
        }
    }
    if (addState.open) AddMemberDialog(addState, onAdd, onAddDismissed)
}

/** Asks only for a name: the editor, opened once the member exists, does the rest. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddMemberDialog(state: AddState, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    BasicAlertDialog(onDismissRequest = { if (!state.adding) onDismiss() }) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) { AddMemberForm(state, onAdd, onDismiss) }
    }
}

// Apart from its dialog so tests can show it: Robolectric never settles with a text field in a dialog window.
@Composable
internal fun AddMemberForm(state: AddState, onAdd: (String) -> Unit, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    val tooLong = name.trim().length > MemberField.Name.maxLength!!
    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Add member", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            enabled = !state.adding,
            isError = tooLong,
            supportingText = if (tooLong) { { Text("Up to ${MemberField.Name.maxLength} characters.") } } else null,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
            modifier = Modifier.fillMaxWidth().testTag("new-member-name"),
        )
        state.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        Row(modifier = Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDismiss, enabled = !state.adding) { Text("Cancel") }
            TextButton(onClick = { onAdd(name) }, enabled = name.isNotBlank() && !tooLong && !state.adding) {
                Text(if (state.adding) "Adding…" else "Create")
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
        // Room at the end for the Add member button (56dp, inset 16dp), so the last row scrolls clear of it.
        contentPadding = PaddingValues(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (reloadError != null) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Couldn't refresh: $reloadError",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
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
