package me.pluralwatch.wear.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.pluralwatch.shared.model.Switch
import me.pluralwatch.shared.repository.PkResult
import me.pluralwatch.shared.repository.PluralKitRepository
import me.pluralwatch.wear.ui.state.UiState

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
                is PkResult.Failure -> UiState.Error(r.error.message ?: "Couldn't load history")
            }
        }
    }

    /** Re-register a past fronting configuration. Used by the "switch back" affordance. */
    fun switchBackTo(switch: Switch, onDone: () -> Unit) {
        viewModelScope.launch {
            repository.registerSwitch(switch.members.map { it.uuid })
            onDone()
        }
    }

    @Suppress("UNCHECKED_CAST")
    class Factory(private val repository: PluralKitRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HistoryViewModel(repository) as T
    }
}
