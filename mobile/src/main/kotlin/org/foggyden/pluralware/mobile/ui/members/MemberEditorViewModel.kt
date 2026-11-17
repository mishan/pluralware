package org.foggyden.pluralware.mobile.ui.members

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.foggyden.pluralware.shared.api.PluralKitHttpException
import org.foggyden.pluralware.shared.model.Member
import org.foggyden.pluralware.shared.model.MemberDraft
import org.foggyden.pluralware.shared.model.MemberEdit
import org.foggyden.pluralware.shared.model.MemberField
import org.foggyden.pluralware.shared.repository.PkResult
import org.foggyden.pluralware.shared.repository.PluralKitRepository
import org.foggyden.pluralware.shared.ui.state.UiState
import org.foggyden.pluralware.shared.ui.state.toUiError

data class EditorState(
    val member: UiState<Member> = UiState.Loading,
    val saving: Boolean = false,
    /** The edit is in PluralKit; the screen closes. */
    val saved: Boolean = false,
    /** PluralKit's message for each field it rejected, until that field is edited again. */
    val fieldErrors: Map<MemberField, String> = emptyMap(),
    /** Why the last save failed, when it wasn't one field's fault. */
    val saveError: String? = null,
    /** PluralKit rejected the token: the screen offers a sign-out. */
    val unauthorized: Boolean = false,
    val deleting: Boolean = false,
    /** The member is gone from PluralKit; the screen closes. */
    val deleted: Boolean = false,
    /** Why the last delete failed. */
    val deleteError: String? = null,
)

/**
 * Edits one member. The form's draft, and the member as it was loaded, are
 * the screen's to keep (they must outlive process death); this loads the
 * member and saves edits.
 */
class MemberEditorViewModel(
    private val repository: PluralKitRepository,
    private val uuid: String,
) : ViewModel() {

    private val _state = MutableStateFlow(EditorState())
    val state: StateFlow<EditorState> = _state.asStateFlow()

    // Once per screen: the ViewModel outlives rotation, so turning the phone doesn't refetch.
    init { load() }

    /** Loads the member fresh from PluralKit: it may have been edited elsewhere. */
    fun load() {
        if (_state.value.member !is UiState.Content) _state.update { it.copy(member = UiState.Loading) }
        viewModelScope.launch {
            val member = when (val result = repository.refreshMembers(force = true)) {
                is PkResult.Success -> result.value.firstOrNull { it.uuid == uuid }?.let { UiState.Content(it) }
                    ?: UiState.Error(GONE)
                is PkResult.Failure -> result.toUiError("Couldn't load this member")
            }
            _state.update { it.copy(member = member) }
        }
    }

    /** Sends the fields of [draft] that differ from [original], the member as the form loaded it. */
    fun save(original: Member, draft: MemberDraft) {
        val now = _state.value
        if (now.saving || now.saved || now.deleting || now.deleted || !MemberEdit.isValid(original, draft)) return
        val changes = MemberEdit.changes(original, draft)
        val proxyTags = MemberEdit.proxyTagChanges(original, draft)
        val privacy = MemberEdit.privacyChanges(original, draft)
        val aliases = MemberEdit.aliasChanges(original, draft)
        if (changes.isEmpty() && proxyTags == null && privacy.isEmpty() && aliases == null) {
            _state.update { it.copy(saved = true) }
            return
        }
        _state.update { it.copy(saving = true, saveError = null, fieldErrors = emptyMap()) }
        viewModelScope.launch {
            when (val result = repository.updateMember(uuid, changes, proxyTags, privacy, aliases)) {
                is PkResult.Success -> _state.update { it.copy(saving = false, saved = true) }
                is PkResult.Failure -> _state.update { failed(it.copy(saving = false), result) }
            }
        }
    }

    /** Deletes the member from PluralKit; the screen closes once they're gone. */
    fun delete() {
        val now = _state.value
        if (now.saving || now.saved || now.deleting || now.deleted) return
        _state.update { it.copy(deleting = true, deleteError = null) }
        viewModelScope.launch {
            when (val result = repository.deleteMember(uuid)) {
                is PkResult.Success -> _state.update { it.copy(deleting = false, deleted = true) }
                is PkResult.Failure -> _state.update {
                    it.copy(deleting = false, deleteError = whyFailed(result, "delete them"), unauthorized = it.unauthorized || result.isUnauthorized)
                }
            }
        }
    }

    /** The delete dialog closed: a failure it showed is done with. */
    fun deleteDismissed() {
        if (!_state.value.deleting) _state.update { it.copy(deleteError = null) }
    }

    /** The user changed [field]: PluralKit's complaint about its old value no longer applies. */
    fun edited(field: MemberField) {
        if (field in _state.value.fieldErrors) _state.update { it.copy(fieldErrors = it.fieldErrors - field) }
    }

    private fun failed(state: EditorState, failure: PkResult.Failure): EditorState {
        val error = failure.error
        if (failure.isUnauthorized) return state.copy(unauthorized = true, saveError = whyFailed(failure, "save this"))
        if (error is PluralKitHttpException && error.fieldErrors.isNotEmpty()) {
            // Under the field it's about, or said plainly if the form has no such field.
            val (known, other) = error.fieldErrors.entries.partition { (key, _) -> MemberField.entries.any { it.apiKey == key } }
            return state.copy(
                fieldErrors = known.associate { (key, messages) ->
                    MemberField.entries.first { it.apiKey == key } to messages.joinToString(" ")
                },
                saveError = other.flatMap { it.value }.joinToString(" ").ifEmpty { null },
            )
        }
        val gone = error is PluralKitHttpException && !error.isRateLimited && error.code == MEMBER_NOT_FOUND
        return state.copy(saveError = if (gone) GONE else whyFailed(failure, "save this"))
    }

    private companion object {
        const val GONE = "This member isn't in PluralKit anymore."
        const val MEMBER_NOT_FOUND = 20002
    }

    @Suppress("UNCHECKED_CAST")
    class Factory(private val repository: PluralKitRepository, private val uuid: String) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MemberEditorViewModel(repository, uuid) as T
    }
}

/** Why [failure] happened, worded for a person; [doing] is what failed, as in "couldn't save this". */
internal fun whyFailed(failure: PkResult.Failure, doing: String): String {
    val error = failure.error
    return when {
        failure.isUnauthorized -> "PluralKit no longer accepts this token. Sign out, then connect again."
        error is PluralKitHttpException && error.isRateLimited ->
            "PluralKit is busy: try again in " + (error.retryAfter?.seconds?.let { if (it == 1L) "1 second" else "$it seconds" } ?: "a minute") + "."
        error is PluralKitHttpException -> "PluralKit couldn't $doing: ${error.reason}"
        error is IOException -> "Couldn't reach PluralKit. Check your connection and try again."
        else -> "Couldn't $doing: ${error.message}"
    }
}
