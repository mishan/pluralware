package me.pluralware.wear.ui.state

import java.time.Instant
import me.pluralware.shared.api.PluralKitHttpException
import me.pluralware.shared.model.Member
import me.pluralware.shared.model.Switch
import me.pluralware.shared.repository.PkResult

/** Generic three-state container used by simple screens. */
sealed interface UiState<out T> {
    data object Loading : UiState<Nothing>
    /**
     * [unauthorized] means PluralKit rejected the token: retrying can't help,
     * so the screen offers a sign-out instead.
     */
    data class Error(val message: String, val unauthorized: Boolean = false) : UiState<Nothing>
    data class Content<T>(val value: T) : UiState<T>
}

/** The error state for a failed call, worded for why it failed. */
fun PkResult.Failure.toUiError(fallback: String): UiState.Error =
    UiState.Error(message = error.userMessage(fallback), unauthorized = isUnauthorized)

private fun Throwable.userMessage(fallback: String): String {
    val http = this as? PluralKitHttpException
    return when {
        http?.isUnauthorized == true ->
            "PluralKit no longer accepts this token. Sign out, then connect again from your phone."
        http?.isRateLimited == true -> "PluralKit is busy. Try again in a minute."
        else -> message ?: fallback
    }
}

/**
 * Home screen state: the current switch plus, for each fronter, how long
 * they have been continuously fronting across switches.
 */
data class FrontersState(
    val switch: Switch?,
    val streaks: Map<String, FronterStreak> = emptyMap(),
    /** True while a background/manual refresh is in flight over existing content. */
    val refreshing: Boolean = false,
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
    /**
     * Selected members in front order. Order is significant: index 0 is the
     * primary fronter PluralKit uses for proxying. An empty list means a
     * switch-out (nobody fronting).
     */
    val selectedUuids: List<String> = emptyList(),
    val submitting: Boolean = false,
    /** True once we've fetched current fronters and applied them as the initial selection. */
    val seeded: Boolean = false,
    /** Set briefly after a successful switch so the screen can flash confirmation before navigating away. */
    val confirmed: Boolean = false,
)
