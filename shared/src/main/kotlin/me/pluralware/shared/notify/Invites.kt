package me.pluralware.shared.notify

import java.util.Base64
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The two halves of the Private-mode handshake (docs/notifications-design.md §4.1).
 *
 * Both travel as text, `<prefix><base64url JSON>`, so they survive being pasted
 * through any chat app. [parse] finds one anywhere in the text it's given,
 * including inside a link's `#fragment`, which is how the web receiver will
 * carry an invite.
 */

/** From the sharing system to a friend: what they need to subscribe. */
@Serializable
data class Invite(
    val system: String,
    /** The system's VAPID public key, base64url; the friend's subscription is bound to it. */
    val vapid: String,
    val v: Int = 1,
) {
    fun encode(): String = PREFIX + encodePayload(serializer(), this)

    companion object {
        const val PREFIX = "pluralware-invite:"

        fun parse(text: String): Invite? = decodeFrom(text, PREFIX, serializer())
    }
}

/**
 * From a friend back to the system: where and how to send them pushes.
 * [auth] is a secret, so a follow code is a credential (§4.1).
 */
@Serializable
data class FollowCode(
    val name: String,
    val endpoint: String,
    /** The friend's P-256 public key, base64url, 65-byte uncompressed point. */
    val p256dh: String,
    /** The friend's 16-byte auth secret, base64url. */
    val auth: String,
    val v: Int = 1,
) {
    fun encode(): String = PREFIX + encodePayload(serializer(), this)

    companion object {
        const val PREFIX = "pluralware-follow:"

        fun parse(text: String): FollowCode? = decodeFrom(text, PREFIX, serializer())?.takeIf {
            it.endpoint.startsWith("https://") &&
                runCatching { B64.decode(it.p256dh).size == 65 && B64.decode(it.auth).size == 16 }
                    .getOrDefault(false)
        }
    }
}

private val json = Json { ignoreUnknownKeys = true }

private fun <T> encodePayload(serializer: KSerializer<T>, value: T): String =
    B64.encode(json.encodeToString(serializer, value).toByteArray())

private fun <T> decodeFrom(text: String, prefix: String, serializer: KSerializer<T>): T? {
    val start = text.indexOf(prefix).takeIf { it >= 0 } ?: return null
    val payload = text.substring(start + prefix.length).takeWhile { it.isLetterOrDigit() || it == '-' || it == '_' }
    return runCatching { json.decodeFromString(serializer, String(B64.decode(payload))) }.getOrNull()
}

/** Unpadded base64url, the encoding Web Push uses for keys. */
internal object B64 {
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val decoder = Base64.getUrlDecoder()
    fun encode(bytes: ByteArray): String = encoder.encodeToString(bytes)
    fun decode(text: String): ByteArray = decoder.decode(text.trimEnd('='))
}
