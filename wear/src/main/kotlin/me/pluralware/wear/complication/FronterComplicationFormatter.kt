package me.pluralware.wear.complication

import me.pluralware.shared.model.Member
import me.pluralware.shared.model.Switch

/**
 * Pure mapping from a [Switch] (or its absence) to the strings a LONG_TEXT
 * complication renders. Kept free of Android types so it's unit-testable on the
 * JVM; the service ([FronterComplicationService]) wraps these into
 * `ComplicationData`.
 *
 * Three switch states:
 *  - `null`     → the system has no registered switches (PluralKit 204).
 *  - switch-out → a [Switch] with no members.
 *  - fronting   → one or more members; order is meaningful (index 0 is
 *                 PluralKit's proxy fronter), so we never sort.
 */
internal object FronterComplicationFormatter {

    /** Static title shown alongside the body on faces that render long-text titles. */
    const val TITLE = "Fronter"

    /** How many names to spell out before collapsing the rest into "+N". */
    private const val MAX_NAMES = 2

    /** Body line for the complication. */
    fun body(switch: Switch?): String = when {
        switch == null -> "No switches yet"
        switch.isSwitchOut -> "Switched out"
        else -> "Fronting: " + joinNames(switch.members, capped = true)
    }

    /** Screen-reader description; never truncated so every fronter is read out. */
    fun contentDescription(switch: Switch?): String = when {
        switch == null -> "No registered switches"
        switch.isSwitchOut -> "Switched out — nobody fronting"
        else -> "Current fronter: " + joinNames(switch.members, capped = false)
    }

    private fun joinNames(members: List<Member>, capped: Boolean): String {
        val labels = members.map { it.displayLabel }
        if (!capped || labels.size <= MAX_NAMES) return labels.joinToString(", ")
        val shown = labels.take(MAX_NAMES).joinToString(", ")
        return "$shown +${labels.size - MAX_NAMES}"
    }
}
