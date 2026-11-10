package me.pluralware.wear.ui.viewmodel

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import me.pluralware.shared.api.PluralKitClient
import me.pluralware.shared.api.PluralKitHttpException
import me.pluralware.shared.preview.PreviewData
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.wear.ui.state.UiState
import me.pluralware.wear.util.MainDispatcherRule
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
}
