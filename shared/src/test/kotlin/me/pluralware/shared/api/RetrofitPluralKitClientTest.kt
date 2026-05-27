package me.pluralware.shared.api

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import me.pluralware.shared.api.dto.CreateSwitchRequest
import me.pluralware.shared.api.dto.MemberDto
import me.pluralware.shared.api.dto.SwitchFullDto
import me.pluralware.shared.api.dto.SwitchRefsDto
import me.pluralware.shared.api.dto.SystemDto
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.time.Instant

/**
 * Tests for the DTO↔domain mapping in [RetrofitPluralKitClient].
 *
 * Lives in the same package as the client so it can use the `internal`
 * constructor (the production path goes through [PluralKitClientFactory]).
 * The [PluralKitApi] is a mockk so these tests are pure JVM — no Retrofit,
 * no OkHttp, no network.
 */
class RetrofitPluralKitClientTest {

    private fun client(api: PluralKitApi) = RetrofitPluralKitClient(api)

    private val sampleMemberDto = MemberDto(
        id = "alpha",
        uuid = "uuid-alpha",
        name = "Alex",
        displayName = "Alex (display)",
        pronouns = "she/her",
        color = "ff6b9d",
        avatarUrl = "https://example/alex.png",
    )

    private val sampleSystemDto = SystemDto(
        id = "exmpl",
        uuid = "uuid-system",
        name = "Sample",
        tag = "| Sample",
    )

    @Test
    fun `getOwnSystem maps every field`() = runTest {
        val api = mockk<PluralKitApi>()
        coEvery { api.getOwnSystem() } returns sampleSystemDto

        val result = client(api).getOwnSystem()

        assertEquals("exmpl", result.id)
        assertEquals("uuid-system", result.uuid)
        assertEquals("Sample", result.name)
        assertEquals("| Sample", result.tag)
    }

    @Test
    fun `getOwnMembers maps display name and warms the resolver cache`() = runTest {
        val api = mockk<PluralKitApi>()
        coEvery { api.getOwnMembers() } returns listOf(sampleMemberDto)

        val members = client(api).getOwnMembers()

        assertEquals(1, members.size)
        assertEquals("Alex", members[0].name)
        assertEquals("Alex (display)", members[0].displayName)
        // verify getOwnMembers was called exactly once — the cache warmed,
        // so a subsequent history fetch shouldn't issue a second call.
        coVerify(exactly = 1) { api.getOwnMembers() }
    }

    @Test
    fun `getCurrentFronters returns null on 204 No Content`() = runTest {
        val api = mockk<PluralKitApi>()
        // Empty-body 204 response — the documented state for "no switches ever registered".
        coEvery { api.getCurrentFronters() } returns Response.success<SwitchFullDto?>(
            204,
            null,
        ) as Response<SwitchFullDto>

        val result = client(api).getCurrentFronters()

        assertNull(result)
    }

    @Test
    fun `getCurrentFronters maps switch with full member objects`() = runTest {
        val api = mockk<PluralKitApi>()
        coEvery { api.getCurrentFronters() } returns Response.success(
            SwitchFullDto(
                id = "uuid-switch-1",
                timestamp = "2026-01-15T14:30:00Z",
                members = listOf(sampleMemberDto),
            )
        )

        val result = client(api).getCurrentFronters()

        assertNotNull(result)
        assertEquals("uuid-switch-1", result!!.uuid) // JSON `id` -> domain `uuid`
        assertEquals(Instant.parse("2026-01-15T14:30:00Z"), result.timestamp)
        assertEquals(1, result.members.size)
        assertEquals("uuid-alpha", result.members[0].uuid)
    }

    @Test
    fun `getCurrentFronters throws on non-204 non-2xx response`() = runTest {
        val api = mockk<PluralKitApi>()
        val errorBody = "".toResponseBody("application/json".toMediaType())
        coEvery { api.getCurrentFronters() } returns Response.error(500, errorBody)

        try {
            client(api).getCurrentFronters()
            error("expected PluralKitHttpException")
        } catch (e: PluralKitHttpException) {
            assertEquals(500, e.statusCode)
        }
    }

    @Test
    fun `getRecentSwitches resolves member ids using the members endpoint`() = runTest {
        val api = mockk<PluralKitApi>()
        val bea = sampleMemberDto.copy(id = "brava", uuid = "uuid-brava", name = "Bea")
        coEvery { api.getOwnMembers() } returns listOf(sampleMemberDto, bea)
        coEvery { api.getRecentSwitches(any()) } returns listOf(
            SwitchRefsDto(
                id = "uuid-switch-history-1",
                timestamp = "2026-01-15T14:00:00Z",
                members = listOf("uuid-alpha", "uuid-brava"),
            ),
        )

        val switches = client(api).getRecentSwitches(10)

        assertEquals(1, switches.size)
        assertEquals(2, switches[0].members.size)
        assertEquals(setOf("Alex", "Bea"), switches[0].members.map { it.name }.toSet())
    }

    @Test
    fun `getRecentSwitches drops unknown member ids after one cache refresh`() = runTest {
        val api = mockk<PluralKitApi>()
        // The members endpoint always returns just Alex — the orphaned ID in the
        // switch never resolves, even after the cache refresh, so it gets dropped.
        coEvery { api.getOwnMembers() } returns listOf(sampleMemberDto)
        coEvery { api.getRecentSwitches(any()) } returns listOf(
            SwitchRefsDto(
                id = "uuid-switch-history-1",
                timestamp = "2026-01-15T14:00:00Z",
                members = listOf("uuid-alpha", "uuid-deleted-member"),
            ),
        )

        val switches = client(api).getRecentSwitches(10)

        assertEquals(1, switches[0].members.size)
        assertEquals("Alex", switches[0].members[0].name)
    }

    @Test
    fun `getRecentSwitches clamps limit to API ceiling`() = runTest {
        val api = mockk<PluralKitApi>()
        coEvery { api.getOwnMembers() } returns emptyList()
        coEvery { api.getRecentSwitches(any()) } returns emptyList()

        client(api).getRecentSwitches(500) // way over the docs' 100 cap

        coVerify { api.getRecentSwitches(100) }
    }

    @Test
    fun `registerSwitch with empty list creates a switch-out`() = runTest {
        val api = mockk<PluralKitApi>()
        val bodySlot = slot<CreateSwitchRequest>()
        coEvery { api.createSwitch(capture(bodySlot)) } returns SwitchFullDto(
            id = "uuid-new-switch",
            timestamp = "2026-01-15T14:31:00Z",
            members = emptyList(),
        )

        val switch = client(api).registerSwitch(emptyList())

        assertTrue(switch.isSwitchOut)
        assertEquals(emptyList<String>(), bodySlot.captured.members)
        // We deliberately don't send a timestamp — server uses "now", avoiding clock skew.
        assertNull(bodySlot.captured.timestamp)
    }

    @Test
    fun `registerSwitch passes uuids straight through and returns mapped switch`() = runTest {
        val api = mockk<PluralKitApi>()
        coEvery { api.createSwitch(any()) } returns SwitchFullDto(
            id = "uuid-new-switch",
            timestamp = "2026-01-15T14:31:00Z",
            members = listOf(sampleMemberDto),
        )

        val switch = client(api).registerSwitch(listOf("uuid-alpha"))

        coVerify { api.createSwitch(CreateSwitchRequest(members = listOf("uuid-alpha"))) }
        assertEquals(1, switch.members.size)
        assertEquals("Alex", switch.members[0].name)
    }
}
