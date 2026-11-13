package me.pluralware.mobile.ui.members

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
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

    private val _reloadError = MutableStateFlow<String?>(null)

    /** Why the last reload failed, while the list from before stays on screen. */
    val reloadError: StateFlow<String?> = _reloadError.asStateFlow()

    // Only the newest load's answer is shown; an older one finishing late is stale.
    private var latestLoad = 0

    init {
        // Once per screen: the ViewModel outlives rotation, so turning the phone doesn't refetch.
        load()
        // Again for every edit, here (even one that landed after its editor closed) or on the other device.
        repository.membersChanged.drop(1).onEach { load() }.launchIn(viewModelScope)
    }

    /** Loads the list fresh from PluralKit: members may have been edited elsewhere since. */
    fun load() {
        val load = ++latestLoad
        // A reload keeps the list on screen: returning from an edit shouldn't flash a spinner.
        if (_state.value !is UiState.Content) _state.value = UiState.Loading
        viewModelScope.launch {
            val result = repository.refreshMembers(force = true)
            if (load != latestLoad) return@launch
            when (result) {
                is PkResult.Success -> {
                    _state.value = UiState.Content(result.value)
                    _reloadError.value = null
                }
                is PkResult.Failure ->
                    if (_state.value is UiState.Content && !result.isUnauthorized) {
                        _reloadError.value = result.toUiError("Couldn't refresh your members").message
                    } else {
                        _state.value = result.toUiError("Couldn't load your members")
                    }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    class Factory(private val repository: PluralKitRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MembersViewModel(repository) as T
    }
}
