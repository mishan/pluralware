package me.pluralware.wear.ui.viewmodel

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
import me.pluralware.shared.repository.PkResult
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.wear.ui.state.PickerState
import me.pluralware.wear.ui.state.UiState

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

    init {
        loadMembers()
        loadInitialSelection()
    }

    fun loadMembers() {
        _state.update { it.copy(members = UiState.Loading) }
        viewModelScope.launch {
            // force=true on every picker open: this screen is the user's
            // chance to react to members they just added in PluralKit, so a
            // cached list defeats the purpose of opening it.
            val r = repository.refreshMembers(force = true)
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

    /**
     * Fetch the current fronters once and apply them as the initial selection,
     * so the picker opens with today's fronters already ticked.
     *
     * If the fetch fails we leave [PickerState.seeded] = false; submit() will
     * then fall back to hitting the API instead of short-circuiting on a stale
     * comparison.
     */
    private fun loadInitialSelection() {
        viewModelScope.launch {
            val result = repository.refreshFronters()
            if (result !is PkResult.Success) return@launch
            val fronterUuids = result.value?.members?.map { it.uuid }?.toSet().orEmpty()
            _state.update { current ->
                if (current.seeded) return@update current
                // Don't clobber user toggles that landed before the fetch did.
                val selection = if (current.selectedUuids.isEmpty()) fronterUuids else current.selectedUuids
                current.copy(selectedUuids = selection, seeded = true)
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
        val requested = uuids.toSet()
        val currentFronterUuids = repository.currentFronters.value
            ?.members?.map { it.uuid }?.toSet().orEmpty()
        // PluralKit returns HTTP 400 if you POST a switch with the same fronters
        // already on top. Treat the user's intent as already satisfied and just
        // flash the confirmation. Gated on `seeded` so we don't short-circuit on
        // a stale (never-loaded) current-fronters value.
        if (_state.value.seeded && requested == currentFronterUuids) {
            viewModelScope.launch {
                _state.update { it.copy(confirmed = true) }
                delay(confirmationLingerMillis)
                _doneEvents.emit(Unit)
            }
            return
        }
        _state.update { it.copy(submitting = true) }
        viewModelScope.launch {
            when (val r = repository.registerSwitch(uuids)) {
                is PkResult.Success -> {
                    _state.update { it.copy(submitting = false, confirmed = true) }
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
