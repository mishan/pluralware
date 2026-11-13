package me.pluralware.mobile.ui.members

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.pluralware.shared.model.Member
import me.pluralware.shared.repository.PkResult
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.ui.state.UiState
import me.pluralware.shared.ui.state.toUiError

/** The system's members, for choosing one to edit. */
class MembersViewModel(private val repository: PluralKitRepository) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<Member>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Member>>> = _state.asStateFlow()

    // Once per screen: the ViewModel outlives rotation, so turning the phone doesn't refetch.
    init { load() }

    /** Loads the list fresh from PluralKit: members may have been edited elsewhere since. */
    fun load() {
        // A reload keeps the list on screen: returning from an edit shouldn't flash a spinner.
        if (_state.value !is UiState.Content) _state.value = UiState.Loading
        viewModelScope.launch {
            val result = repository.refreshMembers(force = true)
            _state.value = when (result) {
                is PkResult.Success -> UiState.Content(result.value)
                is PkResult.Failure -> _state.value.takeIf { it is UiState.Content && !result.isUnauthorized }
                    ?: result.toUiError("Couldn't load your members")
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    class Factory(private val repository: PluralKitRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MembersViewModel(repository) as T
    }
}
