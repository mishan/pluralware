package me.pluralware.wear.ui.viewmodel

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import me.pluralware.shared.api.PluralKitClient
import me.pluralware.shared.api.PluralKitHttpException
import me.pluralware.shared.model.Switch
import me.pluralware.shared.preview.PreviewData
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.wear.ui.state.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HistoryViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val client = mockk<PluralKitClient>().apply {
        coEvery { getRecentSwitches(any()) } returns PreviewData.historyRecent
        coEvery { getCurrentFronters() } returns PreviewData.currentSwitch
    }
    private val repository = PluralKitRepository(client)
    private val past = PreviewData.historyRecent.last()

    @Test
    fun `switch back registers the past fronters and then finishes`() = runTest {
        coEvery { client.registerSwitch(any()) } returns past
        var done = 0

        HistoryViewModel(repository).switchBackTo(past) { done++ }

        coVerify { client.registerSwitch(past.members.map { it.uuid }) }
        assertEquals(1, done)
    }

    @Test
    fun `a double tap registers one switch`() = runTest {
        val response = CompletableDeferred<Switch>()
        coEvery { client.registerSwitch(any()) } coAnswers { response.await() }
        val vm = HistoryViewModel(repository)

        vm.switchBackTo(past) {}
        vm.switchBackTo(past) {}
        response.complete(past)

        coVerify(exactly = 1) { client.registerSwitch(any()) }
    }

    @Test
    fun `a failed switch back shows an error and stays put`() = runTest {
        coEvery { client.registerSwitch(any()) } throws PluralKitHttpException(500, "Oops")
        var done = 0
        val vm = HistoryViewModel(repository)

        vm.switchBackTo(past) { done++ }

        assertEquals(0, done)
        assertTrue(vm.state.value is UiState.Error)
    }

    @Test
    fun `switching back to the newest switch's fronters skips the request`() = runTest {
        var done = 0
        val sameAsNewest = PreviewData.historyRecent.first().copy(uuid = "older-but-identical")

        HistoryViewModel(repository).switchBackTo(sameAsNewest) { done++ }

        coVerify(exactly = 0) { client.registerSwitch(any()) }
        assertEquals(1, done)
    }

    @Test
    fun `a stale cached front doesn't swallow a real switch back`() = runTest {
        // The home screen last saw `past` in front; since then someone switched
        // elsewhere, and the history this screen loads starts with that switch.
        coEvery { client.getCurrentFronters() } returns past
        repository.refreshFronters()
        coEvery { client.registerSwitch(any()) } returns past
        var done = 0

        HistoryViewModel(repository).switchBackTo(past) { done++ }

        coVerify(exactly = 1) { client.registerSwitch(past.members.map { it.uuid }) }
        assertEquals(1, done)
    }
}
