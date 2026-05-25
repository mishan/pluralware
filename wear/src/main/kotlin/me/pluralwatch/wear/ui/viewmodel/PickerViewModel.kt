package me.pluralwatch.wear.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.pluralwatch.shared.repository.PkResult
import me.pluralwatch.shared.repository.PluralKitRepository
import me.pluralwatch.wear.ui.state.PickerState
import me.pluralwatch.wear.ui.state.UiState

class PickerViewModel(
    private val repository: PluralKitRepository,
    /** Briefly shown after a successful switch before nav-back. Tuned for "felt" but not annoying. */
    private val confirmationLingerMillis: Long = 700L,
) : ViewModel() {

    private val _state = MutableStateFlow(PickerState())
    val state: StateFlow<PickerState> = _state.asStateFlow()

    /** Emits when submission finished successfully so the screen can dismiss itself. */
    private val _doneEvents = MutableSharedFlow<Unit>()
    val doneEvents: SharedFlow<Unit> = _doneEvents.asSharedFlow()

    init { loadMembers() }

    fun loadMembers() {
        _state.update { it.copy(members = UiState.Loading) }
        viewModelScope.launch {
            val r = repository.refreshMembers()
            _state.update {
                it.copy(
                    members = when (r) {
                        is PkResult.Success -> UiState.Content(r.value)
                        is PkResult.Failure -> UiState.Error(r.error.message ?: "Couldn't load members")
                    },
                )
            }
        }
    }

    fun toggle(memberUuid: String) {
        _state.update { current ->
            val next = if (memberUuid in current.selectedUuids) {
                current.selectedUuids - memberUuid
            } else {
                current.selectedUuids + memberUuid
            }
            current.copy(selectedUuids = next)
        }
    }

    fun submitSelection() = submit(_state.value.selectedUuids.toList())

    fun submitSwitchOut() = submit(emptyList())

    private fun submit(uuids: List<String>) {
        if (_state.value.submitting) return
        _state.update { it.copy(submitting = true) }
        viewModelScope.launch {
            when (val r = repository.registerSwitch(uuids)) {
                is PkResult.Success -> {
                    _state.update { it.copy(submitting = false, confirmed = r.value) }
                    delay(confirmationLingerMillis)
                    _doneEvents.emit(Unit)
                }
                is PkResult.Failure -> {
                    _state.update {
                        it.copy(
                            submitting = false,
                            members = UiState.Error(
                                r.error.message ?: "Couldn't register switch",
                            ),
                        )
                    }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    class Factory(private val repository: PluralKitRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PickerViewModel(repository) as T
    }
}
