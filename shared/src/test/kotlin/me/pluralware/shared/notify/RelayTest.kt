package me.pluralware.shared.notify

import kotlinx.coroutines.test.runTest
import me.pluralware.shared.model.Member
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RelayTest {

    private val server = MockWebServer().apply { start() }
    private val client = RelayClient(NotifyHttp.create("9.9.9"))

    @After fun tearDown() = server.shutdown()

    private fun member(uuid: String, label: String) = Member(uuid, uuid, label, null, null, null, null)
    private val alex = member("uuid-alex", "Alex")
    private val bea = member("uuid-bea", "Bea")

    private val sharing = SharingConfig(
        title = "Sample",
        sharedMemberUuids = setOf(alex.uuid),
        friends = listOf(
            Friend.Private("p1", "Sam", FollowCode("Sam", "https://push.example/sam", "PUB", "AUTH")),
            Friend.Simple("s1", "Kit", "pw_kit"),
        ),
        vapid = VapidKeys("VPUB", "VPRIV"),
        ntfy = NtfyServer("https://ntfy.example.org", "tk_x"),
        relay = RelaySettings(
            url = "https://relay.example/",
            adminSecret = "admin",
            webhookPath = "hook_abcdefghijklmnop",
            signingToken = "pk-signing-token",
            enabled = true,
        ),
    )

    @Test
    fun `the upload names shared members only, and is exactly what the relay reads`() {
        val config = RelayConfig.from(sharing, listOf(alex, bea))!!
        assertEquals(mapOf("uuid-alex" to "Alex"), config.names)
        // Shared with relay/test/config-interop.test.mjs.
        assertEquals(INTEROP_JSON, RelayJson.encode(config))
    }

    @Test
    fun `nothing to upload until PluralKit has given a signing token`() {
        assertNull(RelayConfig.from(sharing.copy(relay = sharing.relay!!.copy(signingToken = null)), listOf(alex)))
        assertNull(RelayConfig.from(sharing.copy(relay = null), listOf(alex)))
    }

    @Test
    fun `the webhook URL joins cleanly, and fresh paths are long and unguessable`() {
        assertEquals("https://relay.example/pk/hook_abcdefghijklmnop", sharing.relay!!.webhookUrl)
        val paths = List(50) { RelaySettings.newWebhookPath() }
        paths.forEach { assertTrue(it, Regex("[A-Za-z0-9_-]{24}").matches(it)) }
        assertEquals(paths.size, paths.toSet().size)
    }

    @Test
    fun `the relay taking over stops the watch sending`() {
        assertTrue(sharing.relaySends)
        assertTrue(!sharing.copy(relay = sharing.relay!!.copy(enabled = false)).relaySends)
    }

    @Test
    fun `upload, status and clear speak the relay's admin API`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        server.enqueue(MockResponse().setBody("""{"configured":true,"enabled":true,"gone":["p1"],"last":null}"""))
        server.enqueue(MockResponse().setResponseCode(204))
        val settings = sharing.relay!!.copy(url = server.url("/").toString())

        client.upload(settings, RelayConfig.from(sharing, listOf(alex))!!)
        val status = client.status(settings)
        client.clear(settings)

        val put = server.takeRequest()
        assertEquals("PUT", put.method)
        assertEquals("/config", put.path)
        assertEquals("Bearer admin", put.getHeader("Authorization"))
        assertTrue(put.body.readUtf8().contains("\"signingToken\":\"pk-signing-token\""))
        assertEquals(RelayStatus(configured = true, enabled = true, gone = listOf("p1")), status)
        assertEquals("GET", server.takeRequest().method)
        assertEquals("DELETE", server.takeRequest().method)
    }

    @Test
    fun `refusals carry the relay's reason`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"bad webhookPath"}"""))
        server.enqueue(MockResponse().setResponseCode(401))
        val settings = sharing.relay!!.copy(url = server.url("/").toString())
        val config = RelayConfig.from(sharing, listOf(alex))!!

        try {
            client.upload(settings, config); fail()
        } catch (e: RelayClient.RelayException) {
            assertEquals("The relay said: bad webhookPath", e.message)
        }
        try {
            client.upload(settings, config); fail()
        } catch (e: RelayClient.RelayException) {
            assertEquals("The relay didn't accept its admin secret.", e.message)
        }
    }

    companion object {
        const val INTEROP_JSON = """{"enabled":true,"webhookPath":"hook_abcdefghijklmnop","signingToken":"pk-signing-token","title":"Sample","names":{"uuid-alex":"Alex"},"friends":[{"type":"private","id":"p1","label":"Sam","followCode":{"name":"Sam","endpoint":"https://push.example/sam","p256dh":"PUB","auth":"AUTH"}},{"type":"simple","id":"s1","label":"Kit","topic":"pw_kit"}],"vapid":{"publicKey":"VPUB","privateKey":"VPRIV"},"ntfy":{"baseUrl":"https://ntfy.example.org","accessToken":"tk_x"}}"""
    }
}
