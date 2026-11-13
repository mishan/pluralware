package me.pluralware.shared.api

import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import me.pluralware.shared.model.MemberField
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** The write calls against a fake PluralKit, checking the requests byte for byte. */
class PluralKitWritesTest {

    private val server = MockWebServer().apply { start() }
    private val client = PluralKitClientFactory.create(
        PluralKitToken("token"), appVersion = "test", enableLogging = false,
        baseUrl = server.url("/v2/").toString(),
    )

    @After fun tearDown() = server.shutdown()

    private fun respond(code: Int = 200, body: String = "") =
        server.enqueue(MockResponse().setResponseCode(code).setBody(body))

    private fun RecordedRequest.json() = Json.parseToJsonElement(body.readUtf8())

    private val alexJson = """{"id":"abcde","uuid":"uuid-alex","name":"Alexis","pronouns":"she/her","color":"e6a12b"}"""
    private val switchJson = """{"id":"uuid-sw","timestamp":"2026-11-13T14:00:00Z","members":[$alexJson]}"""

    @Test
    fun `a member update sends only the given fields, a cleared one as null`() = runTest {
        val cases = listOf(
            mapOf(MemberField.Pronouns to "she/her") to """{"pronouns":"she/her"}""",
            mapOf(MemberField.DisplayName to null) to """{"display_name":null}""",
            mapOf(MemberField.Name to "Alexis", MemberField.Color to "e6a12b", MemberField.Description to null) to
                """{"name":"Alexis","color":"e6a12b","description":null}""",
            mapOf(MemberField.AvatarUrl to "https://example.com/a.png") to """{"avatar_url":"https://example.com/a.png"}""",
        )
        for ((changes, expected) in cases) {
            respond(body = alexJson)

            val member = client.updateMember("uuid-alex", changes)

            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("/v2/members/uuid-alex", request.path)
            assertEquals("token", request.getHeader("Authorization"))
            assertEquals("application/json", request.getHeader("Content-Type")?.substringBefore(";"))
            assertEquals(changes.toString(), Json.parseToJsonElement(expected), request.json())
            assertEquals("Alexis", member.name)
        }
    }

    @Test
    fun `an edit that changes nothing sends nothing`() = runTest {
        assertEquals(
            IllegalArgumentException::class,
            runCatching { client.updateMember("uuid-alex", emptyMap()) }.exceptionOrNull()?.let { it::class },
        )
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `moving a switch sends its new start time, to the millisecond`() = runTest {
        // PluralKit parses times with .NET, which takes at most seven fractional digits.
        val cases = listOf(
            "2026-11-13T14:00:00Z" to "2026-11-13T14:00:00Z",
            "2026-11-13T14:00:00.250Z" to "2026-11-13T14:00:00.250Z",
            "2026-11-13T14:00:00.100Z" to "2026-11-13T14:00:00.100Z",
            "2026-11-13T14:00:00.123456789Z" to "2026-11-13T14:00:00.123Z",
        )
        for ((to, sent) in cases) {
            respond(body = switchJson)

            val switch = client.moveSwitch("uuid-sw", Instant.parse(to))

            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("/v2/systems/@me/switches/uuid-sw", request.path)
            assertEquals(to, Json.parseToJsonElement("""{"timestamp":"$sent"}"""), request.json())
            assertEquals(Instant.parse("2026-11-13T14:00:00Z"), switch.timestamp)
        }
    }

    @Test
    fun `changing a switch's members sends them in order`() = runTest {
        val cases = listOf(
            listOf("uuid-alex", "uuid-sam") to """["uuid-alex","uuid-sam"]""",
            listOf("uuid-sam", "uuid-alex") to """["uuid-sam","uuid-alex"]""",
            emptyList<String>() to "[]",
        )
        for ((members, expected) in cases) {
            respond(body = switchJson)

            val switch = client.setSwitchMembers("uuid-sw", members)

            val request = server.takeRequest()
            assertEquals("PATCH", request.method)
            assertEquals("/v2/systems/@me/switches/uuid-sw/members", request.path)
            assertEquals(Json.parseToJsonElement(expected), request.json())
            assertEquals(listOf("Alexis"), switch.members.map { it.name })
        }
    }

    @Test
    fun `deleting a switch`() = runTest {
        respond(code = 204)

        client.deleteSwitch("uuid-sw")

        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/v2/systems/@me/switches/uuid-sw", request.path)
    }

    @Test
    fun `a write PluralKit refuses surfaces its code`() = runTest {
        val cases = listOf<Triple<Int, Int, suspend () -> Unit>>(
            Triple(404, 20007) { client.deleteSwitch("gone") },
            Triple(404, 20008) { client.deleteSwitch("someone-elses") },
            Triple(404, 20002) { client.updateMember("gone", mapOf(MemberField.Name to "X")) },
            Triple(400, 40003) { client.setSwitchMembers("uuid-sw", listOf("uuid-alex", "uuid-alex")) },
            Triple(400, 40005) { client.moveSwitch("uuid-sw", Instant.parse("2026-11-13T14:00:00Z")) },
            Triple(400, 40004) { client.setSwitchMembers("uuid-sw", listOf("uuid-alex")) },
        )
        for ((status, code, call) in cases) {
            respond(status, """{"code":$code,"message":"Refused."}""")

            val e = runCatching { call() }.exceptionOrNull()

            assertEquals(code, (e as PluralKitHttpException).code)
        }
    }

    @Test
    fun `a rejected member edit says which field and why`() = runTest {
        respond(400, """{"code":40001,"message":"Error parsing JSON model","errors":{"avatar_url":[{"message":"Value must be a valid URL."}]}}""")

        val e = runCatching { client.updateMember("uuid-alex", mapOf(MemberField.AvatarUrl to "https://x")) }
            .exceptionOrNull() as PluralKitHttpException

        assertEquals(40001, e.code)
        assertEquals(mapOf("avatar_url" to listOf("Value must be a valid URL.")), e.fieldErrors)
    }

    @Test
    fun `a revoked token or a busy PluralKit is told apart on writes too`() = runTest {
        respond(401, """{"code":0,"message":"401: Missing or invalid Authorization header"}""")
        val unauthorized = runCatching { client.deleteSwitch("uuid-sw") }.exceptionOrNull() as PluralKitHttpException
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "3").setBody("{}"))
        val busy = runCatching { client.updateMember("uuid-alex", mapOf(MemberField.Name to "A")) }
            .exceptionOrNull() as PluralKitHttpException

        assertEquals(true, unauthorized.isUnauthorized)
        assertEquals(true, busy.isRateLimited)
        assertEquals(java.time.Duration.ofSeconds(3), busy.retryAfter)
    }

    @Test
    fun `an error without PluralKit's body still says what happened`() = runTest {
        server.enqueue(MockResponse().setStatus("HTTP/1.1 503 Service Unavailable"))

        val e = runCatching { client.deleteSwitch("uuid-sw") }.exceptionOrNull() as PluralKitHttpException

        assertEquals(503, e.statusCode)
        assertEquals(true, e.message!!.contains("Service Unavailable"))
        assertEquals(null, e.code)
    }

    @Test
    fun `privacy settings come through, private only when PluralKit says so`() = runTest {
        for ((privacy, private) in listOf(""","privacy":{"visibility":"private"}""" to true,
            ""","privacy":{"visibility":"public"}""" to false, "" to false)) {
            respond(body = """[{"id":"abcde","uuid":"uuid-alex","name":"Alex"$privacy}]""")
            assertEquals(privacy, private, client.getOwnMembers().single().isPrivate)
        }
        for ((privacy, private) in listOf(""","privacy":{"front_privacy":"private"}""" to true,
            ""","privacy":{"front_privacy":"public"}""" to false, "" to false)) {
            respond(body = """{"id":"exmpl","uuid":"uuid-sys"$privacy}""")
            assertEquals(privacy, private, client.getOwnSystem().frontIsPrivate)
        }
    }

    @Test
    fun `a connection dropped after the request was sent surfaces as a network error`() = runTest {
        // PluralKit may have applied it; the repository decides what a retry means (design §9).
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        val e = runCatching { client.moveSwitch("uuid-sw", Instant.parse("2026-11-13T14:00:00Z")) }.exceptionOrNull()

        assertEquals(true, e is java.io.IOException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `after forgetting members, history fetches them again`() = runTest {
        respond(body = """[{"id":"abcde","uuid":"uuid-alex","name":"Alex"}]""")
        client.getOwnMembers()
        client.forgetMembers()
        respond(body = """[{"id":"uuid-sw","timestamp":"2026-11-13T14:00:00Z","members":["abcde"]}]""")
        respond(body = """[{"id":"abcde","uuid":"uuid-alex","name":"Renamed on the phone"}]""")

        val history = client.getRecentSwitches(10)

        assertEquals(listOf("Renamed on the phone"), history.single().members.map { it.name })
    }

    @Test
    fun `a member list fetched before members were forgotten isn't cached over it`() = runTest {
        // The fetch lands with the old name only after forgetMembers ran.
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            var calls = 0
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = when {
                request.path!!.endsWith("/members") && calls++ == 0 -> {
                    runBlocking { client.forgetMembers() }
                    MockResponse().setBody("""[{"id":"abcde","uuid":"uuid-alex","name":"Alex"}]""")
                }
                request.path!!.endsWith("/members") -> MockResponse().setBody("""[{"id":"abcde","uuid":"uuid-alex","name":"Sam"}]""")
                else -> MockResponse().setBody("""[{"id":"uuid-sw","timestamp":"2026-11-13T14:00:00Z","members":["abcde"]}]""")
            }
        }
        client.getOwnMembers()

        val history = client.getRecentSwitches(10)

        assertEquals(listOf("Sam"), history.single().members.map { it.name })
    }

    @Test
    fun `history shows a member's new name without fetching the member list again`() = runTest {
        respond(body = """[{"id":"abcde","uuid":"uuid-alex","name":"Alex"}]""")
        client.getOwnMembers()
        respond(body = alexJson)
        client.updateMember("uuid-alex", mapOf(MemberField.Name to "Alexis"))
        respond(body = """[{"id":"uuid-sw","timestamp":"2026-11-13T14:00:00Z","members":["abcde"]}]""")

        val history = client.getRecentSwitches(10)

        assertEquals(listOf("Alexis"), history.single().members.map { it.name })
        assertEquals("only the three calls above", 3, server.requestCount)
    }
}
