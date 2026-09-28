package me.pluralware.mobile.sharing

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import me.pluralware.shared.notify.Friend
import me.pluralware.shared.notify.RelaySettings
import me.pluralware.shared.notify.SharingConfig

/** Share switches with friends: who may be named, who receives, and how (docs/notifications-design.md). */
@Composable
fun SharingScreen(viewModel: SharingViewModel, onBack: () -> Unit) {
    val state by viewModel.state.collectAsState()
    val config by viewModel.config.collectAsState()
    val orphans by viewModel.orphanedRelays.collectAsState()
    val context = LocalContext.current

    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TextButton(onClick = onBack) { Text("← Back") }
            Text("Share with friends", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "When you switch on your watch, friends you add here get a notification. " +
                    "Only members you choose are named; anyone else in front shows as \"someone\".",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (state.system?.frontIsPrivate == true) {
                Section(title = "Your front is private in PluralKit") {
                    Text(
                        "Sharing tells the friends you add who is fronting, even though PluralKit keeps it private.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            TitleSection(config.title, onSave = viewModel::setTitle)

            Section(title = "Who may be named") {
                when {
                    state.loading -> CircularProgressIndicator()
                    state.loadError != null -> {
                        Text(state.loadError!!, color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = viewModel::load) { Text("Retry") }
                    }
                    else -> state.members.forEach { member ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = member.uuid in config.sharedMemberUuids,
                                onCheckedChange = { viewModel.toggleMember(member.uuid) },
                            )
                            Text(member.displayLabel)
                            if (member.isPrivate) {
                                Text(
                                    "  private in PluralKit",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }

            FriendsSection(config, state.goneFriendIds, onRemove = viewModel::removeFriend)

            PrivateInviteSection(
                error = state.followCodeError,
                onInvite = viewModel::invite,
                onShareInvite = { link ->
                    shareText(
                        context,
                        "Follow ${config.title}'s switches: open this link, or paste it into " +
                            "PluralWare's Following screen.\n\n$link",
                    )
                },
                onAddFollowCode = viewModel::addFollowCode,
            )

            SimpleModeSection(
                config = config,
                error = state.ntfyError,
                newTopicUrl = state.newTopicUrl,
                onSaveServer = viewModel::setNtfyServer,
                onAddFriend = viewModel::addSimpleFriend,
                onShareTopic = { shareText(context, it) },
                onDismissTopic = viewModel::dismissNewTopic,
            )

            RelaySection(
                relay = config.relay,
                configured = state.relayConfigured,
                error = state.relayError,
                removeFailed = state.relayRemoveFailed,
                onSave = viewModel::saveRelay,
                onSigningToken = viewModel::setSigningToken,
                onEnabled = viewModel::setRelayEnabled,
                onRetry = viewModel::retryRelay,
                onRemove = viewModel::removeRelay,
                onRemoveAnyway = viewModel::removeRelayAnyway,
                onShareCommand = { shareText(context, it) },
            )

            if (orphans.isNotEmpty()) {
                Section(title = "Relays still holding your settings") {
                    Text(
                        "These couldn't be reached when you let them go, so they may still have your friends' " +
                            "follow codes and keep sending. PluralWare retries each time you open this screen.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    orphans.forEach { relay ->
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text(relay.url, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = viewModel::retryOrphans) { Text("Retry") }
                            TextButton(onClick = { viewModel.forgetOrphan(relay) }) { Text("Forget") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TitleSection(title: String, onSave: (String) -> Unit) {
    var text by remember(title) { mutableStateOf(title) }
    Section(title = "Notification title") {
        Text(
            "What friends see above the text. Your system name identifies you to anyone who sees their screen.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (text != title) OutlinedButton(onClick = { onSave(text) }) { Text("Save title") }
    }
}

@Composable
private fun FriendsSection(config: SharingConfig, gone: Set<String>, onRemove: (String) -> Unit) {
    Section(title = "Friends") {
        if (config.friends.isEmpty()) {
            Text("Nobody yet. Sharing is off until you add someone.", style = MaterialTheme.typography.bodySmall)
        }
        config.friends.forEach { friend ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(friend.label, fontWeight = FontWeight.Medium)
                    val how = when (friend) {
                        is Friend.Private -> "Private (encrypted)"
                        is Friend.Simple -> "Simple (ntfy topic)"
                    }
                    Text(how, style = MaterialTheme.typography.labelSmall)
                    if (friend.id in gone) {
                        Text(
                            "Stopped receiving. Ask them for a new follow code.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
                TextButton(onClick = { onRemove(friend.id) }) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun PrivateInviteSection(
    error: String?,
    onInvite: ((String) -> Unit) -> Unit,
    onShareInvite: (String) -> Unit,
    onAddFollowCode: (String, String) -> Boolean,
) {
    var code by remember { mutableStateOf("") }
    var label by remember { mutableStateOf("") }
    var inviteLink by remember { mutableStateOf<String?>(null) }
    Section(title = "Add a friend (private)") {
        Text(
            "1. Invite them: they scan the code or open the link, in a browser or in PluralWare. " +
                "2. They send back a follow code. 3. Paste it here. Only their device can read what you send.",
            style = MaterialTheme.typography.bodySmall,
        )
        val link = inviteLink
        if (link == null) {
            Button(onClick = { onInvite { inviteLink = it } }) { Text("Invite a friend") }
        } else {
            QrCode(
                text = link,
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .align(Alignment.CenterHorizontally),
            )
            Text("Have them scan this with their phone's camera.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onShareInvite(link) }) { Text("Share link") }
                TextButton(onClick = { inviteLink = null }) { Text("Done") }
            }
        }
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("Their follow code") },
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
        )
        OutlinedTextField(
            value = label,
            onValueChange = { label = it },
            label = { Text("Name (optional)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedButton(
            onClick = { if (onAddFollowCode(code, label)) { code = ""; label = "" } },
            enabled = code.isNotBlank(),
        ) { Text("Add friend") }
    }
}

@Composable
private fun SimpleModeSection(
    config: SharingConfig,
    error: String?,
    newTopicUrl: String?,
    onSaveServer: (String, String) -> Unit,
    onAddFriend: (String) -> Unit,
    onShareTopic: (String) -> Unit,
    onDismissTopic: () -> Unit,
) {
    var url by remember(config.ntfy) { mutableStateOf(config.ntfy?.baseUrl.orEmpty()) }
    var token by remember(config.ntfy) { mutableStateOf(config.ntfy?.accessToken.orEmpty()) }
    var label by remember { mutableStateOf("") }
    Section(title = "Add a friend (simple, via ntfy)") {
        Text(
            "For friends who only have the ntfy app. Messages are readable by whoever runs the ntfy server, " +
                "so use a server you run yourself.",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("ntfy server, e.g. https://ntfy.example.org") },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            modifier = Modifier.fillMaxWidth(),
        )
        SecretField(value = token, onValueChange = { token = it }, label = "Access token (servers with accounts)")
        if (url != config.ntfy?.baseUrl.orEmpty() || token != config.ntfy?.accessToken.orEmpty()) {
            OutlinedButton(onClick = { onSaveServer(url, token) }) { Text("Save server") }
        }
        if (config.ntfy != null) {
            OutlinedTextField(
                value = label,
                onValueChange = { label = it },
                label = { Text("Friend's name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedButton(onClick = { onAddFriend(label); label = "" }) { Text("Add friend") }
        }
        if (newTopicUrl != null) {
            Text("Send them this topic to subscribe to in the ntfy app:", style = MaterialTheme.typography.bodySmall)
            Text(newTopicUrl, fontFamily = FontFamily.Monospace)
            if (config.ntfy?.accessToken != null) {
                Text(
                    "On your server, give them read access:\nntfy access <their user> ${newTopicUrl.substringAfterLast('/')} read-only",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onShareTopic(newTopicUrl) }) { Text("Share") }
                TextButton(onClick = onDismissTopic) { Text("Done") }
            }
        }
    }
}

@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

internal fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable
private fun RelaySection(
    relay: RelaySettings?,
    configured: Boolean,
    error: String?,
    onSave: (String, String) -> Unit,
    onSigningToken: (String) -> Unit,
    removeFailed: Boolean,
    onEnabled: (Boolean) -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onRemoveAnyway: () -> Unit,
    onShareCommand: (String) -> Unit,
) {
    var url by remember(relay?.url) { mutableStateOf(relay?.url.orEmpty()) }
    var secret by remember(relay?.adminSecret) { mutableStateOf(relay?.adminSecret.orEmpty()) }
    var token by remember(relay?.signingToken) { mutableStateOf(relay?.signingToken.orEmpty()) }
    Section(title = "Relay (optional)") {
        Text(
            "Without one, friends hear about switches you make on your watch. A relay you host also " +
                "catches switches made in Discord or anywhere else. Setup is in the relay's README.",
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            "PluralKit allows one webhook per system. If something else already uses it, such as a " +
                "Discord bot that announces your switches, this replaces it.",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium,
        )
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("Relay address, e.g. https://relay.example.org") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SecretField(value = secret, onValueChange = { secret = it }, label = "Its admin secret")
        if (url.isNotBlank() && secret.isNotBlank() && (url != relay?.url || secret != relay.adminSecret)) {
            OutlinedButton(onClick = { onSave(url, secret) }) { Text("Save relay") }
        }
        if (relay == null) return@Section

        val command = "pk;s webhook ${relay.webhookUrl}"
        Text("1. In a DM with PluralKit, run:", style = MaterialTheme.typography.bodySmall)
        Text(command, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { onShareCommand(command) }) { Text("Copy or share the command") }
        Text(
            "2. PluralKit shows a token and waits. Paste it here and save, then reply \"yes\" to PluralKit.",
            style = MaterialTheme.typography.bodySmall,
        )
        SecretField(value = token, onValueChange = { token = it }, label = "Token from PluralKit")
        if (token != relay.signingToken.orEmpty()) {
            OutlinedButton(onClick = { onSigningToken(token) }) { Text("Save token") }
        }
        when {
            error != null -> {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (!removeFailed) TextButton(onClick = onRetry) { Text("Retry") }
            }
            configured -> Text("The relay has your settings.", style = MaterialTheme.typography.bodySmall)
        }
        if (relay.signingToken != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = relay.enabled, onCheckedChange = onEnabled)
                Text("  Send through the relay")
            }
            Text(
                "3. Once PluralKit confirms the webhook, turn this on. The watch stops sending while it's on, " +
                    "so friends never get a switch twice.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (removeFailed) {
            TextButton(onClick = onRemove) { Text("Try removing again") }
            TextButton(onClick = onRemoveAnyway) { Text("Remove anyway (keep trying to delete from it)") }
        } else {
            TextButton(onClick = onRemove) { Text("Remove relay") }
        }
    }
}

/** A masked field for secrets: no autocorrect or suggestions, so the keyboard never learns it. */
@Composable
private fun SecretField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrectEnabled = false,
            keyboardType = KeyboardType.Password,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

