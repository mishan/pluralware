package org.foggyden.pluralware.mobile.ui.members

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
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
import org.foggyden.pluralware.mobile.ui.components.BackTopBar
import org.foggyden.pluralware.mobile.ui.components.ErrorView
import org.foggyden.pluralware.mobile.ui.components.LoadingView
import org.foggyden.pluralware.shared.model.Member
import org.foggyden.pluralware.shared.model.MemberDraft
import org.foggyden.pluralware.shared.model.MemberEdit
import org.foggyden.pluralware.shared.model.MemberField
import org.foggyden.pluralware.shared.model.MemberPrivacy
import org.foggyden.pluralware.shared.model.ProxyTagDraft
import org.foggyden.pluralware.shared.model.SavedForm
import org.foggyden.pluralware.shared.repository.PluralKitRepository
import org.foggyden.pluralware.shared.ui.state.UiState

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
    MemberEditorContent(
        state, vm::save, vm::edited, onBack, onRetry = vm::load, onSignOut,
        onDelete = vm::delete, onDeleteDismissed = vm::deleteDismissed, onDeleted = onSaved,
    )
}

@Composable
internal fun MemberEditorContent(
    state: EditorState,
    onSave: (original: Member, draft: MemberDraft) -> Unit,
    onEdited: (MemberField) -> Unit,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onSignOut: () -> Unit,
    onDelete: () -> Unit = {},
    onDeleteDismissed: () -> Unit = {},
    onDeleted: () -> Unit = {},
) {
    // The form and the member it started from are kept across process death. Saving diffs
    // against that member, not one fetched since: that would undo edits made elsewhere.
    var original by rememberSaveable(stateSaver = MemberSaver) { mutableStateOf<Member?>(null) }
    var draft by rememberSaveable(stateSaver = DraftSaver) { mutableStateOf<MemberDraft?>(null) }
    val loaded = (state.member as? UiState.Content)?.value
    // Both from the member as loaded, unless both were restored: a form must diff against its own start.
    if ((original == null || draft == null) && loaded != null) {
        original = loaded
        draft = MemberDraft.of(loaded)
    }
    val from = original
    val form = draft
    val changed = from != null && form != null && (
        MemberField.entries.any { MemberEdit.normalize(it, form[it]) != MemberEdit.normalize(it, MemberDraft.of(from)[it]) } ||
            MemberEdit.proxyTagChanges(from, form) != null ||
            MemberEdit.privacyChanges(from, form).isNotEmpty() ||
            MemberEdit.aliasChanges(from, form) != null
        )

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
    // Gone from PluralKit: the edits are moot, so no asking about them.
    LaunchedEffect(state.deleted) { if (state.deleted) onDeleted() }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = { BackTopBar(from?.let { "Edit ${it.displayLabel}" } ?: "Edit member", onBack = leave) },
        bottomBar = {
            if (from != null && form != null) {
                SaveBar(state, enabled = changed && MemberEdit.isValid(from, form) && !state.deleting, { onSave(from, form) }, onSignOut)
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
                onTagsChange = { draft = form.copy(proxyTags = it) },
                onPrivacyChange = { draft = form.copy(privateParts = it) },
                onAliasesChange = { draft = form.copy(aliases = it) },
                onDeleteClick = { confirmingDelete = true },
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

    if (confirmingDelete && from != null) {
        DeleteDialog(from, state, onDelete, onDismiss = {
            confirmingDelete = false
            onDeleteDismissed()
        })
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
    onTagsChange: (List<ProxyTagDraft>) -> Unit,
    onPrivacyChange: (Set<MemberPrivacy>) -> Unit,
    onAliasesChange: (List<String>) -> Unit,
    onDeleteClick: () -> Unit,
    modifier: Modifier,
) {
    val problems = MemberEdit.validate(original, draft)
    // A save sends the draft as it was, and a delete makes it moot: no changes meanwhile.
    val locked = state.saving || state.deleting
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
                enabled = !locked,
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
                    field == MemberField.Birthday -> { { Text("YYYY-MM-DD, or MM-DD to leave out the year") } }
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
        ProxyTags(draft.proxyTags, MemberEdit.proxyTagProblem(original, draft), enabled = !locked, onChange = onTagsChange)
        Aliases(draft.aliases, MemberEdit.aliasProblem(original, draft), enabled = !locked, onChange = onAliasesChange)
        Privacy(draft.privateParts, enabled = !locked, onChange = onPrivacyChange)
        OutlinedButton(
            onClick = onDeleteClick,
            enabled = !locked,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) { Text("Delete member") }
    }
}

/** Deleting can't be undone, so the member's name must be typed first, as PluralKit asks for their ID. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DeleteDialog(member: Member, state: EditorState, onDelete: () -> Unit, onDismiss: () -> Unit) {
    BasicAlertDialog(onDismissRequest = { if (!state.deleting) onDismiss() }) {
        Surface(shape = MaterialTheme.shapes.extraLarge, tonalElevation = 6.dp) { DeleteForm(member, state, onDelete, onDismiss) }
    }
}

// Apart from its dialog so tests can show it: Robolectric never settles with a text field in a dialog window.
@Composable
internal fun DeleteForm(member: Member, state: EditorState, onDelete: () -> Unit, onDismiss: () -> Unit) {
    var typed by rememberSaveable { mutableStateOf("") }
    val matches = typed.trim().equals(member.name.trim(), ignoreCase = true)
    Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Delete ${member.displayLabel}?", style = MaterialTheme.typography.headlineSmall)
        Text(
            "This deletes ${member.displayLabel} from PluralKit for good, and takes them out of every " +
                "switch they were in. Type ${member.name} to confirm.",
        )
        OutlinedTextField(
            value = typed,
            onValueChange = { typed = it },
            label = { Text("Member's name") },
            singleLine = true,
            enabled = !state.deleting,
            keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().testTag("delete-confirm"),
        )
        state.deleteError?.let {
            Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        }
        Row(modifier = Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onDismiss, enabled = !state.deleting) { Text("Cancel") }
            TextButton(
                onClick = onDelete,
                enabled = matches && !state.deleting,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(if (state.deleting) "Deleting…" else "Delete") }
        }
    }
}

@Composable
private fun Privacy(private: Set<MemberPrivacy>, enabled: Boolean, onChange: (Set<MemberPrivacy>) -> Unit) {
    Text("Privacy", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    Text(
        "Switched on, it's private: only your system sees it in PluralKit. Others see a private name as the " +
            "display name, and don't see a private member in the member list.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for (part in MemberPrivacy.entries) {
        val isPrivate = part in private
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = isPrivate, enabled = enabled, role = Role.Switch) {
                    onChange(if (it) private + part else private - part)
                }
                .testTag("privacy-${part.apiKey}")
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(part.label, modifier = Modifier.weight(1f))
            Switch(checked = isPrivate, onCheckedChange = null, enabled = enabled)
        }
    }
}

private val MemberPrivacy.label: String
    get() = when (this) {
        MemberPrivacy.Visibility -> "Whole member"
        MemberPrivacy.Name -> "Name"
        MemberPrivacy.Description -> "Description"
        MemberPrivacy.Birthday -> "Birthday"
        MemberPrivacy.Pronouns -> "Pronouns"
        MemberPrivacy.Avatar -> "Avatar"
        MemberPrivacy.Banner -> "Banner"
        MemberPrivacy.Metadata -> "Message count and dates"
        MemberPrivacy.ProxyTags -> "Proxy tags"
        MemberPrivacy.Aliases -> "Aliases"
    }

/** Other names the member goes by, one per row. */
@Composable
private fun Aliases(aliases: List<String>, problem: String?, enabled: Boolean, onChange: (List<String>) -> Unit) {
    Text("Aliases", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    aliases.forEachIndexed { i, alias ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = alias,
                onValueChange = { onChange(aliases.toMutableList().apply { set(i, it) }) },
                enabled = enabled,
                label = { Text("Alias") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words, imeAction = ImeAction.Next),
                modifier = Modifier.weight(1f).testTag("alias-name-$i"),
            )
            IconButton(
                onClick = { onChange(aliases.filterIndexed { j, _ -> j != i }) },
                enabled = enabled,
                modifier = Modifier.testTag("alias-remove-$i"),
            ) { Icon(Icons.Filled.Close, contentDescription = "Remove alias ${i + 1}") }
        }
    }
    if (problem != null) {
        Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    TextButton(onClick = { onChange(aliases + "") }, enabled = enabled) { Text("Add alias") }
}

/** Each tag as prefix, "text", suffix: how a message is marked as this member's in Discord. */
@Composable
private fun ProxyTags(tags: List<ProxyTagDraft>, problem: String?, enabled: Boolean, onChange: (List<ProxyTagDraft>) -> Unit) {
    Text("Proxy tags", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    tags.forEachIndexed { i, tag ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val tagKeyboard = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Next)
            OutlinedTextField(
                value = tag.prefix,
                onValueChange = { onChange(tags.toMutableList().apply { set(i, tag.copy(prefix = it)) }) },
                enabled = enabled,
                label = { Text("Prefix") },
                singleLine = true,
                keyboardOptions = tagKeyboard,
                modifier = Modifier.weight(1f).testTag("proxy-prefix-$i"),
            )
            Text("text", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = tag.suffix,
                onValueChange = { onChange(tags.toMutableList().apply { set(i, tag.copy(suffix = it)) }) },
                enabled = enabled,
                label = { Text("Suffix") },
                singleLine = true,
                keyboardOptions = tagKeyboard,
                modifier = Modifier.weight(1f).testTag("proxy-suffix-$i"),
            )
            IconButton(
                onClick = { onChange(tags.filterIndexed { j, _ -> j != i }) },
                enabled = enabled,
                modifier = Modifier.testTag("proxy-remove-$i"),
            ) { Icon(Icons.Filled.Close, contentDescription = "Remove proxy tag ${i + 1}") }
        }
    }
    if (problem != null) {
        Text(problem, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
    TextButton(onClick = { onChange(tags + ProxyTagDraft("", "")) }, enabled = enabled) { Text("Add proxy tag") }
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
        MemberField.Birthday -> "Birthday"
    }

private val MemberField.keyboard: KeyboardOptions
    get() = when (this) {
        MemberField.Color -> KeyboardOptions(keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false)
        MemberField.AvatarUrl -> KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false)
        // Digits and a dash, which a phone keyboard has and a number keyboard may not.
        MemberField.Birthday -> KeyboardOptions(keyboardType = KeyboardType.Phone, autoCorrectEnabled = false)
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
    MemberField.Birthday -> copy(birthday = value)
}

// Restored as Any: the version before saved each as a list, under the same key.
internal val DraftSaver: Saver<MemberDraft?, Any> =
    Saver(save = { it?.let(SavedForm::encode) }, restore = { (it as? String)?.let(SavedForm::draft) })

internal val MemberSaver: Saver<Member?, Any> =
    Saver(save = { it?.let(SavedForm::encode) }, restore = { (it as? String)?.let(SavedForm::member) })
