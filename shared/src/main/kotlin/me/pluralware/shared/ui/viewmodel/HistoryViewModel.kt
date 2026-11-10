package me.pluralware.shared.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.pluralware.shared.model.Switch
import me.pluralware.shared.repository.PkResult
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.ui.state.UiState
import me.pluralware.shared.ui.state.toUiError

class HistoryViewModel(
    private val repository: PluralKitRepository,
    private val limit: Int = 10,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<List<Switch>>>(UiState.Loading)
    val state: StateFlow<UiState<List<Switch>>> = _state.asStateFlow()

    init { load() }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch {
            _state.value = when (val r = repository.recentSwitches(limit)) {
                is PkResult.Success -> UiState.Content(r.value)
                is PkResult.Failure -> r.toUiError("Couldn't load history")
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    class Factory(private val repository: PluralKitRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HistoryViewModel(repository) as T
    }
}
