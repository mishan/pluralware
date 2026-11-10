package me.pluralware.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import me.pluralware.mobile.ui.components.ErrorView
import me.pluralware.mobile.ui.components.LoadingView
import me.pluralware.mobile.ui.components.MemberLine
import me.pluralware.mobile.ui.components.MessageView
import me.pluralware.mobile.ui.components.WideOutlinedButton
import me.pluralware.shared.model.Member
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.settings.SettingsStore
import me.pluralware.shared.ui.elapsedSince
import me.pluralware.shared.ui.relativeTo
import me.pluralware.shared.ui.state.FronterStreak
import me.pluralware.shared.ui.state.FrontersState
import me.pluralware.shared.ui.state.UiState
import me.pluralware.shared.ui.viewmodel.FrontersViewModel

/** Home: who's fronting now, and the way to change it. */
@Composable
fun FrontersScreen(
    repository: PluralKitRepository,
    settingsStore: SettingsStore,
    onChangeFronters: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onSignOut: () -> Unit,
) {
    val vm: FrontersViewModel = viewModel(factory = FrontersViewModel.Factory(repository, settingsStore))
    val state by vm.state.collectAsState()
    val intervalSeconds by vm.refreshIntervalSeconds.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // As on the watch: refresh whenever the screen comes into view, then poll
    // on the configured interval while it stays there. repeatOnLifecycle
    // cancels the loop in the background, so nothing polls unseen.
    LaunchedEffect(lifecycleOwner, intervalSeconds) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            vm.poll()
            val seconds = intervalSeconds
            if (seconds > 0) {
                while (isActive) {
                    delay(seconds * 1000L)
                    vm.poll()
                }
            }
        }
    }

    FrontersContent(
        state = state,
        onRefresh = vm::refresh,
        onRetry = vm::load,
        onChangeFronters = onChangeFronters,
        onOpenHistory = onOpenHistory,
        onOpenSettings = onOpenSettings,
        onSignOut = onSignOut,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FrontersContent(
    state: UiState<FrontersState>,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onChangeFronters: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    onSignOut: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("PluralWare") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (state) {
            UiState.Loading -> LoadingView(modifier)
            is UiState.Error -> ErrorView(state, onRetry = onRetry, onSignOut = onSignOut, modifier = modifier)
            is UiState.Content -> {
                val switch = state.value.switch
                if (switch == null) {
                    // A system that has never registered a switch (PluralKit's 204).
                    MessageView(
                        title = "No switches yet",
                        body = "Log your first switch to see who's fronting here.",
                        modifier = modifier,
                    ) {
                        Button(onClick = onChangeFronters) { Text("Log a switch") }
                    }
                    return@Scaffold
                }
                val listState = rememberLazyListState()
                // After a new switch, here or anywhere else, start from the top.
                LaunchedEffect(switch.uuid) { listState.animateScrollToItem(0) }
                PullToRefreshBox(
                    isRefreshing = state.value.refreshing,
                    onRefresh = onRefresh,
                    modifier = modifier.fillMaxSize(),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        item {
                            Text(
                                text = if (switch.isSwitchOut) "Nobody's fronting" else "Fronting now",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                text = (if (switch.isSwitchOut) "Switched out " else "Since ") +
                                    switch.timestamp.relativeTo(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        items(switch.members, key = { it.uuid }) { member ->
                            FronterCard(member, state.value.streaks[member.uuid])
                        }
                        item {
                            Button(
                                onClick = onChangeFronters,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(top = 8.dp),
                            ) {
                                Text(if (switch.isSwitchOut) "Switch in" else "Change fronters")
                            }
                        }
                        item { WideOutlinedButton("Recent switches", onClick = onOpenHistory) }
                    }
                }
            }
        }
    }
}

/**
 * One fronter, with how long they've been fronting across switches: a
 * co-fronter who stayed through the last switch keeps their longer streak.
 * ">" marks a streak older than the history we fetched.
 */
@Composable
private fun FronterCard(member: Member, streak: FronterStreak?) {
    val fronting = streak?.let { "fronting for " + (if (it.truncated) ">" else "") + it.since.elapsedSince() }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        MemberLine(
            member = member,
            secondary = listOfNotNull(member.pronouns?.takeIf { it.isNotBlank() }, fronting).joinToString(" · "),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}
