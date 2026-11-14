package me.pluralware.mobile.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.pluralware.shared.api.PluralKitHttpException
import me.pluralware.shared.repository.Deletion
import me.pluralware.shared.repository.PkResult
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.repository.SwitchTimeOutOfRange

/** How the last fix went, shown once. */
sealed interface FixNotice {
    data object Moved : FixNotice
    data object Deleted : FixNotice
    data object AlreadyGone : FixNotice

    /** PluralKit's switches, fetched just before sending, put the time outside these. */
    data class OutOfRange(val after: Instant?, val before: Instant) : FixNotice
    data class Failed(val message: String, val unauthorized: Boolean = false) : FixNotice
}

/** Moves and deletes switches from History, one at a time. History reloads itself after each. */
class SwitchFixViewModel(private val repository: PluralKitRepository) : ViewModel() {

    data class State(val busy: Boolean = false, val notice: FixNotice? = null)

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun move(uuid: String, to: Instant) = fix {
        when (val r = repository.moveSwitch(uuid, to)) {
            is PkResult.Success -> FixNotice.Moved
            is PkResult.Failure -> (r.error as? SwitchTimeOutOfRange)?.let { FixNotice.OutOfRange(it.after, it.before) }
                ?: failed("move", r)
        }
    }

    fun delete(uuid: String) = fix {
        when (val r = repository.deleteSwitch(uuid)) {
            is PkResult.Success -> if (r.value == Deletion.Done) FixNotice.Deleted else FixNotice.AlreadyGone
            is PkResult.Failure -> failed("delete", r)
        }
    }

    fun noticeShown() = _state.update { it.copy(notice = null) }

    private fun fix(block: suspend () -> FixNotice) {
        if (_state.value.busy) return
        _state.update { State(busy = true) }
        viewModelScope.launch { _state.value = State(notice = block()) }
    }

    private fun failed(verb: String, failure: PkResult.Failure): FixNotice.Failed {
        val error = failure.error
        return when {
            failure.isUnauthorized ->
                FixNotice.Failed("PluralKit no longer accepts this token. Sign out, then connect again.", unauthorized = true)
            error is PluralKitHttpException && error.isRateLimited -> FixNotice.Failed("PluralKit is busy. Try again in a minute.")
            error is PluralKitHttpException -> FixNotice.Failed("PluralKit couldn't $verb it: ${error.reason}")
            error is IOException -> FixNotice.Failed("Couldn't reach PluralKit. Check your connection and try again.")
            else -> FixNotice.Failed("Couldn't $verb it: ${error.message}")
        }
    }

    @Suppress("UNCHECKED_CAST")
    class Factory(private val repository: PluralKitRepository) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T = SwitchFixViewModel(repository) as T
    }
}
