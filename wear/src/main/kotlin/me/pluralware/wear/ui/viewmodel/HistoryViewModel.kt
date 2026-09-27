package me.pluralware.wear.ui.viewmodel

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
import me.pluralware.wear.ui.state.UiState
import me.pluralware.wear.ui.state.toUiError

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

    // Ignores taps while a switch is in flight, so a double tap posts once.
    private var switching = false

    /**
     * Re-register a past fronting configuration. Used by the "switch back"
     * affordance. [onDone] runs only once the switch is in; a failure replaces
     * the list with an error, as the picker does.
     */
    fun switchBackTo(switch: Switch, onDone: () -> Unit) {
        if (switching) return
        val uuids = switch.members.map { it.uuid }
        // PluralKit answers a switch identical to the current one with a 400.
        // The user already has what they tapped for.
        if (uuids == repository.currentFronters.value?.members?.map { it.uuid }) {
            onDone()
            return
        }
        switching = true
        viewModelScope.launch {
            try {
                when (val r = repository.registerSwitch(uuids)) {
                    is PkResult.Success -> onDone()
                    is PkResult.Failure -> _state.value = r.toUiError("Couldn't switch back")
                }
            } finally {
                switching = false
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    class Factory(private val repository: PluralKitRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HistoryViewModel(repository) as T
    }
}
