package me.pluralware.shared.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InvitesTest {

    private val code = FollowCode(
        name = "Sam",
        endpoint = "https://ntfy.example.org/upABC?up=1",
        p256dh = B64.encode(ByteArray(65) { 4 }),
        auth = B64.encode(ByteArray(16) { 7 }),
    )

    @Test
    fun `an invite survives the trip, even inside a link or a sentence`() {
        val invite = Invite(system = "Sample", vapid = Vapid.generate().publicKey)
        val encoded = invite.encode()
        assertEquals(invite, Invite.parse(encoded))
        assertEquals(invite, Invite.parse("https://example.org/follow#$encoded"))
        assertEquals(invite, Invite.parse("Follow us! $encoded (from PluralWare)"))
    }

    @Test
    fun `a follow code survives the trip`() {
        assertEquals(code, FollowCode.parse("here you go: ${code.encode()}"))
    }

    @Test
    fun `the two kinds don't parse as each other`() {
        assertNull(Invite.parse(code.encode()))
        assertNull(FollowCode.parse(Invite("S", "k").encode()))
    }

    @Test
    fun `follow codes with bad keys or a non-https endpoint are rejected`() {
        assertNull(FollowCode.parse(code.copy(endpoint = "http://insecure.example/x").encode()))
        assertNull(FollowCode.parse(code.copy(auth = B64.encode(ByteArray(8))).encode()))
        assertNull(FollowCode.parse(code.copy(p256dh = "not base64!").encode()))
    }

    @Test
    fun `garbage is not an invite`() {
        assertNull(Invite.parse("pluralware-invite:%%%"))
        assertNull(Invite.parse("hello"))
    }
}
