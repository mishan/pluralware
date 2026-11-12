package me.pluralware.shared.handoff

import org.junit.Assert.assertEquals
import org.junit.Test

class OtherDeviceTest {

    @Test
    fun `a device drops its own items`() {
        val items = listOf("phone" to "mine", "watch" to "theirs", null to "unknown")

        assertEquals(listOf("theirs", "unknown"), OtherDevice.payloads("phone", items))
    }

    @Test
    fun `it keeps them all when it can't tell which are its own`() {
        val items = listOf("phone" to "mine", "watch" to "theirs")

        assertEquals(listOf("mine", "theirs"), OtherDevice.payloads(null, items))
    }
}
