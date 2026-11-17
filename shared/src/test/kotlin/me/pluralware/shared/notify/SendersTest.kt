package me.pluralware.shared.notify

import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.pluralware.shared.model.Member
import me.pluralware.shared.model.Switch
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SendersTest {

    private val server = MockWebServer().apply { start() }
    private val http = NotifyHttp.create("9.9.9")

    @After fun tearDown() = server.shutdown()

    private val receiverKeys = Vapid.generate() // any valid P-256 point works as a receiver key here
    private fun privateFriend(path: String = "/push/abc") = Friend.Private(
        id = "p1",
        label = "Sam",
        followCode = FollowCode("Sam", server.url(path).toString(), receiverKeys.publicKey, B64.encode(ByteArray(16) { 1 })),
    )

    private val payload = SwitchPayload("Sample", "Alex is fronting", "2026-09-27T14:02:00Z")

    @Test
    fun `a Web Push request is encrypted, signed and short-lived`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201))
        val vapid = Vapid.generate()

        val outcome = WebPushSender(http) { Instant.EPOCH }.send(privateFriend(), vapid, payload)

        assertEquals(SendOutcome.Delivered, outcome)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/push/abc", request.path)
        assertEquals("aes128gcm", request.getHeader("Content-Encoding"))
        assertEquals("43200", request.getHeader("TTL"))
        // The relay's pushes share it, so a correction replaces a mistake a push service still holds.
        assertEquals("front", request.getHeader("Topic"))
        assertTrue(request.getHeader("Authorization")!!.startsWith("vapid t="))
        assertTrue(request.getHeader("User-Agent")!!.startsWith("PluralWare/9.9.9 "))
        assertEquals(512L, request.bodySize)
        // Ciphertext only: the text is nowhere in the body.
        assertTrue("fronting" !in request.body.readUtf8())
    }

    @Test
    fun `404 and 410 mean the subscription is gone`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        server.enqueue(MockResponse().setResponseCode(410))
        val sender = WebPushSender(http)
        val vapid = Vapid.generate()
        assertEquals(SendOutcome.Gone, sender.send(privateFriend(), vapid, payload))
        assertEquals(SendOutcome.Gone, sender.send(privateFriend(), vapid, payload))
    }

    @Test
    fun `other errors are failures, not gone`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        assertEquals(SendOutcome.Failed("HTTP 500"), WebPushSender(http).send(privateFriend(), Vapid.generate(), payload))
    }

    @Test
    fun `an ntfy publish is JSON to the server root, with the token when there is one`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val ntfy = NtfyServer(server.url("/").toString(), accessToken = "tk_abc")

        val outcome = NtfySender(http).send(ntfy, "pw_topic", "Sample", "Alex is fronting")

        assertEquals(SendOutcome.Delivered, outcome)
        val request = server.takeRequest()
        assertEquals("/", request.path)
        assertEquals("Bearer tk_abc", request.getHeader("Authorization"))
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("pw_topic", body["topic"]!!.jsonPrimitive.content)
        assertEquals("Sample", body["title"]!!.jsonPrimitive.content)
        assertEquals("Alex is fronting", body["message"]!!.jsonPrimitive.content)
    }

    @Test
    fun `an open ntfy server gets no Authorization header`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        NtfySender(http).send(NtfyServer(server.url("/").toString()), "pw_topic", "T", "M")
        assertEquals(null, server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `one network failure is retried, two are a failure`() = runTest {
        var calls = 0
        val flaky = suspend {
            calls++
            if (calls == 1) throw IOException("reset") else SendOutcome.Delivered
        }
        assertEquals(SendOutcome.Delivered, withOneRetry(flaky))

        calls = 0
        val down = suspend { calls++; throw IOException("down") }
        assertEquals(SendOutcome.Failed("down"), withOneRetry(down))
        assertEquals(2, calls)
    }

    @Test
    fun `the sharer sends each friend their own way and reports each outcome`() = runTest {
        // Web Push to one path, ntfy to the root; MockWebServer answers in order of arrival,
        // so route by path instead of by queue.
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) = when (request.path) {
                "/push/gone" -> MockResponse().setResponseCode(410)
                else -> MockResponse().setResponseCode(201)
            }
        }
        val alex = Member("alex", "uuid-alex", "Alex", null, null, null, null)
        val switch = Switch("s", Instant.parse("2026-09-27T14:02:00Z"), listOf(alex))
        val sam = privateFriend("/push/sam")
        val gone = privateFriend("/push/gone").copy(id = "p2")
        val skipped = privateFriend("/push/skipped").copy(id = "p3")
        val kit = Friend.Simple("s1", "Kit", "pw_kit")
        val config = SharingConfig(
            title = "Sample",
            sharedMemberUuids = setOf(alex.uuid),
            friends = listOf(sam, gone, skipped, kit),
            vapid = Vapid.generate(),
            ntfy = NtfyServer(server.url("/").toString()),
        )

        val outcomes = SwitchSharer(WebPushSender(http), NtfySender(http), discord())
            .announce(config, switch, skipFriendIds = setOf("p3"))

        assertEquals(
            mapOf(sam to SendOutcome.Delivered, gone to SendOutcome.Gone, kit to SendOutcome.Delivered),
            outcomes,
        )
        val paths = List(3) { server.takeRequest(1, TimeUnit.SECONDS)!!.path }.toSet()
        assertEquals(setOf("/push/sam", "/push/gone", "/"), paths)
    }

    @Test
    fun `a correction goes to every friend, worded as one`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))
        val kit = Friend.Simple("s1", "Kit", "pw_kit")
        val config = SharingConfig(title = "Sample", friends = listOf(kit), ntfy = NtfyServer(server.url("/").toString()))

        val outcomes = SwitchSharer(WebPushSender(http), NtfySender(http), discord()).announceCorrection(config, null)

        assertEquals(mapOf(kit to SendOutcome.Delivered), outcomes)
        assertTrue("Correction: switched out" in server.takeRequest().body.readUtf8())
    }

    @Test
    fun `a malformed endpoint fails that friend alone`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201))
        val alex = Member("alex", "uuid-alex", "Alex", null, null, null, null)
        val switch = Switch("s", Instant.EPOCH, listOf(alex))
        // Built directly: FollowCode.parse would refuse it, but a config from an
        // older build, or a bug, shouldn't be able to crash the sender.
        val broken = Friend.Private("bad", "Bad", privateFriend().followCode.copy(endpoint = "https://push.example/a b"))
        val sam = privateFriend()
        val config = SharingConfig(friends = listOf(broken, sam), vapid = Vapid.generate())

        val outcomes = SwitchSharer(WebPushSender(http), NtfySender(http), discord()).announce(config, switch)

        assertTrue(outcomes[broken] is SendOutcome.Failed)
        assertEquals(SendOutcome.Delivered, outcomes[sam])
    }

    @Test
    fun `a friend the config can't serve fails without stopping the others`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201))
        val alex = Member("alex", "uuid-alex", "Alex", null, null, null, null)
        val switch = Switch("s", Instant.EPOCH, listOf(alex))
        val kit = Friend.Simple("s1", "Kit", "pw_kit") // no ntfy server configured
        val sam = privateFriend()
        val config = SharingConfig(friends = listOf(kit, sam), vapid = Vapid.generate())

        val outcomes = SwitchSharer(WebPushSender(http), NtfySender(http), discord()).announce(config, switch)

        assertEquals(SendOutcome.Failed("no ntfy server"), outcomes[kit])
        assertEquals(SendOutcome.Delivered, outcomes[sam])
    }

    // --- Discord ------------------------------------------------------------------------------

    private val discordFriend = Friend.Discord("d1", "Server", "123456789012345678", "tok_en")
    private fun discord() = DiscordSender(http, base = server.url("/").toString().trimEnd('/'))
    private fun okhttp3.mockwebserver.RecordedRequest.jsonBody() = Json.parseToJsonElement(body.readUtf8())

    @Test
    fun `a Discord post goes to the webhook as the system, and Discord's 204 is delivered`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val outcome = discord().send(discordFriend, "Sample", "Alex is fronting")

        assertEquals(SendOutcome.Delivered, outcome)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/webhooks/123456789012345678/tok_en", request.path)
        assertEquals(DiscordWebhook.payload("Sample", "Alex is fronting"), request.jsonBody())
    }

    @Test
    fun `a webhook Discord deleted, or whose token was reset, is gone`() = runTest {
        for (code in listOf(404, 401)) {
            server.enqueue(MockResponse().setResponseCode(code))
            assertEquals("HTTP $code", SendOutcome.Gone, discord().send(discordFriend, "Sample", "Alex is fronting"))
        }
    }

    @Test
    fun `a poster's name Discord refuses is left off, and the post sent once more`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(400)
                .setBody("""{"code":50035,"errors":{"username":{"_errors":[{"code":"USERNAME_INVALID"}]}},"message":"Invalid Form Body"}"""),
        )
        server.enqueue(MockResponse().setResponseCode(204))

        val outcome = discord().send(discordFriend, "Sample", "Alex is fronting")

        assertEquals(SendOutcome.Delivered, outcome)
        assertTrue(server.takeRequest().jsonBody().jsonObject.containsKey("username"))
        assertTrue(!server.takeRequest().jsonBody().jsonObject.containsKey("username"))
    }

    @Test
    fun `any other refusal is a failure, sent once, that never shows the webhook's token`() = runTest {
        val cases = listOf(
            MockResponse().setResponseCode(400)
                .setBody("""{"code":220001,"message":"Webhooks posted to forum channels must have a thread_name or thread_id"}"""),
            MockResponse().setResponseCode(429).setBody("""{"retry_after":1.5}"""),
            MockResponse().setResponseCode(500),
        )
        for (response in cases) {
            val before = server.requestCount
            server.enqueue(response)
            val outcome = discord().send(discordFriend, "Sample", "Alex is fronting")
            assertTrue(outcome.toString(), outcome is SendOutcome.Failed)
            assertTrue(outcome.toString(), "tok_en" !in (outcome as SendOutcome.Failed).reason)
            assertEquals("not retried", before + 1, server.requestCount)
        }
    }

    @Test
    fun `a connection Discord drops says so without the webhook's address`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.DISCONNECT_AT_START))

        val error = runCatching { discord().send(discordFriend, "Sample", "Alex is fronting") }.exceptionOrNull()

        assertTrue(error.toString(), error is IOException)
        val chain = generateSequence(error) { it.cause }.toList()
        assertTrue(chain.toString(), chain.none { "tok_en" in it.toString() })
    }

    @Test
    fun `a test post waits for Discord to keep it`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        val outcome = discord().test(DiscordWebhook.Address("123456789012345678", "tok_en"), "Sample")

        assertEquals(SendOutcome.Delivered, outcome)
        val request = server.takeRequest()
        assertEquals("/api/webhooks/123456789012345678/tok_en?wait=true", request.path)
        assertEquals(DiscordWebhook.payload("Sample", "PluralWare will post switches here."), request.jsonBody())
    }

    @Test
    fun `the sharer posts to Discord channels too, a correction as well`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        val config = SharingConfig(title = "Sample", friends = listOf(discordFriend))

        val outcomes = SwitchSharer(WebPushSender(http), NtfySender(http), discord()).announceCorrection(config, null)

        assertEquals(mapOf<Friend, SendOutcome>(discordFriend to SendOutcome.Delivered), outcomes)
        assertEquals("Correction: switched out", server.takeRequest().jsonBody().jsonObject.getValue("content").jsonPrimitive.content)
    }
}
