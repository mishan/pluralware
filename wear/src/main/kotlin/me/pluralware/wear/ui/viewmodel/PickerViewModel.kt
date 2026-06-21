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
    /**
     * Fired after a switch is actually registered (not on the no-op short-circuit),
     * so the app layer can push a fronter-complication refresh. No-op by default
     * to keep previews/tests Context-free.
     */
    private val onSwitchRegistered: () -> Unit = {},
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
            // Preserve PluralKit's order — index 0 is the existing proxy fronter.
            val fronterUuids = result.value?.members?.map { it.uuid }.orEmpty()
            _state.update { current ->
                if (current.seeded) return@update current
                // Don't clobber user toggles that landed before the fetch did.
                val selection = current.selectedUuids.ifEmpty { fronterUuids }
                current.copy(selectedUuids = selection, seeded = true)
            }
        }
    }

    /**
     * Toggle a member in/out of the selection. Adding appends to the end so the
     * first member tapped stays the primary (proxy) fronter; removing preserves
     * the order of the remaining members.
     */
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

    fun deselectAll() {
        _state.update { it.copy(selectedUuids = emptyList()) }
    }

    fun submitSelection() = submit(_state.value.selectedUuids)

    private fun submit(uuids: List<String>) {
        if (_state.value.submitting) return
        val currentFronterUuids = repository.currentFronters.value
            ?.members?.map { it.uuid }.orEmpty()
        // PluralKit returns HTTP 400 if you POST a switch identical to the current
        // fronters. Treat the user's intent as already satisfied and just flash the
        // confirmation. The comparison is order-sensitive: re-ordering the same
        // members (e.g. to change the proxy fronter) is a real change, not a no-op.
        // Gated on `seeded` so we don't short-circuit on a stale current-fronters value.
        if (_state.value.seeded && uuids == currentFronterUuids) {
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
                    // Fronters changed — refresh any hosted complication.
                    onSwitchRegistered()
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
    class Factory(
        private val repository: PluralKitRepository,
        private val onSwitchRegistered: () -> Unit = {},
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            PickerViewModel(repository, onSwitchRegistered = onSwitchRegistered) as T
    }
}
