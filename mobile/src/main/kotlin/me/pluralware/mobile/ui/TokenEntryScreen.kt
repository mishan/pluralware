package me.pluralware.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import me.pluralware.shared.model.SystemInfo
import me.pluralware.shared.settings.RefreshInterval

@Composable
fun TokenEntryScreen(viewModel: TokenEntryViewModel) {
    val state by viewModel.state.collectAsState()
    val settings by viewModel.settings.collectAsState()

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "PluralWare",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Connect your PluralKit account so your watch can read and update your fronters.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            HowToFindTokenCard()

            OutlinedTextField(
                value = state.input,
                onValueChange = viewModel::onInputChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("PluralKit token") },
                placeholder = { Text("Paste the output of `pk;token`") },
                singleLine = true,
                enabled = state.status !is ConnectionStatus.Validating,
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    autoCorrectEnabled = false,
                ),
                isError = state.status is ConnectionStatus.Error,
            )

            Button(
                onClick = viewModel::connect,
                modifier = Modifier.fillMaxWidth(),
                enabled = state.input.isNotBlank() &&
                    state.status !is ConnectionStatus.Validating,
            ) {
                if (state.status is ConnectionStatus.Validating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("  Connecting…")
                } else {
                    Text("Connect & send to watch")
                }
            }

            StatusSection(
                status = state.status,
                onDisconnect = viewModel::disconnect,
                onResend = viewModel::resendToWatch,
            )

            RefreshSettingsCard(
                selected = settings.refreshInterval,
                onSelect = viewModel::setRefreshInterval,
            )
        }
    }
}

@Composable
private fun RefreshSettingsCard(
    selected: RefreshInterval,
    onSelect: (RefreshInterval) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = "Watch refresh",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "How often your watch checks PluralKit for fronter changes while the app is open. It always refreshes when you open it and has a manual refresh button.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            RefreshInterval.entries.forEach { interval ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSelect(interval) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = interval == selected,
                        onClick = { onSelect(interval) },
                    )
                    Text(
                        text = interval.label(),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

private fun RefreshInterval.label(): String = when (this) {
    RefreshInterval.OFF -> "Off"
    RefreshInterval.SEC_30 -> "Every 30 seconds"
    RefreshInterval.MIN_1 -> "Every minute"
    RefreshInterval.MIN_5 -> "Every 5 minutes"
}

@Composable
private fun HowToFindTokenCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Where to find your token",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "In any channel a PluralKit bot can see, send `pk;token`. PluralKit will DM you a token. Paste it below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "The token is stored encrypted on this phone and sent to your paired watch over the Wearable Data Layer.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun StatusSection(
    status: ConnectionStatus,
    onDisconnect: () -> Unit,
    onResend: () -> Unit,
) {
    when (status) {
        ConnectionStatus.Idle, ConnectionStatus.Validating -> Unit
        is ConnectionStatus.Connected -> ConnectedCard(
            state = status,
            onDisconnect = onDisconnect,
            onResend = onResend,
        )
        is ConnectionStatus.Error -> Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
            ),
        ) {
            Text(
                text = status.message,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun ConnectedCard(
    state: ConnectionStatus.Connected,
    onDisconnect: () -> Unit,
    onResend: () -> Unit,
) {
    val title = when {
        state.system != null -> "Connected as ${state.system.displayLabel()}"
        else -> "Token saved"
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            PushStateText(state.push)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                if (state.push !is PushState.Pushing) {
                    TextButton(onClick = onResend) {
                        Text(if (state.push is PushState.Pushed) "Resend to watch" else "Send to watch")
                    }
                }
                OutlinedButton(onClick = onDisconnect) { Text("Disconnect") }
            }
        }
    }
}

@Composable
private fun PushStateText(push: PushState) {
    val (text, isError) = when (push) {
        PushState.Idle -> "Saved on this phone. Tap Send to push to your watch." to false
        PushState.Pushing -> "Sending to watch…" to false
        PushState.Pushed -> "Sent to your watch." to false
        is PushState.Failed -> push.message to true
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error
        else MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

private fun SystemInfo.displayLabel(): String =
    name?.takeIf { it.isNotBlank() }
        ?: tag?.takeIf { it.isNotBlank() }
        ?: id
