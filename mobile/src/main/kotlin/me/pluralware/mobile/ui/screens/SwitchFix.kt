package me.pluralware.mobile.ui.screens

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import me.pluralware.shared.model.Switch
import me.pluralware.shared.ui.listOfNames

/** When a switch may move to: after the switch before it ([after], if known) and before the next one, or now. */
data class SwitchWindow(val after: Instant?, val before: Instant?) {
    fun allows(at: Instant, now: Instant): Boolean =
        (after == null || at.isAfter(after)) && at.isBefore(before ?: now)

    companion object {
        /** For [switches] newest first, as PluralKit lists them. */
        fun of(switches: List<Switch>, index: Int) =
            SwitchWindow(switches.getOrNull(index + 1)?.timestamp, switches.getOrNull(index - 1)?.timestamp)
    }
}

/** What the front becomes when the switch at [index] of [switches] (newest first) is deleted. */
fun deleteOutcome(switches: List<Switch>, index: Int, now: Instant, zone: ZoneId): String {
    val before = switches.getOrNull(index + 1)
    val next = switches.getOrNull(index - 1)
    val who = before?.takeUnless { it.isSwitchOut }?.members?.let { members -> listOfNames(members.map { it.displayLabel }) }
    return when {
        next == null && before == null -> "Nobody will be fronting"
        next == null -> "Front goes back to ${who ?: "nobody"}, since ${timeOf(before!!.timestamp, now, zone)}"
        who == null -> "Nobody will be fronting until ${timeOf(next.timestamp, now, zone)}"
        else -> "$who's front will run on until ${timeOf(next.timestamp, now, zone)}"
    }
}

private val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

/** The time alone for today, with the date otherwise. */
internal fun timeOf(at: Instant, now: Instant, zone: ZoneId): String {
    val local = at.atZone(zone)
    return (if (local.toLocalDate() == now.atZone(zone).toLocalDate()) time else dateTime).format(local)
}
