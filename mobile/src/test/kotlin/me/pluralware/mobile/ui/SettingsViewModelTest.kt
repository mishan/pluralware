package me.pluralware.mobile.ui

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import me.pluralware.mobile.watch.WatchLink
import me.pluralware.mobile.watch.WatchState
import me.pluralware.shared.api.PluralKitClient
import me.pluralware.shared.api.PluralKitToken
import me.pluralware.shared.model.SystemInfo
import me.pluralware.shared.repository.InMemoryTokenStore
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.settings.InMemorySettingsStore
import me.pluralware.shared.settings.RefreshInterval
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val system = SystemInfo("sys", "uuid-sys", "Sample", null)
    private val client = mockk<PluralKitClient>()
    private val settings = InMemorySettingsStore()
    private val tokens = InMemoryTokenStore(PluralKitToken("token"))
    private val sentTokens = mutableListOf<PluralKitToken>()
    private var settingsPushes = 0
    private var signOuts = 0

    private val watchLink = WatchLink(
        scope = CoroutineScope(dispatcher),
        connectedWatches = { listOf("Pixel Watch") },
        pushToken = { sentTokens += it },
        pushSettings = { settingsPushes++ },
        pushSignOut = {},
    )

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    private fun viewModel() = SettingsViewModel(
        repository = PluralKitRepository(client),
        settingsStore = settings,
        tokenStore = tokens,
        watchLink = watchLink,
        signOut = { signOuts++ },
        signingOut = MutableStateFlow(false),
    )

    @Test
    fun `shows who's signed in, and looks for a watch`() {
        coEvery { client.getOwnSystem() } returns system

        val vm = viewModel()

        assertEquals(system, vm.system.value)
        assertEquals(WatchState.Connected("Pixel Watch"), vm.watch.value)
    }

    @Test
    fun `an account PluralKit can't describe right now still shows as connected`() {
        coEvery { client.getOwnSystem() } throws java.io.IOException("offline")

        assertNull(viewModel().system.value)
    }

    @Test
    fun `a new refresh interval is saved and sent to the watch`() {
        coEvery { client.getOwnSystem() } returns system
        val vm = viewModel()

        vm.setRefreshInterval(RefreshInterval.MIN_5)

        assertEquals(RefreshInterval.MIN_5, settings.settingsFlow.value.refreshInterval)
        assertEquals(1, settingsPushes)
    }

    @Test
    fun `send to watch sends the stored token`() {
        coEvery { client.getOwnSystem() } returns system
        val vm = viewModel()

        vm.sendToWatch()

        assertEquals(listOf(PluralKitToken("token")), sentTokens)
        assertEquals(WatchState.Sent("Pixel Watch"), vm.watch.value)
    }

    @Test
    fun `disconnect signs out`() {
        coEvery { client.getOwnSystem() } returns system

        viewModel().disconnect()

        assertEquals(1, signOuts)
    }
}
