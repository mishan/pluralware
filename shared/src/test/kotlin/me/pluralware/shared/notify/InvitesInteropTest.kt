package me.pluralware.shared.notify

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The same fixtures as web-receiver/test/formats.test.mjs, so the apps and the
 * web receiver can't drift apart on the wire format.
 */
class InvitesInteropTest {

    private val vapid = "BAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8gISIjJCUmJygpKissLS4vMDEyMzQ1Njc4OTo7PD0-P0A"
    private val invite = "pluralware-invite:eyJzeXN0ZW0iOiJTYW1wbGUiLCJ2YXBpZCI6IkJBRUNBd1FGQmdjSUNRb0xEQTBPRHhBUkVoTVVGUllYR0JrYUd4d2RIaDhnSVNJakpDVW1KeWdwS2lzc0xTNHZNREV5TXpRMU5qYzRPVG83UEQwLVAwQSJ9"
    private val followCode = "pluralware-follow:eyJuYW1lIjoiU2FtIOKcqCIsImVuZHBvaW50IjoiaHR0cHM6Ly9mY20uZ29vZ2xlYXBpcy5jb20vZmNtL3NlbmQvYWJjOmRlZiIsInAyNTZkaCI6IkJQNzlfUHY2LWZqMzl2WDA4X0x4OE9fdTdlenI2dW5vNS1ibDVPUGk0ZURmM3QzYzI5cloyTmZXMWRUVDB0SFF6ODdOek12S3ljakh4c1hFdzhMQndMOCIsImF1dGgiOiJBQU1HQ1F3UEVoVVlHeDRoSkNjcUxRIiwidiI6MX0"

    @Test
    fun `writes the invite the web receiver reads`() {
        assertEquals(invite, Invite(system = "Sample", vapid = vapid).encode())
    }

    @Test
    fun `reads the follow code the web receiver writes`() {
        assertEquals(
            FollowCode(
                name = "Sam ✨",
                endpoint = "https://fcm.googleapis.com/fcm/send/abc:def",
                p256dh = "BP79_Pv6-fj39vX08_Lx8O_u7ezr6uno5-bl5OPi4eDf3t3c29rZ2NfW1dTT0tHQz87NzMvKycjHxsXEw8LBwL8",
                auth = "AAMGCQwPEhUYGx4hJCcqLQ",
            ),
            FollowCode.parse(followCode),
        )
    }

    @Test
    fun `an invite link opens the web receiver with the invite in its fragment`() {
        val link = Invite(system = "Sample", vapid = vapid).link()
        assertEquals("${Invite.WEB_RECEIVER}#$invite", link)
        assertEquals(Invite("Sample", vapid), Invite.parse(link))
    }
}
