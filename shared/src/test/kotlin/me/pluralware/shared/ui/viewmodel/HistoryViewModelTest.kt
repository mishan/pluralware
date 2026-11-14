package me.pluralware.shared.ui.viewmodel

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import me.pluralware.shared.mock.MockPluralKitClient
import me.pluralware.shared.model.Switch
import me.pluralware.shared.api.PluralKitClient
import me.pluralware.shared.api.PluralKitHttpException
import me.pluralware.shared.preview.PreviewData
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.ui.state.UiState
import me.pluralware.shared.util.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HistoryViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val client = mockk<PluralKitClient>()
    private val repository = PluralKitRepository(client)

    @Test
    fun `loads recent switches`() = runTest {
        coEvery { client.getRecentSwitches(any()) } returns PreviewData.historyRecent

        val vm = HistoryViewModel(repository)

        assertEquals(UiState.Content(PreviewData.historyRecent), vm.state.value)
    }

    @Test
    fun `a failed load shows an error`() = runTest {
        coEvery { client.getRecentSwitches(any()) } throws PluralKitHttpException(500, "Oops")

        val vm = HistoryViewModel(repository)

        assertTrue(vm.state.value is UiState.Error)
    }

    @Test
    fun `retrying after a failed load shows the switches`() = runTest {
        coEvery { client.getRecentSwitches(any()) } throws java.io.IOException("offline")
        val vm = HistoryViewModel(repository)

        coEvery { client.getRecentSwitches(any()) } returns PreviewData.historyRecent
        vm.load()

        assertEquals(UiState.Content(PreviewData.historyRecent), vm.state.value)
    }

    @Test
    fun `a rejected token offers sign-out`() = runTest {
        coEvery { client.getRecentSwitches(any()) } throws PluralKitHttpException(401, "Unauthorized")

        val state = HistoryViewModel(repository).state.value

        assertTrue((state as UiState.Error).unauthorized)
    }

    @Test
    fun `a switch fixed anywhere in the app reloads history, with no loading in between`() =
        runTest(UnconfinedTestDispatcher()) {
            val pk = MockPluralKitClient(artificialLatencyMillis = 0)
            val repository = PluralKitRepository(pk)
            val vm = HistoryViewModel(repository)
            val (_, older) = pk.getRecentSwitches(10)
            val states = mutableListOf<UiState<List<Switch>>>()
            backgroundScope.launch { vm.state.toList(states) }

            repository.deleteSwitch(older.uuid)

            assertEquals(UiState.Content(pk.getRecentSwitches(10)), states.last())
            assertTrue("the list stays up while it reloads", UiState.Loading !in states)
        }

    @Test
    fun `a reload after a fix that fails keeps the list up`() = runTest(UnconfinedTestDispatcher()) {
        val pk = MockPluralKitClient(artificialLatencyMillis = 0)
        val client = object : PluralKitClient by pk {
            var offline = false
            override suspend fun getRecentSwitches(limit: Int) =
                if (offline) throw java.io.IOException("offline") else pk.getRecentSwitches(limit)
        }
        val repository = PluralKitRepository(client)
        val vm = HistoryViewModel(repository)
        val shown = vm.state.value
        val (_, older) = pk.getRecentSwitches(10)

        client.offline = true
        repository.deleteSwitch(older.uuid)

        assertEquals(shown, vm.state.value)
    }
}
