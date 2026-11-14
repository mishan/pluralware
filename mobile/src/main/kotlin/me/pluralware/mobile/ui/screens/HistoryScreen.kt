package me.pluralware.mobile.ui.screens

import android.text.format.DateFormat
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
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

private const val SHOWN = 10

/** Recent switches; tap one to change its time or delete it. */
@Composable
fun HistoryScreen(
    repository: PluralKitRepository,
    onBack: () -> Unit,
    onSignOut: () -> Unit,
) {
    // One more than is shown, so the oldest row shown knows the switch before it.
    val vm: HistoryViewModel = viewModel(factory = HistoryViewModel.Factory(repository, limit = SHOWN + 1))
    val fixes: SwitchFixViewModel = viewModel(factory = SwitchFixViewModel.Factory(repository))
    val state by vm.state.collectAsState()
    val fix by fixes.state.collectAsState()
    HistoryContent(
        state = state,
        onBack = onBack,
        onRetry = vm::load,
        onSignOut = onSignOut,
        fix = fix,
        onMove = fixes::move,
        onDelete = fixes::delete,
        onNoticeShown = fixes::noticeShown,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HistoryContent(
    state: UiState<List<Switch>>,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    fix: SwitchFixViewModel.State = SwitchFixViewModel.State(),
    onMove: (String, Instant) -> Unit = { _, _ -> },
    onDelete: (String) -> Unit = {},
    onNoticeShown: () -> Unit = {},
    zone: ZoneId = LocalClock.current.zone,
) {
    val now = LocalClock.current.instant()
    val snackbar = remember { SnackbarHostState() }
    fix.notice?.let { notice ->
        LaunchedEffect(notice) {
            val result = snackbar.showSnackbar(
                message = notice.text(now, zone),
                actionLabel = if ((notice as? FixNotice.Failed)?.unauthorized == true) "Sign out" else null,
            )
            if (result == SnackbarResult.ActionPerformed) onSignOut()
            onNoticeShown()
        }
    }
    Scaffold(
        topBar = { BackTopBar("Recent switches", onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (state) {
            UiState.Loading -> LoadingView(modifier)
            is UiState.Error -> ErrorView(state, onRetry = onRetry, onSignOut = onSignOut, modifier = modifier)
            is UiState.Content -> {
                val switches = state.value
                var chosen by rememberSaveable { mutableStateOf<String?>(null) }
                var moving by rememberSaveable { mutableStateOf<String?>(null) }
                var deleting by rememberSaveable { mutableStateOf<String?>(null) }
                if (switches.isEmpty()) {
                    MessageView(title = "No switches yet", modifier = modifier)
                } else {
                    LazyColumn(
                        modifier = modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        itemsIndexed(switches.take(SHOWN), key = { _, it -> it.uuid }) { index, switch ->
                            SwitchCard(switch, current = index == 0, zone, enabled = !fix.busy) { chosen = switch.uuid }
                        }
                    }
                }
                val index = switches.indexOfFirst { it.uuid == chosen }
                if (index >= 0) {
                    FixSheet(
                        onDismiss = { chosen = null },
                        onChangeTime = { moving = chosen; chosen = null },
                        onDelete = { deleting = chosen; chosen = null },
                    )
                }
                val movingIndex = switches.indexOfFirst { it.uuid == moving }
                if (movingIndex >= 0) {
                    SwitchTimeDialog(
                        initial = switches[movingIndex].timestamp,
                        window = SwitchWindow.of(switches, movingIndex),
                        zone = zone,
                        onConfirm = { onMove(switches[movingIndex].uuid, it); moving = null },
                        onDismiss = { moving = null },
                    )
                }
                val deletingIndex = switches.indexOfFirst { it.uuid == deleting }
                if (deletingIndex >= 0) {
                    AlertDialog(
                        onDismissRequest = { deleting = null },
                        title = { Text("Delete this switch?") },
                        text = { Text(deleteOutcome(switches, deletingIndex, now, zone)) },
                        confirmButton = {
                            TextButton(onClick = { onDelete(switches[deletingIndex].uuid); deleting = null }) { Text("Delete") }
                        },
                        dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
                    )
                }
            }
        }
    }
}

private fun FixNotice.text(now: Instant, zone: ZoneId): String = when (this) {
    FixNotice.Moved -> "Time changed"
    FixNotice.Deleted -> "Switch deleted"
    FixNotice.AlreadyGone -> "That switch was already deleted"
    is FixNotice.OutOfRange ->
        "Someone switched since this list loaded. " + SwitchWindow(after, before).limits(now, zone)
    is FixNotice.Failed -> message
}

/** "Pick a time after 2:40 PM and before 4:00 PM." */
private fun SwitchWindow.limits(now: Instant, zone: ZoneId): String {
    val until = before?.let { timeOf(it, now, zone) } ?: "now"
    return if (after == null) "Pick a time before $until." else "Pick a time after ${timeOf(after, now, zone)} and before $until."
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FixSheet(onDismiss: () -> Unit, onChangeTime: () -> Unit, onDelete: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 16.dp)) {
            ListItem(
                headlineContent = { Text("Change time") },
                leadingContent = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                modifier = Modifier.clickable(role = Role.Button, onClick = onChangeTime),
            )
            ListItem(
                headlineContent = { Text("Delete") },
                leadingContent = { Icon(Icons.Filled.Delete, contentDescription = null) },
                colors = ListItemDefaults.colors(
                    headlineColor = MaterialTheme.colorScheme.error,
                    leadingIconColor = MaterialTheme.colorScheme.error,
                ),
                modifier = Modifier.clickable(role = Role.Button, onClick = onDelete),
            )
        }
    }
}

private val dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

/** A date and a time, limited to [window]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwitchTimeDialog(
    initial: Instant,
    window: SwitchWindow,
    zone: ZoneId,
    onConfirm: (Instant) -> Unit,
    onDismiss: () -> Unit,
    clock: Clock = LocalClock.current,
) {
    // Read on each change, so a dialog left open still allows up to now.
    val now = clock.instant()
    val start = initial.atZone(zone)
    var date by rememberSaveable { mutableStateOf(start.toLocalDate()) }
    val time = rememberTimePickerState(start.hour, start.minute, is24Hour = DateFormat.is24HourFormat(LocalContext.current))
    var pickingDate by rememberSaveable { mutableStateOf(false) }
    val picked = date.atTime(time.hour, time.minute)
    val unchanged = picked == start.toLocalDateTime().truncatedTo(ChronoUnit.MINUTES)
    // In the hour that repeats when clocks go back, the switch's own offset says which one is meant.
    val chosen = if (unchanged) initial else ZonedDateTime.ofLocal(picked, zone, start.offset).toInstant()
    // The hour skipped when clocks go forward: ofLocal would quietly move it an hour on.
    val exists = zone.rules.getValidOffsets(picked).isNotEmpty()
    val allowed = unchanged || exists && window.allows(chosen, now)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change time") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { pickingDate = true }) { Text(dateFormat.format(date)) }
                TimeInput(time)
                if (!allowed) {
                    val why = if (exists) window.limits(now, zone) else "That time doesn't exist here: the clocks skipped it."
                    Text(why, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(chosen) }, enabled = allowed && !unchanged) { Text("Change") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
    if (pickingDate) {
        val first = window.after?.atZone(zone)?.toLocalDate()
        val last = (window.before ?: now).atZone(zone).toLocalDate()
        val dates = rememberDatePickerState(
            initialSelectedDateMillis = date.utcMillis(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val day = Instant.ofEpochMilli(utcTimeMillis).atZone(ZoneOffset.UTC).toLocalDate()
                    return (first == null || day >= first) && day <= last
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                TextButton(onClick = {
                    dates.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                    pickingDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { pickingDate = false }) { Text("Cancel") } },
        ) { DatePicker(dates) }
    }
}

// DatePicker counts days as UTC midnights.
private fun LocalDate.utcMillis() = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private val dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SwitchCard(switch: Switch, current: Boolean, zone: ZoneId, enabled: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            modifier = Modifier
                .clickable(enabled = enabled, role = Role.Button, onClickLabel = "Fix", onClick = onClick)
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (current) {
                Text("Now", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
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
