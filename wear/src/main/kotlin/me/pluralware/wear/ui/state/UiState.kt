package me.pluralware.wear.ui.state

import me.pluralware.shared.model.Member
import me.pluralware.shared.model.Switch

/** Generic three-state container used by simple screens. */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Error(val message: String, val canRetry: Boolean = true) : UiState<Nothing>
    data class Content<T>(val value: T) : UiState<T>
}

/** Member-picker has a richer state: content + the user's working selection. */
data class PickerState(
    val members: UiState<List<Member>> = UiState.Loading,
    val selectedUuids: Set<String> = emptySet(),
    val submitting: Boolean = false,
    /** Set briefly after a successful switch so the screen can flash confirmation before navigating away. */
    val confirmed: Switch? = null,
)
