package me.pluralware.shared.handoff

import com.google.android.gms.wearable.DataMap
import me.pluralware.shared.notify.Friend
import me.pluralware.shared.notify.SharingConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharingHandoffTest {

    @Test
    fun `a config payload reads back whole`() {
        val config = SharingConfig(title = "Sample", friends = listOf(Friend.Simple("1", "Kit", "pw_a")))
        val map = DataMap().apply { putString("config", config.toJson()) }
        assertEquals(config, SharingHandoff.read(map))
    }

    @Test
    fun `a missing or corrupt payload reads as nothing`() {
        assertNull(SharingHandoff.read(DataMap()))
        assertNull(SharingHandoff.read(DataMap().apply { putString("config", "{nope") }))
    }
}
