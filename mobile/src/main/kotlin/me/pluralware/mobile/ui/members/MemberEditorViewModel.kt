package me.pluralware.mobile.ui.members

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.io.IOException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.pluralware.shared.api.PluralKitHttpException
import me.pluralware.shared.model.Member
import me.pluralware.shared.model.MemberDraft
import me.pluralware.shared.model.MemberEdit
import me.pluralware.shared.model.MemberField
import me.pluralware.shared.repository.PkResult
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.ui.state.UiState
import me.pluralware.shared.ui.state.toUiError

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
        if (now.saving || now.saved || MemberEdit.validate(original, draft).isNotEmpty()) return
        val changes = MemberEdit.changes(original, draft)
        if (changes.isEmpty()) {
            _state.update { it.copy(saved = true) }
            return
        }
        _state.update { it.copy(saving = true, saveError = null, fieldErrors = emptyMap()) }
        viewModelScope.launch {
            when (val result = repository.updateMember(uuid, changes)) {
                is PkResult.Success -> _state.update { it.copy(saving = false, saved = true) }
                is PkResult.Failure -> _state.update { failed(it.copy(saving = false), result) }
            }
        }
    }

    /** The user changed [field]: PluralKit's complaint about its old value no longer applies. */
    fun edited(field: MemberField) {
        if (field in _state.value.fieldErrors) _state.update { it.copy(fieldErrors = it.fieldErrors - field) }
    }

    private fun failed(state: EditorState, failure: PkResult.Failure): EditorState {
        val error = failure.error
        if (failure.isUnauthorized) {
            return state.copy(unauthorized = true, saveError = "PluralKit no longer accepts this token. Sign out, then connect again.")
        }
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
        val why = when {
            error is PluralKitHttpException && error.isRateLimited ->
                "PluralKit is busy: try again in " + (error.retryAfter?.seconds?.let { if (it == 1L) "1 second" else "$it seconds" } ?: "a minute") + "."
            error is PluralKitHttpException && error.code == MEMBER_NOT_FOUND -> GONE
            error is PluralKitHttpException -> "PluralKit couldn't save this: ${error.reason}"
            error is IOException -> "Couldn't reach PluralKit. Check your connection and try again."
            else -> "Couldn't save this: ${error.message}"
        }
        return state.copy(saveError = why)
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
