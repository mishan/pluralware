package me.pluralware.wear.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import me.pluralware.shared.model.Switch
import me.pluralware.shared.repository.PkResult
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.wear.ui.state.UiState

class FrontersViewModel(
    private val repository: PluralKitRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<Switch?>>(UiState.Loading)
    val state: StateFlow<UiState<Switch?>> = _state.asStateFlow()

    init {
        // Reflect any fronter changes pushed by other screens (e.g. picker → home).
        repository.currentFronters
            .onEach { current ->
                // Only overwrite if we already have content, so we don't clobber a Loading state.
                if (_state.value is UiState.Content) {
                    _state.value = UiState.Content(current)
                }
            }
            .launchIn(viewModelScope)

        load()
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = when (val r = repository.refreshFronters()) {
                is PkResult.Success -> UiState.Content(r.value)
                is PkResult.Failure -> UiState.Error(
                    message = r.error.message ?: "Couldn't load fronters",
                )
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    class Factory(private val repository: PluralKitRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            FrontersViewModel(repository) as T
    }
}
