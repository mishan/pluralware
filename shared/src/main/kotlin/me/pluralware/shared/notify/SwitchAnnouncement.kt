package me.pluralware.shared.notify

import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.pluralware.shared.model.Switch

/**
 * What friends are told about a switch (docs/notifications-design.md §6).
 * Members not in the shared set are never named; however many there are, they
 * read as "someone", so the text doesn't give away how many are hidden.
 */
object SwitchAnnouncement {

    fun text(switch: Switch, sharedMemberUuids: Set<String>): String {
        if (switch.isSwitchOut) return "Switched out"
        val named = switch.members.filter { it.uuid in sharedMemberUuids }.map { it.displayLabel }
        val anyHidden = named.size < switch.members.size
        return when {
            named.isEmpty() -> "Someone is fronting"
            anyHidden -> "${list(named + "someone else")} are fronting"
            named.size == 1 -> "${named[0]} is fronting"
            else -> "${list(named)} are fronting"
        }
    }

    /** "A", "A and B", "A, B, and C". */
    private fun list(items: List<String>): String = when (items.size) {
        1 -> items[0]
        2 -> "${items[0]} and ${items[1]}"
        else -> items.dropLast(1).joinToString(", ") + ", and " + items.last()
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

        fun fromBytes(bytes: ByteArray): SwitchPayload? =
            runCatching { json.decodeFromString(serializer(), String(bytes)) }.getOrNull()
    }
}
