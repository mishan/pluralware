package me.pluralware.shared.handoff

import com.google.android.gms.wearable.DataMap
import me.pluralware.shared.api.PluralKitToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TokenHandoffTest {

    @Test
    fun `a token payload reads as Connect`() {
        val map = DataMap().apply { putString("token", "abc123") }
        assertEquals(TokenHandoff.Message.Connect(PluralKitToken("abc123")), TokenHandoff.read(map))
    }

    @Test
    fun `a sign-out payload reads as SignOut`() {
        val map = DataMap().apply { putBoolean("signed_out", true) }
        assertEquals(TokenHandoff.Message.SignOut, TokenHandoff.read(map))
    }

    @Test
    fun `a blank or missing token reads as nothing`() {
        assertNull(TokenHandoff.read(DataMap()))
        assertNull(TokenHandoff.read(DataMap().apply { putString("token", "  ") }))
    }
}
