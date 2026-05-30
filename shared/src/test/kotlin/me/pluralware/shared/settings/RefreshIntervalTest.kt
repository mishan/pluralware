package me.pluralware.shared.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class RefreshIntervalTest {

    @Test
    fun `fromSeconds maps known presets`() {
        assertEquals(RefreshInterval.OFF, RefreshInterval.fromSeconds(0))
        assertEquals(RefreshInterval.SEC_30, RefreshInterval.fromSeconds(30))
        assertEquals(RefreshInterval.MIN_1, RefreshInterval.fromSeconds(60))
        assertEquals(RefreshInterval.MIN_5, RefreshInterval.fromSeconds(300))
    }

    @Test
    fun `fromSeconds falls back to default for unknown values`() {
        assertEquals(RefreshInterval.DEFAULT, RefreshInterval.fromSeconds(45))
        assertEquals(RefreshInterval.DEFAULT, RefreshInterval.fromSeconds(-1))
    }
}
