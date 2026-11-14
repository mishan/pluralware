package me.pluralware.shared.ui.viewmodel

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

    init {
        load()
        // A fix made from this list, or another screen: show the switches as they are now.
        repository.switchesChanged.drop(1).onEach { fetch(keepOnFailure = true) }.launchIn(viewModelScope)
    }

    fun load() {
        _state.value = UiState.Loading
        viewModelScope.launch { fetch() }
    }

    // Only the newest fetch may answer: an older one may have read PluralKit from before a fix.
    private var fetches = 0

    private suspend fun fetch(keepOnFailure: Boolean = false) {
        val fetch = ++fetches
        val r = repository.recentSwitches(limit)
        if (fetch != fetches) return
        when (r) {
            is PkResult.Success -> _state.value = UiState.Content(r.value)
            is PkResult.Failure ->
                // A fix's own result already shows; a list a little out of date beats an error over it.
                if (!keepOnFailure || _state.value !is UiState.Content) _state.value = r.toUiError("Couldn't load history")
        }
    }

    @Suppress("UNCHECKED_CAST")
    class Factory(private val repository: PluralKitRepository, private val limit: Int = 10) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            HistoryViewModel(repository, limit) as T
    }
}
