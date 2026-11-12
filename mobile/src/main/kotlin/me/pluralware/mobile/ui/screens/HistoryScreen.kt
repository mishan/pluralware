package me.pluralware.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import me.pluralware.mobile.ui.LocalClock
import me.pluralware.mobile.ui.components.BackTopBar
import me.pluralware.mobile.ui.components.ErrorView
import me.pluralware.mobile.ui.components.LoadingView
import me.pluralware.mobile.ui.components.MemberBadge
import me.pluralware.mobile.ui.components.MessageView
import me.pluralware.shared.model.Switch
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.ui.relativeTo
import me.pluralware.shared.ui.state.UiState
import me.pluralware.shared.ui.viewmodel.HistoryViewModel

/** Recent switches, to look back on. Read-only, as on the watch. */
@Composable
fun HistoryScreen(
    repository: PluralKitRepository,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
) {
    val vm: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory(repository))
    val state by vm.state.collectAsState()
    HistoryContent(state = state, onBack = onBack, onRetry = vm::load, onSignOut = onSignOut)
}

@Composable
internal fun HistoryContent(
    state: UiState<List<Switch>>,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    zone: ZoneId = LocalClock.current.zone,
) {
    Scaffold(topBar = { BackTopBar("Recent switches", onBack = onBack) }) { padding ->
        val modifier = Modifier.padding(padding)
        when (state) {
            UiState.Loading -> LoadingView(modifier)
            is UiState.Error -> ErrorView(state, onRetry = onRetry, onSignOut = onSignOut, modifier = modifier)
            is UiState.Content -> {
                // The newest switch is who's fronting now, which home already shows.
                val past = state.value.drop(1)
                if (past.isEmpty()) {
                    MessageView(title = "No earlier switches", modifier = modifier)
                } else {
                    LazyColumn(
                        modifier = modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(past, key = { it.uuid }) { SwitchCard(it, zone) }
                    }
                }
            }
        }
    }
}

private val dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SwitchCard(switch: Switch, zone: ZoneId) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (switch.isSwitchOut) {
                Text(
                    "Switched out",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    switch.members.forEach { MemberBadge(it) }
                }
            }
            Text(
                text = switch.timestamp.relativeTo(LocalClock.current.instant()) + " · " + dateTime.format(switch.timestamp.atZone(zone)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
