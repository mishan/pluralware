package me.pluralware.mobile.sharing

import android.content.Context
import io.mockk.mockk
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import me.pluralware.shared.api.PluralKitToken
import me.pluralware.shared.model.Member
import me.pluralware.shared.model.SystemInfo
import me.pluralware.shared.notify.DiscordWebhook
import me.pluralware.shared.notify.Friend
import me.pluralware.shared.notify.InMemorySharingStore
import me.pluralware.shared.notify.RelayClient
import me.pluralware.shared.notify.RelaySettings
import me.pluralware.shared.notify.SendOutcome
import me.pluralware.shared.notify.SharingConfig
import me.pluralware.shared.repository.InMemoryTokenStore
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SharingViewModelTest {

    private val relayServer = MockWebServer()

    /** Answers the relay's admin API by request, so tests needn't know its call order. */
    private val putStatuses = java.util.concurrent.LinkedBlockingQueue<Int>()
    private val deleteStatuses = java.util.concurrent.LinkedBlockingQueue<Int>()
    private val puts = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val deletes = java.util.concurrent.atomic.AtomicInteger()
    private val relayWrites = Mutex()

    private fun fakeRelay() = object : okhttp3.mockwebserver.Dispatcher() {
        override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when (request.method) {
            "GET" -> MockResponse().setBody("""{"configured":true,"enabled":false,"gone":[]}""")
            "PUT" -> {
                // Before recording it: a test that waits for this PUT and then
                // queues a status means that status for the next one.
                val status = putStatuses.poll() ?: 204
                puts += request.body.readUtf8()
                MockResponse().setResponseCode(status)
            }
            "DELETE" -> MockResponse().setResponseCode(deleteStatuses.poll() ?: 204).also { deletes.incrementAndGet() }
            else -> MockResponse().setResponseCode(404)
        }
    }
    private val system = SystemInfo("sys", "uuid-sys", "Sample", null)
    private val alex = Member("alex", "uuid-alex", "Alex", null, null, null, null)
    private val pushedToWatch = java.util.concurrent.CopyOnWriteArrayList<SharingConfig>()
    private lateinit var store: InMemorySharingStore

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        relayServer.dispatcher = fakeRelay()
        relayServer.start()
        store = InMemorySharingStore(
            SharingConfig(
                sharedMemberUuids = setOf(alex.uuid),
                friends = listOf(Friend.Simple("s1", "Kit", "pw_kit")),
                relay = RelaySettings(
                    url = relayServer.url("/").toString(),
                    adminSecret = "admin-secret",
                    signingToken = "pk-token",
                ),
            ),
        )
    }

    private val created = mutableListOf<SharingViewModel>()

    @After fun tearDown() {
        // Stop each ViewModel's work and let any relay call in flight finish
        // before Main goes away; otherwise it resumes into the next test.
        val jobs = created.map { it.viewModelScope.coroutineContext.job.apply { cancel() } }
        relayServer.shutdown()
        runBlocking { withTimeout(5_000) { jobs.joinAll() } }
        Dispatchers.resetMain()
    }

    private val discordPosts = mutableListOf<Pair<DiscordWebhook.Address, String>>()
    private var discordAnswer: suspend () -> SendOutcome = { SendOutcome.Delivered }

    private fun viewModel(membersLoad: Boolean = true) = SharingViewModel(
        tokenStore = InMemoryTokenStore(PluralKitToken("token")),
        sharingStore = store,
        appContext = mockk<Context>(relaxed = true),
        loadSystem = { if (membersLoad) system to listOf(alex) else error("offline") },
        relayClient = RelayClient.create("test"),
        pushToWatch = { pushedToWatch += it },
        readWatchStatus = { emptySet() },
        relayWrites = relayWrites,
        testDiscord = { address, title -> discordPosts += address to title; discordAnswer() },
    ).also { created += it }

    /** The relay calls run on OkHttp's threads; wait for them in real time. */
    private fun eventually(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!check()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what" }
            Thread.sleep(20)
        }
    }

    @Test
    fun `deleting from a relay waits for another relay write in flight`() {
        val relay = store.configFlow.value.relay!!
        // Each with what it leaves saved, so the next case doesn't start while it's still saving.
        val cases = listOf<Triple<String, (SharingViewModel) -> Unit, () -> Boolean>>(
            Triple("removing the relay", { it.removeRelay() }, { store.configFlow.value.relay == null }),
            Triple(
                "moving to another relay",
                { it.saveRelay("https://other.example", "s") },
                { store.configFlow.value.relay?.url == "https://other.example" },
            ),
            Triple(
                "retrying an unreachable one",
                { vm ->
                    runBlocking { store.addOrphan(relay.copy(url = relay.url + "old/")) }
                    vm.retryOrphans()
                },
                { store.orphanedRelays.value.isEmpty() },
            ),
        )
        for ((what, act, settled) in cases) {
            runBlocking { store.set(store.configFlow.value.copy(relay = relay)) }
            val vm = viewModel()
            eventually("initial sync") { vm.state.value.system != null && puts.isNotEmpty() }
            deletes.set(0)
            runBlocking { relayWrites.lock() }

            act(vm)
            Thread.sleep(200)
            assertEquals(what, 0, deletes.get())

            relayWrites.unlock()
            eventually("$what: the delete") { deletes.get() == 1 }
            eventually("$what: saved") { settled() }
        }
    }

    @Test
    fun `turning the relay on goes by the config once other relay writes are done`() {
        val vm = viewModel()
        eventually("initial sync") { puts.size == 1 }
        runBlocking { relayWrites.lock() }

        vm.setRelayEnabled(true)
        runBlocking { store.clear() }
        relayWrites.unlock()
        Thread.sleep(200)

        assertEquals(1, puts.size)
        assertEquals(SharingConfig(), store.configFlow.value)
    }

    @Test
    fun `turning the relay on waits for it, so a failed upload leaves the watch sending`() {
        val vm = viewModel()
        eventually("initial sync") { puts.size == 1 }
        putStatuses += 500

        vm.setRelayEnabled(true)
        eventually("the error") { vm.state.value.relayError != null }

        assertFalse(store.configFlow.value.relaySends)
        assertTrue(store.configFlow.value.appSends)
    }

    @Test
    fun `once the relay has it, sending moves off the watch`() {
        val vm = viewModel()
        eventually("initial sync") { puts.size == 1 }

        vm.setRelayEnabled(true)
        // The watch is told after the store is saved, so wait for that.
        eventually("the watch told") { pushedToWatch.lastOrNull()?.appSends == false }

        assertTrue(store.configFlow.value.relaySends)
        assertTrue(puts.last().contains("\"enabled\":true"))
    }

    @Test
    fun `without members loaded, nothing is uploaded and the screen says why`() {
        val vm = viewModel(membersLoad = false)

        vm.setRelayEnabled(true)
        eventually("the error") { vm.state.value.relayError != null }

        assertEquals(0, puts.size)
        assertFalse(store.configFlow.value.relaySends)
    }

    @Test
    fun `the watch never gets the relay's secrets`() {
        val vm = viewModel()
        eventually("initial sync") { puts.size == 1 }

        vm.setTitle("New title")
        eventually("pushed") { pushedToWatch.isNotEmpty() }

        val onWatch = pushedToWatch.last().relay
        assertNotNull(onWatch)
        assertEquals("", onWatch!!.adminSecret)
        assertNull(onWatch.signingToken)
        assertEquals("", onWatch.url)
    }

    @Test
    fun `an unreachable relay isn't forgotten until the user says so, and then it's remembered`() {
        val vm = viewModel()
        eventually("initial sync") { puts.size == 1 }
        val relay = store.configFlow.value.relay!!
        deleteStatuses += 503

        vm.removeRelay()
        eventually("the failure") { vm.state.value.relayRemoveFailed }
        assertNotNull(store.configFlow.value.relay)

        vm.removeRelayAnyway()
        eventually("removed") { store.configFlow.value.relay == null }
        assertEquals(listOf(relay), store.orphanedRelays.value)
    }

    private val webhook = "https://discord.com/api/webhooks/123456789012345678/tok_en"

    @Test
    fun `a Discord channel is added only once Discord takes its test post`() {
        val cases = listOf<Triple<suspend () -> SendOutcome, Boolean, String?>>(
            Triple({ SendOutcome.Delivered }, true, null),
            Triple({ SendOutcome.Gone }, false, "copied the whole link"),
            Triple({ SendOutcome.Failed("HTTP 400") }, false, "forum channel"),
            Triple({ SendOutcome.Failed("HTTP 429") }, false, "busy (HTTP 429)"),
            Triple({ SendOutcome.Failed("HTTP 403") }, false, "refused the test post (HTTP 403)"),
            Triple({ throw java.io.IOException("Couldn't reach Discord (UnknownHostException)") }, false, "Check your connection"),
        )
        for ((answer, added, says) in cases) {
            runBlocking { store.set(store.configFlow.value.copy(title = "Sample", friends = emptyList())) }
            discordPosts.clear()
            discordAnswer = answer
            val vm = viewModel()
            var done = false

            vm.addDiscordChannel(" $webhook ", "Server", onAdded = { done = true })

            assertEquals(listOf(DiscordWebhook.Address("123456789012345678", "tok_en") to "Sample"), discordPosts)
            val friends = store.configFlow.value.friends
            assertEquals(says, added, friends == listOf(Friend.Discord(friends.firstOrNull()?.id.orEmpty(), "Server", "123456789012345678", "tok_en")))
            assertEquals(says, added, done)
            val error = vm.state.value.discordError
            if (says == null) assertNull(error) else assertTrue("$error", error!!.contains(says))
            assertFalse(vm.state.value.discordTesting)
        }
    }

    @Test
    fun `a link that isn't a Discord webhook, or a channel already added, is refused without posting`() {
        runBlocking {
            store.set(store.configFlow.value.copy(friends = listOf(Friend.Discord("d1", "Server", "123456789012345678", "tok_en"))))
        }
        val vm = viewModel()
        for ((link, says) in listOf(
            "https://discord.example/api/webhooks/123456789012345678/tok_en" to "isn't a Discord webhook link",
            webhook to "Remove it first",
        )) {
            vm.addDiscordChannel(link, "", onAdded = { error("added") })

            assertTrue(link, vm.state.value.discordError!!.contains(says))
        }
        assertEquals(emptyList<Any>(), discordPosts)
        assertEquals(1, store.configFlow.value.friends.size)
    }

    @Test
    fun `a second tap while the test post is out adds nothing more`() {
        runBlocking { store.set(store.configFlow.value.copy(friends = emptyList())) }
        val reply = kotlinx.coroutines.CompletableDeferred<SendOutcome>()
        discordAnswer = { reply.await() }
        val vm = viewModel()

        vm.addDiscordChannel(webhook, "Server", onAdded = {})
        vm.addDiscordChannel(webhook, "Server", onAdded = {})
        reply.complete(SendOutcome.Delivered)

        assertEquals(1, discordPosts.size)
        assertEquals(1, store.configFlow.value.friends.size)
    }
}
