package me.pluralware.wear.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.pluralware.shared.model.Switch
import me.pluralware.shared.repository.PkResult
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.settings.SettingsStore
import me.pluralware.wear.ui.state.FronterStreak
import me.pluralware.wear.ui.state.FrontersState
import me.pluralware.wear.ui.state.UiState

/** How far back we walk history when computing per-member continuous fronting time. */
private const val HISTORY_LIMIT = 100

class FrontersViewModel(
    private val repository: PluralKitRepository,
    settingsStore: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow<UiState<FrontersState>>(UiState.Loading)
    val state: StateFlow<UiState<FrontersState>> = _state.asStateFlow()

    /** Poll interval in seconds (0 = off), driven by the synced settings. */
    val refreshIntervalSeconds: StateFlow<Int> = settingsStore.settingsFlow
        .map { it.refreshInterval.seconds }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            settingsStore.settingsFlow.value.refreshInterval.seconds,
        )

    // Guards against overlapping fetches: a slow refresh shouldn't stack with
    // the poll loop or the resume refresh.
    private var fetching = false

    init {
        load()
        // Picker → home: when the picker registers a new switch, the repo
        // pushes the new value into currentFronters. We watch for *uuid*
        // changes (not value changes) to avoid re-loading on our own writes,
        // and only react once initial content has settled so we don't race
        // with the inflight load.
        repository.currentFronters
            .onEach { incoming ->
                val current = _state.value
                if (current is UiState.Content &&
                    incoming?.uuid != current.value.switch?.uuid) {
                    refresh()
                }
            }
            .launchIn(viewModelScope)
    }

    /** Full load — shows the loading spinner. Used for first load and error retry. */
    fun load() = fetch(soft = false)

    /**
     * Soft refresh — keeps showing current content (with a small spinner)
     * instead of flashing the full-screen loader. Used by resume, polling, and
     * the manual refresh button. On failure with existing content we keep the
     * stale data rather than replacing it with an error screen.
     */
    fun refresh() = fetch(soft = true)

    private fun fetch(soft: Boolean) {
        if (fetching) return
        val current = _state.value
        when {
            soft && current is UiState.Content ->
                _state.value = UiState.Content(current.value.copy(refreshing = true))
            !soft -> _state.value = UiState.Loading
            // soft && not content: leave Loading/Error as-is; we replace on success.
        }
        fetching = true
        viewModelScope.launch {
            try {
                val frontersResult = repository.refreshFronters()
                if (frontersResult is PkResult.Failure) {
                    val cur = _state.value
                    _state.value = if (cur is UiState.Content) {
                        // Keep stale content visible; just stop the spinner.
                        UiState.Content(cur.value.copy(refreshing = false))
                    } else {
                        UiState.Error(frontersResult.error.message ?: "Couldn't load fronters")
                    }
                    return@launch
                }
                val switch = (frontersResult as PkResult.Success).value
                // History failure is non-fatal — we still have a current switch to
                // show. Streaks just fall back to the current switch timestamp.
                val history = (repository.recentSwitches(limit = HISTORY_LIMIT) as? PkResult.Success)
                    ?.value
                    .orEmpty()
                // If we got back the full HISTORY_LIMIT, assume there could be more
                // switches beyond our window; a member fronting at the boundary
                // gets the ">" uncertainty marker. If we got fewer, we have the
                // whole history and the boundary is real.
                val historyCapped = history.size >= HISTORY_LIMIT
                _state.value = UiState.Content(
                    FrontersState(
                        switch = switch,
                        streaks = computeStreaks(switch, history, historyCapped),
                        refreshing = false,
                    )
                )
            } finally {
                fetching = false
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    class Factory(
        private val repository: PluralKitRepository,
        private val settingsStore: SettingsStore,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            FrontersViewModel(repository, settingsStore) as T
    }
}

/**
 * For each member in [current], walk backward through [history] (newest-first,
 * including the current switch at index 0) until we hit a switch they're NOT in.
 * Their streak's [FronterStreak.since] is the timestamp of the oldest switch
 * in that contiguous run.
 *
 * When the walk reaches the end of [history] with the member still present
 * AND [historyCapped] is true, we mark the streak [FronterStreak.truncated] —
 * the actual streak may extend further back than we can see.
 *
 * If [history] doesn't contain the current switch (e.g. history fetch failed
 * or hasn't propagated yet), we fall back to the current switch's own
 * timestamp — same behavior as before this function existed.
 */
internal fun computeStreaks(
    current: Switch?,
    history: List<Switch>,
    historyCapped: Boolean,
): Map<String, FronterStreak> {
    if (current == null) return emptyMap()
    val currentIndex = history.indexOfFirst { it.uuid == current.uuid }
    return current.members.associate { member ->
        var since = current.timestamp
        var hitHistoryFloor = false
        if (currentIndex >= 0) {
            for (i in currentIndex + 1 until history.size) {
                val older = history[i]
                if (older.members.any { it.uuid == member.uuid }) {
                    since = older.timestamp
                    if (i == history.size - 1) hitHistoryFloor = true
                } else {
                    hitHistoryFloor = false
                    break
                }
            }
        }
        member.uuid to FronterStreak(
            since = since,
            truncated = historyCapped && hitHistoryFloor,
        )
    }
}
