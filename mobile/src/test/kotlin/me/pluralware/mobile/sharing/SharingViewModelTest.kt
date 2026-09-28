package me.pluralware.mobile.sharing

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.pluralware.shared.api.PluralKitToken
import me.pluralware.shared.model.Member
import me.pluralware.shared.model.SystemInfo
import me.pluralware.shared.notify.Friend
import me.pluralware.shared.notify.InMemorySharingStore
import me.pluralware.shared.notify.RelayClient
import me.pluralware.shared.notify.RelaySettings
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

    private fun fakeRelay() = object : okhttp3.mockwebserver.Dispatcher() {
        override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse = when (request.method) {
            "GET" -> MockResponse().setBody("""{"configured":true,"enabled":false,"gone":[]}""")
            "PUT" -> {
                puts += request.body.readUtf8()
                MockResponse().setResponseCode(putStatuses.poll() ?: 204)
            }
            "DELETE" -> MockResponse().setResponseCode(deleteStatuses.poll() ?: 204)
            else -> MockResponse().setResponseCode(404)
        }
    }
    private val system = SystemInfo("sys", "uuid-sys", "Sample", null)
    private val alex = Member("alex", "uuid-alex", "Alex", null, null, null, null)
    private val pushedToWatch = mutableListOf<SharingConfig>()
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

    @After fun tearDown() {
        relayServer.shutdown()
        Dispatchers.resetMain()
    }

    private fun viewModel(membersLoad: Boolean = true) = SharingViewModel(
        tokenStore = InMemoryTokenStore(PluralKitToken("token")),
        sharingStore = store,
        appContext = mockk<Context>(relaxed = true),
        loadSystem = { if (membersLoad) system to listOf(alex) else error("offline") },
        relayClient = RelayClient.create("test"),
        pushToWatch = { pushedToWatch += it },
        readWatchStatus = { emptySet() },
    )

    /** The relay calls run on OkHttp's threads; wait for them in real time. */
    private fun eventually(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (!check()) {
            check(System.currentTimeMillis() < deadline) { "timed out waiting for: $what" }
            Thread.sleep(20)
        }
    }

    @Test
    fun `turning the relay on waits for it, so a failed upload leaves the watch sending`() {
        val vm = viewModel()
        eventually("initial sync") { puts.size == 1 }
        putStatuses += 500

        vm.setRelayEnabled(true)
        eventually("the error") { vm.state.value.relayError != null }

        assertFalse(store.configFlow.value.relaySends)
        assertTrue(store.configFlow.value.watchSends)
    }

    @Test
    fun `once the relay has it, sending moves off the watch`() {
        val vm = viewModel()
        eventually("initial sync") { puts.size == 1 }

        vm.setRelayEnabled(true)
        eventually("enabled") { store.configFlow.value.relaySends }

        assertTrue(puts.last().contains("\"enabled\":true"))
        assertFalse(pushedToWatch.last().watchSends)
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
}
