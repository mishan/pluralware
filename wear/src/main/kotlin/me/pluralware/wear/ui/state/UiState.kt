package me.pluralware.wear.ui.state

import java.time.Instant
import me.pluralware.shared.model.Member
import me.pluralware.shared.model.Switch

/** Generic three-state container used by simple screens. */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    data class Error(val message: String, val canRetry: Boolean = true) : UiState<Nothing>
    data class Content<T>(val value: T) : UiState<T>
}

/**
 * Home screen state: the current switch plus, for each fronter, how long
 * they have been continuously fronting across switches.
 */
data class FrontersState(
    val switch: Switch?,
    val streaks: Map<String, FronterStreak> = emptyMap(),
)

/**
 * How long a single member has been continuously fronting.
 *
 * [since] is the timestamp of the oldest switch in the contiguous run (going
 * back through history) where they were a fronter. If they just joined the
 * front, it equals the current switch's own timestamp.
 *
 * [truncated] is true when we ran out of history while the member was still
 * fronting — i.e. the streak might have started earlier than [since] but our
 * fetch window cuts off there. UI renders this as a ">" prefix on the
 * duration so the uncertainty is visible.
 */
data class FronterStreak(
    val since: Instant,
    val truncated: Boolean = false,
)

/** Member-picker has a richer state: content + the user's working selection. */
data class PickerState(
    val members: UiState<List<Member>> = UiState.Loading,
    val selectedUuids: Set<String> = emptySet(),
    val submitting: Boolean = false,
    /** True once we've fetched current fronters and applied them as the initial selection. */
    val seeded: Boolean = false,
    /** Set briefly after a successful switch so the screen can flash confirmation before navigating away. */
    val confirmed: Boolean = false,
)
