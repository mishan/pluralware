package me.pluralware.shared.notify

import com.google.crypto.tink.apps.webpush.WebPushHybridEncrypt
import com.google.crypto.tink.subtle.EllipticCurves
import com.google.crypto.tink.subtle.EllipticCurves.CurveType
import com.google.crypto.tink.subtle.EllipticCurves.PointFormatType
import java.math.BigInteger
import java.net.URI
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.time.Duration
import java.time.Instant

/**
 * Private-mode payload encryption (RFC 8291, `aes128gcm`), via Tink's
 * apps-webpush — see docs/notifications-design.md §4.2 for why Tink.
 */
object WebPushCrypto {
    /** Header (86) + GCM tag (16) + padding delimiter (1). */
    const val OVERHEAD = 103

    /** Ciphertext sizes a push is padded up to, so its length hides how many names it holds. */
    val BUCKETS = listOf(512, 1024, 2048, 4096)

    fun encrypt(followCode: FollowCode, plaintext: ByteArray): ByteArray {
        val bucket = BUCKETS.firstOrNull { plaintext.size + OVERHEAD <= it }
            ?: throw IllegalArgumentException("payload too large for one Web Push record")
        return WebPushHybridEncrypt.Builder()
            .withAuthSecret(B64.decode(followCode.auth))
            .withRecipientPublicKey(B64.decode(followCode.p256dh))
            .withPaddingSize(bucket - OVERHEAD - plaintext.size)
            .build()
            .encrypt(plaintext, null)
    }
}

/**
 * VAPID (RFC 8292): the ES256-signed JWT that identifies this system to push
 * services. A friend's subscription is bound to the public key it was made
 * with, so one key per system, shared by every sender (§4.3).
 */
object Vapid {
    /** `sub` claim: how a push service operator can reach whoever runs this sender. */
    const val SUBJECT = "https://github.com/mishan/pluralware"

    private val TOKEN_LIFETIME: Duration = Duration.ofHours(12)
    private const val SCALAR_SIZE = 32

    fun generate(): VapidKeys {
        val pair = EllipticCurves.generateKeyPair(CurveType.NIST_P256)
        val public = EllipticCurves.pointEncode(
            CurveType.NIST_P256,
            PointFormatType.UNCOMPRESSED,
            (pair.public as ECPublicKey).w,
        )
        val scalar = (pair.private as ECPrivateKey).s.toFixedBytes(SCALAR_SIZE)
        return VapidKeys(publicKey = B64.encode(public), privateKey = B64.encode(scalar))
    }

    /** The `Authorization` header value for a push to [endpoint]. */
    fun authorization(endpoint: String, keys: VapidKeys, now: Instant): String =
        "vapid t=${jwt(endpoint, keys, now)}, k=${keys.publicKey}"

    internal fun jwt(endpoint: String, keys: VapidKeys, now: Instant): String {
        val header = B64.encode("""{"typ":"JWT","alg":"ES256"}""".toByteArray())
        val exp = now.plus(TOKEN_LIFETIME).epochSecond
        val claims = B64.encode(
            """{"aud":"${audience(endpoint)}","exp":$exp,"sub":"$SUBJECT"}""".toByteArray(),
        )
        val signingInput = "$header.$claims"
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(EllipticCurves.getEcPrivateKey(CurveType.NIST_P256, B64.decode(keys.privateKey)))
            update(signingInput.toByteArray())
            sign()
        }
        // JWS wants the raw r‖s form, not JCA's DER.
        val signature = EllipticCurves.ecdsaDer2Ieee(der, 2 * SCALAR_SIZE)
        return "$signingInput.${B64.encode(signature)}"
    }

    /** The push service's origin: scheme, host, and any explicit port. */
    internal fun audience(endpoint: String): String {
        val uri = URI(endpoint)
        val port = if (uri.port == -1) "" else ":${uri.port}"
        return "${uri.scheme}://${uri.host}$port"
    }

    private fun BigInteger.toFixedBytes(size: Int): ByteArray {
        val bytes = toByteArray()
        return when {
            bytes.size == size -> bytes
            bytes.size > size -> bytes.copyOfRange(bytes.size - size, bytes.size) // sign byte
            else -> ByteArray(size - bytes.size) + bytes
        }
    }
}
