package me.pluralware.shared.preview

import me.pluralware.shared.model.Member
import me.pluralware.shared.model.Switch
import me.pluralware.shared.model.SystemInfo
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Deterministic sample data for previews and tests. UUIDs are stable strings
 * so screenshot diffs don't churn between runs.
 */
object PreviewData {

    val members: List<Member> = listOf(
        Member("alpha", "uuid-alpha", "Alex", null, "she/her", "ff6b9d", null),
        Member("brava", "uuid-brava", "Bea", null, "they/them", "6bcfff", null),
        Member("charl", "uuid-charl", "Cam", null, "he/him", "9d6bff", null),
        Member("delta", "uuid-delta", "Dani", null, "she/they", "6bff9d", null),
        Member("echo0", "uuid-echo0", "Eli", null, "they/them", "ffcc6b", null),
        Member("foxtr", "uuid-foxtr", "Frey", null, "any", "ff6b6b", null),
    )

    val system = SystemInfo("smplx", "uuid-system", "Sample System", "| Sample")

    private val anchor: Instant = Instant.parse("2026-01-15T14:30:00Z")

    val currentSwitch = Switch(
        uuid = "switch-current",
        timestamp = anchor.minus(45, ChronoUnit.MINUTES),
        members = listOf(members[0], members[3]),
    )

    val switchOut = Switch("switch-out", anchor.minus(2, ChronoUnit.HOURS), emptyList())

    val historyRecent: List<Switch> = listOf(
        currentSwitch,
        Switch("s2", anchor.minus(3, ChronoUnit.HOURS), listOf(members[1])),
        Switch("s3", anchor.minus(8, ChronoUnit.HOURS), listOf(members[2], members[4])),
        switchOut,
        Switch("s5", anchor.minus(2, ChronoUnit.DAYS), listOf(members[5])),
        Switch("s6", anchor.minus(5, ChronoUnit.DAYS), listOf(members[0])),
    )
}
