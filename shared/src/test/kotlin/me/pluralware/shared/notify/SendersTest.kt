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

        val outcomes = SwitchSharer(WebPushSender(http), NtfySender(http))
            .announce(config, switch, skipFriendIds = setOf("p3"))

        assertEquals(
            mapOf(sam to SendOutcome.Delivered, gone to SendOutcome.Gone, kit to SendOutcome.Delivered),
            outcomes,
        )
        val paths = List(3) { server.takeRequest(1, TimeUnit.SECONDS)!!.path }.toSet()
        assertEquals(setOf("/push/sam", "/push/gone", "/"), paths)
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

        val outcomes = SwitchSharer(WebPushSender(http), NtfySender(http)).announce(config, switch)

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

        val outcomes = SwitchSharer(WebPushSender(http), NtfySender(http)).announce(config, switch)

        assertEquals(SendOutcome.Failed("no ntfy server"), outcomes[kit])
        assertEquals(SendOutcome.Delivered, outcomes[sam])
    }

}
