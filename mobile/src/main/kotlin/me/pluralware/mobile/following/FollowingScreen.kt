package me.pluralware.mobile.following

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import me.pluralware.mobile.sharing.Section
import me.pluralware.mobile.sharing.shareText
import me.pluralware.shared.notify.Follow

/** Systems this person follows, and accepting a new invite (docs/notifications-design.md §4.4). */
@Composable
fun FollowingScreen(viewModel: FollowingViewModel, onBack: () -> Unit) {
    val follows by viewModel.follows.collectAsState()
    val inviteError by viewModel.inviteError.collectAsState()
    val context = LocalContext.current
    val activity = context as Activity
    var invite by remember { mutableStateOf("") }
    var myName by remember { mutableStateOf("") }

    val askForNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            askForNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

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
            Text("Following", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Get a notification when a system you follow switches. Messages are end-to-end encrypted: " +
                    "only this phone can read them.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Section(title = "Follow a system") {
                OutlinedTextField(
                    value = invite,
                    onValueChange = { invite = it },
                    label = { Text("Paste their invite") },
                    isError = inviteError != null,
                    supportingText = inviteError?.let { { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
                )
                OutlinedTextField(
                    value = myName,
                    onValueChange = { myName = it },
                    label = { Text("Your name, as they'll see it") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = invite.isNotBlank(),
                    onClick = {
                        ensureNotificationPermission()
                        if (viewModel.follow(activity, invite, myName)) {
                            invite = ""
                            myName = ""
                        }
                    },
                ) { Text("Follow") }
            }

            follows.forEach { follow ->
                FollowCard(
                    follow = follow,
                    onShareCode = { code ->
                        shareText(
                            context,
                            "Here's my PluralWare follow code. Paste it under Share with friends:\n\n$code",
                        )
                    },
                    onRetry = { viewModel.retry(activity, follow) },
                    onUnfollow = { viewModel.unfollow(follow) },
                )
            }
        }
    }
}

@Composable
private fun FollowCard(
    follow: Follow,
    onShareCode: (String) -> Unit,
    onRetry: () -> Unit,
    onUnfollow: () -> Unit,
) {
    Section(title = follow.system) {
        when {
            follow.problem != null -> {
                Text(follow.problem!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onRetry) { Text("Try again") }
            }
            follow.followCode == null -> Text("Registering with your push distributor…", style = MaterialTheme.typography.bodySmall)
            follow.lastText == null -> {
                Text(
                    "Send them your follow code to finish. It works like a password for sending you " +
                        "notifications, so send it only to them.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = { onShareCode(follow.followCode!!) }) { Text("Send follow code") }
            }
            else -> {
                Text(follow.lastText!!, style = MaterialTheme.typography.bodyLarge)
                follow.lastSwitchedAt?.let { at ->
                    runCatching { Instant.parse(at) }.getOrNull()?.let {
                        Text(
                            "Since " + TIME.format(it.atZone(ZoneId.systemDefault())),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (follow.followCode != null && follow.lastText != null) {
                TextButton(onClick = { onShareCode(follow.followCode!!) }) { Text("Resend follow code") }
            }
            TextButton(onClick = onUnfollow) { Text("Unfollow") }
        }
    }
}

private val TIME: DateTimeFormatter = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
