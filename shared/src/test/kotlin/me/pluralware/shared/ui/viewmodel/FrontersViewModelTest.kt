package me.pluralware.shared.ui.viewmodel

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Duration
import kotlinx.coroutines.test.runTest
import me.pluralware.shared.api.PluralKitClient
import me.pluralware.shared.api.PluralKitHttpException
import me.pluralware.shared.preview.PreviewData
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.settings.InMemorySettingsStore
import me.pluralware.shared.settings.RefreshInterval
import me.pluralware.shared.ui.state.UiState
import me.pluralware.shared.util.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class FrontersViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val client = mockk<PluralKitClient>().apply {
        coEvery { getRecentSwitches(any()) } returns PreviewData.historyRecent
    }
    private var now = 1_000_000L

    private fun viewModel() = FrontersViewModel(
        repository = PluralKitRepository(client),
        settingsStore = InMemorySettingsStore(),
        clock = { now },
    )

    @Test
    fun `a rejected token replaces stale content with a sign-out error`() = runTest {
        coEvery { client.getCurrentFronters() } returns PreviewData.currentSwitch
        val vm = viewModel()
        assertTrue(vm.state.value is UiState.Content)

        coEvery { client.getCurrentFronters() } throws PluralKitHttpException(401, "Unauthorized")
        vm.refresh()

        val state = vm.state.value
        assertTrue(state is UiState.Error)
        assertTrue((state as UiState.Error).unauthorized)
    }

    @Test
    fun `polling stops once the token is rejected`() = runTest {
        coEvery { client.getCurrentFronters() } throws PluralKitHttpException(401, "Unauthorized")
        val vm = viewModel() // fetch 1: rejected

        vm.poll()
        vm.poll()

        coVerify(exactly = 1) { client.getCurrentFronters() }
    }

    @Test
    fun `other refresh failures keep the stale content`() = runTest {
        coEvery { client.getCurrentFronters() } returns PreviewData.currentSwitch
        val vm = viewModel()

        coEvery { client.getCurrentFronters() } throws PluralKitHttpException(502, "Bad Gateway")
        vm.refresh()

        assertTrue(vm.state.value is UiState.Content)
    }

    @Test
    fun `polling stands down for Retry-After after a 429`() = runTest {
        coEvery { client.getCurrentFronters() } returns PreviewData.currentSwitch
        val vm = viewModel() // fetch 1

        coEvery { client.getCurrentFronters() } throws
            PluralKitHttpException(429, "Too Many Requests", retryAfter = Duration.ofSeconds(30))
        vm.poll() // fetch 2: rate-limited

        now += 29_000
        vm.poll() // paused
        coVerify(exactly = 2) { client.getCurrentFronters() }

        now += 2_000
        vm.poll() // fetch 3: pause over
        coVerify(exactly = 3) { client.getCurrentFronters() }
    }

    @Test
    fun `a manual refresh ignores the rate-limit pause`() = runTest {
        coEvery { client.getCurrentFronters() } returns PreviewData.currentSwitch
        val vm = viewModel()
        coEvery { client.getCurrentFronters() } throws PluralKitHttpException(429, "Too Many Requests")
        vm.poll()

        vm.refresh()

        coVerify(exactly = 3) { client.getCurrentFronters() }
        assertEquals(PreviewData.currentSwitch, (vm.state.value as UiState.Content).value.switch)
    }

    @Test
    fun `loads the current switch with each fronter's streak`() = runTest {
        coEvery { client.getCurrentFronters() } returns PreviewData.currentSwitch

        val content = (viewModel().state.value as UiState.Content).value

        assertEquals(PreviewData.currentSwitch, content.switch)
        assertEquals(PreviewData.currentSwitch.members.map { it.uuid }.toSet(), content.streaks.keys)
        assertFalse(content.refreshing)
    }

    @Test
    fun `a system with no switches loads as empty content, not an error`() = runTest {
        coEvery { client.getCurrentFronters() } returns null

        val content = (viewModel().state.value as UiState.Content).value

        assertNull(content.switch)
        assertTrue(content.streaks.isEmpty())
    }

    @Test
    fun `history that won't load still shows the fronters`() = runTest {
        coEvery { client.getCurrentFronters() } returns PreviewData.currentSwitch
        coEvery { client.getRecentSwitches(any()) } throws java.io.IOException("offline")

        val content = (viewModel().state.value as UiState.Content).value

        assertEquals(PreviewData.currentSwitch, content.switch)
        content.streaks.values.forEach { assertEquals(PreviewData.currentSwitch.timestamp, it.since) }
    }

    @Test
    fun `a first load that fails shows an error, and retry recovers`() = runTest {
        coEvery { client.getCurrentFronters() } throws java.io.IOException("offline")
        val vm = viewModel()
        assertTrue(vm.state.value is UiState.Error)

        coEvery { client.getCurrentFronters() } returns PreviewData.currentSwitch
        vm.load()

        assertTrue(vm.state.value is UiState.Content)
    }

    @Test
    fun `a switch registered elsewhere in the app reloads the screen`() = runTest {
        coEvery { client.getCurrentFronters() } returns PreviewData.currentSwitch
        val repository = PluralKitRepository(client)
        val vm = FrontersViewModel(repository, InMemorySettingsStore(), clock = { now })
        coEvery { client.registerSwitch(any()) } returns PreviewData.switchOut
        coEvery { client.getCurrentFronters() } returns PreviewData.switchOut

        repository.registerSwitch(emptyList())

        assertEquals(PreviewData.switchOut, (vm.state.value as UiState.Content).value.switch)
        coVerify(exactly = 2) { client.getCurrentFronters() }
    }

    @Test
    fun `a moved switch reloads the screen though its uuid is the same`() = runTest {
        coEvery { client.getCurrentFronters() } returns PreviewData.currentSwitch
        val repository = PluralKitRepository(client)
        val vm = FrontersViewModel(repository, InMemorySettingsStore(), clock = { now })
        val moved = PreviewData.currentSwitch.copy(timestamp = PreviewData.currentSwitch.timestamp.minusSeconds(600))
        coEvery { client.getCurrentFronters() } returns moved

        repository.refreshFronters()

        assertEquals(moved, (vm.state.value as UiState.Content).value.switch)
    }

    @Test
    fun `its own refresh doesn't trigger another`() = runTest {
        coEvery { client.getCurrentFronters() } returns PreviewData.currentSwitch
        val vm = viewModel()

        vm.refresh()

        coVerify(exactly = 2) { client.getCurrentFronters() }
    }

    @Test
    fun `the refresh interval follows the settings`() = runTest {
        coEvery { client.getCurrentFronters() } returns PreviewData.currentSwitch
        val settings = InMemorySettingsStore()
        val vm = FrontersViewModel(PluralKitRepository(client), settings, clock = { now })

        settings.setRefreshInterval(RefreshInterval.MIN_5)

        assertEquals(RefreshInterval.MIN_5.seconds, vm.refreshIntervalSeconds.value)
    }
}
