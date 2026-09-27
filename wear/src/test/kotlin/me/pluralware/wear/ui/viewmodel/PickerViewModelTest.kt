package me.pluralware.wear.ui.viewmodel

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import me.pluralware.shared.api.PluralKitClient
import me.pluralware.shared.api.PluralKitHttpException
import me.pluralware.shared.model.Switch
import me.pluralware.shared.preview.PreviewData
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.wear.ui.state.UiState
import me.pluralware.wear.util.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class PickerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val client = mockk<PluralKitClient>().apply {
        coEvery { getOwnMembers() } returns PreviewData.members
        coEvery { getCurrentFronters() } returns PreviewData.currentSwitch
    }
    private val repository = PluralKitRepository(client)

    private fun viewModel() = PickerViewModel(repository = repository, confirmationLingerMillis = 0)

    /** Pick a member who isn't fronting, so submit() really registers a switch. */
    private fun PickerViewModel.pickSomeoneElse(): String {
        val fronting = PreviewData.currentSwitch.members.map { it.uuid }
        val other = PreviewData.members.first { it.uuid !in fronting }.uuid
        deselectAll()
        toggle(other)
        return other
    }

    @Test
    fun `a real switch is registered and becomes the current fronters`() = runTest {
        val vm = viewModel()
        val other = vm.pickSomeoneElse()
        val registered = Switch("new", Instant.now(), PreviewData.members.filter { it.uuid == other })
        coEvery { client.registerSwitch(listOf(other)) } returns registered

        vm.submitSelection()

        // currentFronters is what the home screen, complication and tile all follow.
        assertEquals(registered, repository.currentFronters.value)
        assertTrue(vm.state.value.confirmed)
    }

    @Test
    fun `resubmitting the current fronters registers nothing`() = runTest {
        val vm = viewModel()

        vm.submitSelection()

        coVerify(exactly = 0) { client.registerSwitch(any()) }
        assertTrue(vm.state.value.confirmed)
    }

    @Test
    fun `a failed switch shows an error and leaves the fronters alone`() = runTest {
        val vm = viewModel()
        vm.pickSomeoneElse()
        coEvery { client.registerSwitch(any()) } throws PluralKitHttpException(500, "Oops")

        vm.submitSelection()

        assertTrue(vm.state.value.members is UiState.Error)
        assertFalse(vm.state.value.confirmed)
        assertEquals(PreviewData.currentSwitch, repository.currentFronters.value)
    }
}
