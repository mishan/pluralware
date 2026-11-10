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

    @Test
    fun `a status payload reads back with when each friend was found gone`() {
        val gone = mapOf("f1" to 1_000L, "f2" to 2_000L)
        val map = DataMap().also { SharingHandoff.writeStatus(gone, it) }

        assertEquals(gone, SharingHandoff.readStatus(map, receivedAtEpochMillis = 9_000L))
    }

    @Test
    fun `a status payload still carries the bare ids older versions read`() {
        val map = DataMap().also { SharingHandoff.writeStatus(mapOf("f1" to 1_000L), it) }

        assertEquals(listOf("f1"), map.getStringArrayList("gone"))
    }

    @Test
    fun `an older version's ids count as found gone when received`() {
        val map = DataMap().apply { putStringArrayList("gone", arrayListOf("f1", "f2")) }

        assertEquals(mapOf("f1" to 9_000L, "f2" to 9_000L), SharingHandoff.readStatus(map, receivedAtEpochMillis = 9_000L))
    }

    @Test
    fun `an empty status payload clears nothing in particular`() {
        assertEquals(emptyMap<String, Long>(), SharingHandoff.readStatus(DataMap(), receivedAtEpochMillis = 9_000L))
    }
}
