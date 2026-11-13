package me.pluralware.mobile.ui.members

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import kotlinx.coroutines.delay
import me.pluralware.mobile.ui.components.BackTopBar
import me.pluralware.mobile.ui.components.ErrorView
import me.pluralware.mobile.ui.components.LoadingView
import me.pluralware.shared.model.Member
import me.pluralware.shared.model.MemberDraft
import me.pluralware.shared.model.MemberEdit
import me.pluralware.shared.model.MemberField
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.ui.state.UiState

/** Edits one member, then goes back once PluralKit has the edit. */
@Composable
fun MemberEditorScreen(
    repository: PluralKitRepository,
    uuid: String,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    onSignOut: () -> Unit,
    vm: MemberEditorViewModel = viewModel(factory = MemberEditorViewModel.Factory(repository, uuid)),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.saved) { if (state.saved) onSaved() }
    MemberEditorContent(state, vm::save, vm::edited, onBack, onRetry = vm::load, onSignOut)
}

@Composable
internal fun MemberEditorContent(
    state: EditorState,
    onSave: (original: Member, draft: MemberDraft) -> Unit,
    onEdited: (MemberField) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
) {
    // The form and the member it started from are kept across process death. Saving diffs
    // against that member, not one fetched since: that would undo edits made elsewhere.
    var original by rememberSaveable(stateSaver = MemberSaver) { mutableStateOf<Member?>(null) }
    var draft by rememberSaveable(stateSaver = DraftSaver) { mutableStateOf<MemberDraft?>(null) }
    val loaded = (state.member as? UiState.Content)?.value
    if (original == null && loaded != null) {
        original = loaded
        draft = MemberDraft.of(loaded)
    }
    val from = original
    val form = draft
    val changed = from != null && form != null && MemberField.entries.any {
        MemberEdit.normalize(it, form[it]) != MemberEdit.normalize(it, MemberDraft.of(from)[it])
    }

    var confirmingDiscard by rememberSaveable { mutableStateOf(false) }
    var confirmingLeaveWhileSaving by rememberSaveable { mutableStateOf(false) }
    val leave = {
        when {
            // The save carries on without the screen, but how it went would be shown nowhere.
            state.saving -> confirmingLeaveWhileSaving = true
            changed -> confirmingDiscard = true
            else -> onBack()
        }
    }
    BackHandler(enabled = changed || state.saving, onBack = leave)

    Scaffold(
        topBar = { BackTopBar(from?.let { "Edit ${it.displayLabel}" } ?: "Edit member", onBack = leave) },
        bottomBar = {
            if (from != null && form != null) {
                SaveBar(state, enabled = changed && MemberEdit.validate(from, form).isEmpty(), { onSave(from, form) }, onSignOut)
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when {
            from != null && form != null -> MemberForm(
                state = state,
                original = from,
                draft = form,
                onChange = { field, value ->
                    draft = form.with(field, value)
                    onEdited(field)
                },
                modifier = modifier,
            )
            state.member is UiState.Error -> ErrorView(state.member, onRetry = onRetry, onSignOut = onSignOut, modifier = modifier)
            else -> LoadingView(modifier)
        }
    }

    if (confirmingLeaveWhileSaving && state.saving) {
        AlertDialog(
            onDismissRequest = { confirmingLeaveWhileSaving = false },
            title = { Text("Still saving") },
            text = { Text("If you leave now, you won't see whether PluralKit took your changes.") },
            confirmButton = {
                TextButton(onClick = { confirmingLeaveWhileSaving = false; onBack() }) { Text("Leave") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingLeaveWhileSaving = false }) { Text("Stay") }
            },
        )
    }

    if (confirmingDiscard) {
        AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text("Discard your changes?") },
            text = { Text("They haven't been saved to PluralKit.") },
            confirmButton = {
                TextButton(onClick = { confirmingDiscard = false; onBack() }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDiscard = false }) { Text("Keep editing") }
            },
        )
    }
}

@Composable
private fun MemberForm(
    state: EditorState,
    original: Member,
    draft: MemberDraft,
    onChange: (MemberField, String) -> Unit,
    modifier: Modifier,
) {
    val problems = MemberEdit.validate(original, draft)
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (field in MemberField.entries) {
            val error = problems[field] ?: state.fieldErrors[field]
            OutlinedTextField(
                value = draft[field],
                onValueChange = { onChange(field, it) },
                // A save sends the draft as it was: a change made meanwhile would be lost when it closes.
                enabled = !state.saving,
                modifier = Modifier.fillMaxWidth().testTag("field-${field.apiKey}"),
                label = { Text(field.label) },
                leadingIcon = when {
                    field == MemberField.Color -> { { ColorSwatch(draft.color) } }
                    // Only https: Android blocks plain http, so it could never show.
                    field == MemberField.AvatarUrl && error == null ->
                        MemberEdit.normalize(field, draft.avatarUrl)
                            ?.takeIf { it.startsWith("https://", ignoreCase = true) }
                            ?.let { url -> { AvatarPreview(url) } }
                    else -> null
                },
                isError = error != null,
                supportingText = when {
                    error != null -> { { Text(error) } }
                    field == MemberField.Description -> { { Text("${draft.description.trim().length} / ${field.maxLength}") } }
                    else -> null
                },
                singleLine = field != MemberField.Description,
                minLines = if (field == MemberField.Description) 3 else 1,
                keyboardOptions = field.keyboard.copy(
                    // Single-line fields move on to the next; the description takes new lines.
                    imeAction = when (field) {
                        MemberField.Description -> ImeAction.Default
                        MemberField.entries.last() -> ImeAction.Done
                        else -> ImeAction.Next
                    },
                ),
            )
        }
    }
}

/** Pinned to the bottom, above the keyboard while it's open, so Save is always in reach. */
@Composable
private fun SaveBar(state: EditorState, enabled: Boolean, onSave: () -> Unit, onSignOut: () -> Unit) {
    Surface(tonalElevation = 3.dp, shadowElevation = 3.dp) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.saveError?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
            if (state.unauthorized) {
                Button(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) { Text("Sign out") }
            } else {
                Button(onClick = onSave, enabled = enabled && !state.saving, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.saving) "Saving…" else "Save")
                }
            }
        }
    }
}

/**
 * The avatar at [url], once it's been still for a moment: not a fetch, to a
 * different host, on every keystroke. A plain circle while it loads or if it
 * won't. Silent for screen readers: the address is right beside it.
 */
@Composable
private fun AvatarPreview(url: String) {
    var settled by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(url) {
        delay(AVATAR_SETTLE_MILLIS)
        settled = url
    }
    val shown = settled?.takeIf { it == url } ?: return
    val blank = ColorPainter(MaterialTheme.colorScheme.surfaceVariant)
    AsyncImage(
        model = shown,
        contentDescription = null,
        placeholder = blank,
        error = blank,
        contentScale = ContentScale.Crop,
        modifier = Modifier.size(32.dp).clip(CircleShape).testTag("avatar-preview"),
    )
}

private const val AVATAR_SETTLE_MILLIS = 600L

/**
 * The color as it would be saved, or a plain circle while it isn't a color
 * yet. Outlined, so a color close to the background still shows.
 */
@Composable
private fun ColorSwatch(typed: String) {
    val hex = MemberEdit.normalize(MemberField.Color, typed)
    val color = hex?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { Color(0xFF000000 or it) }
    Box(
        modifier = Modifier
            .size(20.dp)
            .clip(CircleShape)
            .background(color ?: MaterialTheme.colorScheme.surfaceVariant)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
    )
}

private val MemberField.label: String
    get() = when (this) {
        MemberField.Name -> "Name"
        MemberField.DisplayName -> "Display name"
        MemberField.Pronouns -> "Pronouns"
        MemberField.Color -> "Color (hex)"
        MemberField.Description -> "Description"
        MemberField.AvatarUrl -> "Avatar URL"
    }

private val MemberField.keyboard: KeyboardOptions
    get() = when (this) {
        MemberField.Color -> KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false)
        MemberField.AvatarUrl -> KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false)
        MemberField.Description -> KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
        else -> KeyboardOptions(capitalization = KeyboardCapitalization.Words)
    }

private fun MemberDraft.with(field: MemberField, value: String) = when (field) {
    MemberField.Name -> copy(name = value)
    MemberField.DisplayName -> copy(displayName = value)
    MemberField.Pronouns -> copy(pronouns = value)
    MemberField.Color -> copy(color = value)
    MemberField.Description -> copy(description = value)
    MemberField.AvatarUrl -> copy(avatarUrl = value)
}

private val DraftSaver: Saver<MemberDraft?, Any> = listSaver(
    save = { draft -> draft?.let { d -> MemberField.entries.map { d[it] } }.orEmpty() },
    restore = { values -> values.takeIf { it.size == MemberField.entries.size }?.let { MemberDraft(it[0], it[1], it[2], it[3], it[4], it[5]) } },
)

private val MemberSaver: Saver<Member?, Any> = listSaver(
    save = { m ->
        m?.let { listOf(it.id, it.uuid, it.name, it.displayName, it.pronouns, it.color, it.avatarUrl, it.isPrivate.toString(), it.description) }
            .orEmpty()
    },
    restore = { v ->
        v.takeIf { it.size == 9 }?.let {
            Member(it[0]!!, it[1]!!, it[2]!!, it[3], it[4], it[5], it[6], isPrivate = it[7] == "true", description = it[8])
        }
    },
)
