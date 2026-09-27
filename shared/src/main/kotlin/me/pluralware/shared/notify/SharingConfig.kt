package me.pluralware.shared.notify

import java.security.SecureRandom
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Everything the watch needs to tell friends about a switch. The phone owns it
 * (the sharing screen edits it) and hands a copy to the watch, which sends.
 * See docs/notifications-design.md.
 *
 * It holds secrets — the VAPID private key, friends' follow codes, the ntfy
 * token — so both devices keep it in encrypted storage only.
 */
@Serializable
data class SharingConfig(
    /** Notification title friends see, e.g. the system name. */
    val title: String = DEFAULT_TITLE,
    /** Members who may be named; everyone else collapses into "someone". */
    val sharedMemberUuids: Set<String> = emptySet(),
    val friends: List<Friend> = emptyList(),
    /** Signs Private-mode pushes; generated on first invite (§4.3). */
    val vapid: VapidKeys? = null,
    /** Carries Simple-mode messages. */
    val ntfy: NtfyServer? = null,
) {
    val isSharing: Boolean get() = friends.isNotEmpty()

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        const val DEFAULT_TITLE = "PluralWare"

        private val json = Json { ignoreUnknownKeys = true }

        fun fromJson(text: String): SharingConfig = json.decodeFromString(serializer(), text)
    }
}

/** Someone who receives this system's switches, in one of the two delivery modes. */
@Serializable
sealed interface Friend {
    val id: String
    val label: String

    /** Encrypted Web Push to the friend's own device (§4). */
    @Serializable
    @SerialName("private")
    data class Private(
        override val id: String,
        override val label: String,
        val followCode: FollowCode,
    ) : Friend

    /** Plain messages on an ntfy topic (§5). */
    @Serializable
    @SerialName("simple")
    data class Simple(
        override val id: String,
        override val label: String,
        val topic: String,
    ) : Friend

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}

/** A system's VAPID key pair, base64url: the 65-byte uncompressed P-256 point and the 32-byte scalar. */
@Serializable
data class VapidKeys(
    val publicKey: String,
    val privateKey: String,
)

/** An ntfy server for Simple mode; [accessToken] only on a server with accounts (§5.1). */
@Serializable
data class NtfyServer(
    val baseUrl: String,
    val accessToken: String? = null,
)

/** Simple-mode topic names: `pw_` and 20 random `[a-z0-9]` characters, about 100 bits (§5.2). */
object NtfyTopics {
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"
    private const val LENGTH = 20
    private val random = SecureRandom()

    fun generate(): String = buildString {
        append("pw_")
        repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) }
    }

    /** `https://server/topic`: what the friend subscribes to. */
    fun url(server: NtfyServer, topic: String): String = server.baseUrl.trimEnd('/') + "/" + topic
}
