package me.pluralware.shared.notify

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.pluralware.shared.model.Switch
import me.pluralware.shared.ui.listOfNames

/**
 * What friends are told about a switch (docs/notifications-design.md §6).
 * Members not in the shared set are never named; however many there are, they
 * read as "someone", so the text doesn't give away how many are hidden.
 */
object SwitchAnnouncement {

    /**
     * Said when a fix changed who's fronting now: Simple-mode friends keep
     * both messages, so this one says it replaces the other.
     */
    fun correction(front: Switch?, sharedMemberUuids: Set<String>): String {
        val text = textOf(front, sharedMemberUuids)
        // A name keeps its capital; "Someone…" and "Switched out" read on in lower case.
        val named = front != null && front.members.any { it.uuid in sharedMemberUuids }
        return "Correction: " + if (named) text else text.replaceFirstChar { it.lowercase() }
    }

    /** [text], with no switch at all reading as switched out. */
    fun textOf(front: Switch?, sharedMemberUuids: Set<String>): String =
        if (front == null) "Switched out" else text(front, sharedMemberUuids)

    fun text(switch: Switch, sharedMemberUuids: Set<String>): String {
        if (switch.isSwitchOut) return "Switched out"
        val named = switch.members.filter { it.uuid in sharedMemberUuids }.map { it.displayLabel }
        val anyHidden = named.size < switch.members.size
        return when {
            named.isEmpty() -> "Someone is fronting"
            anyHidden -> "${listOfNames(named + "someone else")} are fronting"
            named.size == 1 -> "${named[0]} is fronting"
            else -> "${listOfNames(named)} are fronting"
        }
    }
}

/**
 * The plaintext of a Private-mode push (§4.2). The receiver shows [text] under
 * [system], notes [switchedAt], and replaces its previous notification for the
 * system.
 */
@Serializable
data class SwitchPayload(
    val system: String,
    val text: String,
    /** ISO-8601 instant. */
    val switchedAt: String,
    val v: Int = 1,
) {
    fun toBytes(): ByteArray = json.encodeToString(serializer(), this).toByteArray()

    val switchedAtInstant: Instant? get() = runCatching { Instant.parse(switchedAt) }.getOrNull()

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun of(title: String, switch: Switch, sharedMemberUuids: Set<String>) = SwitchPayload(
            system = title,
            text = SwitchAnnouncement.text(switch, sharedMemberUuids),
            switchedAt = switch.timestamp.toString(),
        )

        /** With nobody's switch left to date it ([front] null), it's dated [now]. */
        fun correction(title: String, front: Switch?, sharedMemberUuids: Set<String>, now: Instant) = SwitchPayload(
            system = title,
            text = SwitchAnnouncement.correction(front, sharedMemberUuids),
            switchedAt = (front?.timestamp ?: now).toString(),
        )

        fun fromBytes(bytes: ByteArray): SwitchPayload? =
            runCatching { json.decodeFromString(serializer(), String(bytes)) }.getOrNull()
    }
}
