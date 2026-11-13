package me.pluralware.shared.api

import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import me.pluralware.shared.api.dto.CreateSwitchRequest
import me.pluralware.shared.api.dto.MemberDto
import me.pluralware.shared.api.dto.SwitchFullDto
import me.pluralware.shared.api.dto.SwitchRefsDto
import me.pluralware.shared.api.dto.SystemDto
import me.pluralware.shared.model.MemberField
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.time.Duration
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
        description = "Likes tea.",
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
        assertEquals("Likes tea.", members[0].description)
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
    fun `a 401 from any endpoint surfaces as an unauthorized PluralKitHttpException`() = runTest {
        val api = mockk<PluralKitApi>()
        val errorBody = "".toResponseBody("application/json".toMediaType())
        coEvery { api.getOwnMembers() } throws HttpException(Response.error<Any>(401, errorBody))

        try {
            client(api).getOwnMembers()
            error("expected PluralKitHttpException")
        } catch (e: PluralKitHttpException) {
            assertEquals(401, e.statusCode)
            assertTrue(e.isUnauthorized)
        }
    }

    @Test
    fun `a 429 carries the server's Retry-After`() = runTest {
        val api = mockk<PluralKitApi>()
        val raw = okhttp3.Response.Builder()
            .request(okhttp3.Request.Builder().url("https://api.pluralkit.me/v2/systems/@me/fronters").build())
            .protocol(okhttp3.Protocol.HTTP_1_1)
            .code(429)
            .message("Too Many Requests")
            .header("Retry-After", "7")
            .build()
        val errorBody = "".toResponseBody("application/json".toMediaType())
        coEvery { api.getCurrentFronters() } returns Response.error(errorBody, raw)

        try {
            client(api).getCurrentFronters()
            error("expected PluralKitHttpException")
        } catch (e: PluralKitHttpException) {
            assertTrue(e.isRateLimited)
            assertEquals(Duration.ofSeconds(7), e.retryAfter)
        }
    }

    @Test
    fun `PluralKit's error code and field errors come through`() = runTest {
        val api = mockk<PluralKitApi>()
        val body = """{"code":40001,"message":"Error parsing JSON model","errors":{"name":[{"message":"Member name can not be set to null."}],"description":[{"message":"Too long.","max_length":1000,"actual_length":1200}]}}"""
        coEvery { api.getOwnMembers() } throws
            HttpException(Response.error<Any>(400, body.toResponseBody("application/json".toMediaType())))

        try {
            client(api).getOwnMembers()
            error("expected PluralKitHttpException")
        } catch (e: PluralKitHttpException) {
            assertEquals(400, e.statusCode)
            assertEquals(40001, e.code)
            assertTrue(e.message, e.message!!.contains("Error parsing JSON model"))
            assertEquals(
                mapOf("name" to listOf("Member name can not be set to null."), "description" to listOf("Too long.")),
                e.fieldErrors,
            )
        }
    }

    @Test
    fun `an error PluralKit returns, rather than throws, carries its code too`() = runTest {
        val api = mockk<PluralKitApi>()
        val body = """{"code":30004,"message":"Unauthorized to view current fronters."}"""
        coEvery { api.getCurrentFronters() } returns
            Response.error(403, body.toResponseBody("application/json".toMediaType()))

        try {
            client(api).getCurrentFronters()
            error("expected PluralKitHttpException")
        } catch (e: PluralKitHttpException) {
            assertEquals(30004, e.code)
            assertTrue(e.message, e.message!!.contains("Unauthorized to view current fronters."))
        }
    }

    @Test
    fun `an error body that isn't PluralKit's leaves the code and field errors empty`() {
        val bodies = listOf(
            null, "", "<html>Bad gateway</html>", "[1,2]", "\"text\"", """{"message":"no code"}""", """{"code":"x"}""",
            """{"code":{"value":40001},"message":{"text":"x"}}""",
        )
        for (body in bodies) {
            val parsed = parseErrorBody(body)
            assertNull(body, parsed.code)
            assertEquals(body, emptyMap<String, List<String>>(), parsed.fieldErrors)
        }
        assertNull(parseErrorBody("""{"code":40001,"message":{"text":"x"}}""").message)
        assertEquals(20007, parseErrorBody("""{"code":20007,"message":"Switch not found."}""").code)
    }

    @Test
    fun `field errors keep only messages PluralKit actually gave`() {
        val cases = listOf(
            """{"errors":{"name":"oops"}}""" to emptyMap(),
            """{"errors":[{"message":"Bad."}]}""" to emptyMap(),
            """{"errors":{"name":["oops", {"message":"Too long."}, {"other":1}, {"message":null}, {"message":{"x":1}}]}}""" to
                mapOf("name" to listOf("Too long.")),
            """{"errors":{"name":[{"other":1}], "color":[{"message":"Bad."}, {"message":"Worse."}]}}""" to
                mapOf("color" to listOf("Bad.", "Worse.")),
        )
        for ((body, expected) in cases) assertEquals(body, expected, parseErrorBody(body).fieldErrors)
        assertEquals("Switch not found.", parseErrorBody("""{"code":20007,"message":"Switch not found."}""").message)
        assertNull(parseErrorBody("""{"code":20007}""").message)
    }

    @Test
    fun `a member list fetched before an edit can't bring back the old name`() = runTest(UnconfinedTestDispatcher()) {
        val api = mockk<PluralKitApi>()
        val slowFetch = CompletableDeferred<Unit>()
        var fetches = 0
        val renamed = sampleMemberDto.copy(name = "Alexis")
        coEvery { api.getOwnMembers() } coAnswers {
            // The first fetch is under way when the edit happens; PluralKit had the old name then.
            if (fetches++ == 0) { slowFetch.await(); listOf(sampleMemberDto) } else listOf(renamed)
        }
        coEvery { api.updateMember("uuid-alpha", any()) } returns renamed
        coEvery { api.getRecentSwitches(any()) } returns
            listOf(SwitchRefsDto(id = "sw", timestamp = "2026-11-13T14:00:00Z", members = listOf("alpha")))
        val client = client(api)

        launch { client.getOwnMembers() }
        client.updateMember("uuid-alpha", mapOf(MemberField.Name to "Alexis"))
        slowFetch.complete(Unit)

        assertEquals(listOf("Alexis"), client.getRecentSwitches(10).single().members.map { it.name })
    }

    @Test
    fun `a slow switch registration can't bring back a member's old name`() = runTest(UnconfinedTestDispatcher()) {
        val api = mockk<PluralKitApi>()
        val slowCreate = CompletableDeferred<Unit>()
        val renamed = sampleMemberDto.copy(name = "Alexis")
        coEvery { api.getOwnMembers() } returns listOf(sampleMemberDto)
        // PluralKit answered the registration with the name it had then.
        coEvery { api.createSwitch(any()) } coAnswers {
            slowCreate.await()
            SwitchFullDto(id = "sw", timestamp = "2026-11-13T14:00:00Z", members = listOf(sampleMemberDto))
        }
        coEvery { api.updateMember("uuid-alpha", any()) } returns renamed
        coEvery { api.getRecentSwitches(any()) } returns
            listOf(SwitchRefsDto(id = "sw", timestamp = "2026-11-13T14:00:00Z", members = listOf("alpha")))
        val client = client(api)
        client.getOwnMembers()

        launch { client.registerSwitch(listOf("uuid-alpha")) }
        client.updateMember("uuid-alpha", mapOf(MemberField.Name to "Alexis"))
        slowCreate.complete(Unit)

        assertEquals(listOf("Alexis"), client.getRecentSwitches(10).single().members.map { it.name })
    }

    @Test
    fun `a slow switch registration can't bring back names forgotten meanwhile`() = runTest(UnconfinedTestDispatcher()) {
        val api = mockk<PluralKitApi>()
        val slowCreate = CompletableDeferred<Unit>()
        var renamedElsewhere = false
        coEvery { api.getOwnMembers() } answers { listOf(if (renamedElsewhere) sampleMemberDto.copy(name = "Sam") else sampleMemberDto) }
        coEvery { api.createSwitch(any()) } coAnswers {
            slowCreate.await()
            SwitchFullDto(id = "sw", timestamp = "2026-11-13T14:00:00Z", members = listOf(sampleMemberDto))
        }
        coEvery { api.getRecentSwitches(any()) } returns
            listOf(SwitchRefsDto(id = "sw", timestamp = "2026-11-13T14:00:00Z", members = listOf("alpha")))
        val client = client(api)

        launch { client.registerSwitch(listOf("uuid-alpha")) }
        // Renamed on the phone; the watch is told and forgets its members.
        renamedElsewhere = true
        client.forgetMembers()
        slowCreate.complete(Unit)

        assertEquals(listOf("Sam"), client.getRecentSwitches(10).single().members.map { it.name })
    }

    @Test
    fun `a member list fetch gives up waiting for edits to stop, after three tries`() = runTest {
        val api = mockk<PluralKitApi>()
        lateinit var client: PluralKitClient
        var fetches = 0
        coEvery { api.updateMember(any(), any()) } returns sampleMemberDto
        coEvery { api.getOwnMembers() } coAnswers {
            fetches++
            // An edit lands during every fetch.
            client.updateMember("uuid-alpha", mapOf(MemberField.Pronouns to "she/her"))
            listOf(sampleMemberDto)
        }
        client = client(api)

        val members = client.getOwnMembers()

        assertEquals(listOf("Alex"), members.map { it.name })
        assertEquals(3, fetches)
    }

    @Test
    fun `parseRetryAfter reads delta-seconds and ignores anything else`() {
        assertEquals(Duration.ofSeconds(30), parseRetryAfter(" 30 "))
        assertEquals(Duration.ZERO, parseRetryAfter("0"))
        assertNull(parseRetryAfter(null))
        assertNull(parseRetryAfter("-1"))
        assertNull(parseRetryAfter("Wed, 21 Oct 2026 07:28:00 GMT"))
    }

    @Test
    fun `user agent names the app version and a contact URL`() {
        val ua = userAgent("1.2.3")
        assertTrue(ua.startsWith("PluralWare/1.2.3 "))
        assertTrue("https://github.com/mishan/pluralware" in ua)
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
