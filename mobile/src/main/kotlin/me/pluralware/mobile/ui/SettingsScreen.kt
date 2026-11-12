package me.pluralware.mobile.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.pluralware.mobile.ui.components.BackTopBar
import me.pluralware.mobile.ui.components.WideOutlinedButton
import me.pluralware.mobile.watch.WatchState
import me.pluralware.shared.model.SystemInfo
import me.pluralware.shared.settings.RefreshInterval

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onBack: () -> Unit,
    onOpenSharing: () -> Unit,
    onOpenFollowing: () -> Unit,
) {
    val system by viewModel.system.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val watch by viewModel.watch.collectAsState()
    val signingOut by viewModel.signingOut.collectAsState()
    SettingsContent(
        system = system,
        refreshInterval = settings.refreshInterval,
        watch = watch,
        signingOut = signingOut,
        onBack = onBack,
        onDisconnect = viewModel::disconnect,
        onSendToWatch = viewModel::sendToWatch,
        onSelectInterval = viewModel::setRefreshInterval,
        onOpenSharing = onOpenSharing,
        onOpenFollowing = onOpenFollowing,
    )
}

@Composable
internal fun SettingsContent(
    system: SystemInfo?,
    refreshInterval: RefreshInterval,
    watch: WatchState,
    signingOut: Boolean,
    onBack: () -> Unit,
    onDisconnect: () -> Unit,
    onSendToWatch: () -> Unit,
    onSelectInterval: (RefreshInterval) -> Unit,
    onOpenSharing: () -> Unit,
    onOpenFollowing: () -> Unit,
) {
    var confirmingDisconnect by rememberSaveable { mutableStateOf(false) }
    Scaffold(topBar = { BackTopBar("Settings", onBack = onBack) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SettingsCard("Account") {
                Text(
                    text = system?.let { "Connected as ${it.displayLabel()}" } ?: "Connected to PluralKit",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = "To use a different PluralKit token, disconnect first.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    OutlinedButton(onClick = { confirmingDisconnect = true }, enabled = !signingOut) {
                        if (signingOut) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.size(8.dp))
                            Text("Disconnecting…")
                        } else {
                            Text("Disconnect")
                        }
                    }
                }
            }

            WatchCard(watch, onSendToWatch)

            SettingsCard("Refresh while open") {
                Text(
                    text = "How often PluralWare checks PluralKit for fronter changes while it's open, " +
                        "here and on a watch. It always checks when you open it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                RefreshInterval.entries.forEach { interval ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = interval == refreshInterval,
                                role = Role.RadioButton,
                                onClick = { onSelectInterval(interval) },
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = interval == refreshInterval, onClick = null)
                        Text(
                            text = interval.label(),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 12.dp, top = 10.dp, bottom = 10.dp),
                        )
                    }
                }
            }

            SettingsCard("Friends") {
                Text(
                    text = "Let friends know when you switch, or follow systems who share with you.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                WideOutlinedButton("Share with friends", onClick = onOpenSharing)
                WideOutlinedButton("Following", onClick = onOpenFollowing)
            }
        }
    }

    if (confirmingDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmingDisconnect = false },
            title = { Text("Disconnect?") },
            text = {
                Text(
                    "PluralWare forgets your token on this phone and any watch, along with who you share " +
                        "switches with. Your PluralKit account isn't changed.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDisconnect = false
                    onDisconnect()
                }) { Text("Disconnect") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDisconnect = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * The watch, when this phone could have one. A phone without Wear OS shows
 * nothing here: the app is complete without a watch.
 */
@Composable
private fun WatchCard(watch: WatchState, onSend: () -> Unit) {
    val (message, action) = when (watch) {
        WatchState.Checking, WatchState.Unsupported -> return
        is WatchState.NotConnected -> if (watch.queued) {
            "No watch is connected right now. A paired watch gets your token when it reconnects." to "Send again"
        } else {
            "No watch is connected. Everything works on this phone; if you pair a Wear OS watch, " +
                "send it your token here." to "Send to watch"
        }
        is WatchState.Connected -> "${watch.watchName} is connected." to "Send to watch"
        WatchState.Sending -> "Sending to your watch…" to null
        is WatchState.Sent -> "Sent to ${watch.watchName}." to "Resend"
        is WatchState.Failed -> watch.message to "Try again"
    }
    SettingsCard("Watch") {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = if (watch is WatchState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (action != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onSend) { Text(action) }
            }
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

private fun RefreshInterval.label(): String = when (this) {
    RefreshInterval.OFF -> "Off"
    RefreshInterval.SEC_30 -> "Every 30 seconds"
    RefreshInterval.MIN_1 -> "Every minute"
    RefreshInterval.MIN_5 -> "Every 5 minutes"
}

private fun SystemInfo.displayLabel(): String =
    name?.takeIf { it.isNotBlank() }
        ?: tag?.takeIf { it.isNotBlank() }
        ?: id
