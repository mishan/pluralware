package me.pluralware.wear.ui.viewmodel

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
import me.pluralware.wear.ui.state.UiState
import org.junit.Assert.assertEquals
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
}
