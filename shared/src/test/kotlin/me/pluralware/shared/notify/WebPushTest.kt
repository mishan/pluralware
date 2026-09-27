package me.pluralware.shared.notify

import com.google.crypto.tink.apps.webpush.WebPushHybridDecrypt
import com.google.crypto.tink.subtle.EllipticCurves
import com.google.crypto.tink.subtle.EllipticCurves.CurveType
import com.google.crypto.tink.subtle.EllipticCurves.PointFormatType
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPushTest {

    /** A receiver, as a browser or the UnifiedPush connector would make one. */
    private class Receiver {
        val pair = EllipticCurves.generateKeyPair(CurveType.NIST_P256)
        val auth = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val publicBytes: ByteArray = EllipticCurves.pointEncode(
            CurveType.NIST_P256, PointFormatType.UNCOMPRESSED, (pair.public as ECPublicKey).w,
        )
        val followCode = FollowCode("Sam", "https://push.example/abc", B64.encode(publicBytes), B64.encode(auth))

        fun decrypt(ciphertext: ByteArray): ByteArray = WebPushHybridDecrypt.Builder()
            .withAuthSecret(auth)
            .withRecipientPublicKey(publicBytes)
            .withRecipientPrivateKey(pair.private as ECPrivateKey)
            .build()
            .decrypt(ciphertext, null)
    }

    @Test
    fun `only the receiver's keys open a push, and it is padded to a bucket`() {
        val receiver = Receiver()
        val plaintext = SwitchPayload("Sample", "Alex is fronting", "2026-09-27T14:02:00Z").toBytes()

        val ciphertext = WebPushCrypto.encrypt(receiver.followCode, plaintext)

        assertEquals(512, ciphertext.size)
        assertArrayEquals(plaintext, receiver.decrypt(ciphertext))
    }

    @Test
    fun `long payloads move up a bucket, so length only hints within a bucket`() {
        val receiver = Receiver()
        assertEquals(512, WebPushCrypto.encrypt(receiver.followCode, ByteArray(512 - WebPushCrypto.OVERHEAD)).size)
        assertEquals(1024, WebPushCrypto.encrypt(receiver.followCode, ByteArray(512 - WebPushCrypto.OVERHEAD + 1)).size)
    }

    @Test
    fun `VAPID keys are a 65-byte point and a 32-byte scalar`() {
        repeat(20) {
            val keys = Vapid.generate()
            assertEquals(65, B64.decode(keys.publicKey).size)
            assertEquals(32, B64.decode(keys.privateKey).size)
            // 87 characters is what the UnifiedPush connector insists on.
            assertEquals(87, keys.publicKey.length)
        }
    }

    @Test
    fun `the VAPID JWT verifies against the public key and names the push origin`() {
        val keys = Vapid.generate()
        val now = Instant.parse("2026-09-27T12:00:00Z")

        val jwt = Vapid.jwt("https://fcm.googleapis.com/fcm/send/xyz", keys, now)
        val (header, claims, signature) = jwt.split(".")

        val verifier = Signature.getInstance("SHA256withECDSA").apply {
            initVerify(EllipticCurves.getEcPublicKey(CurveType.NIST_P256, PointFormatType.UNCOMPRESSED, B64.decode(keys.publicKey)))
            update("$header.$claims".toByteArray())
        }
        assertTrue(verifier.verify(EllipticCurves.ecdsaIeee2Der(B64.decode(signature))))

        val parsed = Json.parseToJsonElement(String(B64.decode(claims))).jsonObject
        assertEquals("https://fcm.googleapis.com", parsed["aud"]!!.jsonPrimitive.content)
        assertEquals(now.epochSecond + 12 * 3600, parsed["exp"]!!.jsonPrimitive.long)
        assertEquals(Vapid.SUBJECT, parsed["sub"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the audience keeps an explicit port and drops the path`() {
        assertEquals("https://ntfy.example.org:8443", Vapid.audience("https://ntfy.example.org:8443/upABC?up=1"))
    }

    @Test
    fun `the authorization header carries the token and the key`() {
        val keys = Vapid.generate()
        val header = Vapid.authorization("https://push.example/abc", keys, Instant.EPOCH)
        assertTrue(header.startsWith("vapid t="))
        assertTrue(header.endsWith(", k=${keys.publicKey}"))
    }
}
