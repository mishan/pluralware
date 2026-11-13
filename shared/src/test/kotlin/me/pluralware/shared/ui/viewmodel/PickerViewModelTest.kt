package me.pluralware.shared.ui.viewmodel

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import me.pluralware.shared.api.PluralKitClient
import me.pluralware.shared.api.PluralKitHttpException
import me.pluralware.shared.model.Switch
import me.pluralware.shared.preview.PreviewData
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.ui.state.UiState
import me.pluralware.shared.util.MainDispatcherRule
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
        coEvery { forgetMembers() } returns Unit
    }
    private val repository = PluralKitRepository(client)

    private fun viewModel() = PickerViewModel(repository = repository, confirmationLingerMillis = 0)

    @Test
    fun `members edited elsewhere while the picker is open show, and the selection stays`() = runTest {
        val vm = viewModel()
        val chosen = vm.pickSomeoneElse()
        val renamed = PreviewData.members.map { if (it.uuid == chosen) it.copy(name = "Renamed") else it }
        coEvery { client.getOwnMembers() } returns renamed

        repository.forgetMembers()

        assertEquals(renamed, (vm.state.value.members as UiState.Content).value)
        assertTrue(chosen in vm.state.value.selectedUuids)
    }

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

    private val m = PreviewData.members

    @Test
    fun `opens with the current fronters selected, in front order`() = runTest {
        val vm = viewModel()

        assertEquals(PreviewData.currentSwitch.members.map { it.uuid }, vm.state.value.selectedUuids)
        assertTrue(vm.state.value.seeded)
        assertEquals(UiState.Content(m), vm.state.value.members)
    }

    @Test
    fun `toggling appends in tap order and removes without reordering the rest`() = runTest {
        val vm = viewModel()
        vm.deselectAll()

        vm.toggle(m[2].uuid)
        vm.toggle(m[0].uuid)
        vm.toggle(m[4].uuid)
        assertEquals(listOf(m[2].uuid, m[0].uuid, m[4].uuid), vm.state.value.selectedUuids)

        vm.toggle(m[0].uuid)
        assertEquals(listOf(m[2].uuid, m[4].uuid), vm.state.value.selectedUuids)
    }

    @Test
    fun `deselect all empties the selection`() = runTest {
        val vm = viewModel()

        vm.deselectAll()

        assertTrue(vm.state.value.selectedUuids.isEmpty())
    }

    @Test
    fun `confirming with nobody selected registers a switch-out`() = runTest {
        coEvery { client.registerSwitch(emptyList()) } returns PreviewData.switchOut
        val vm = viewModel()
        vm.deselectAll()

        vm.submitSelection()

        coVerify { client.registerSwitch(emptyList()) }
        assertEquals(PreviewData.switchOut, repository.currentFronters.value)
    }

    @Test
    fun `reordering the same fronters is a real switch`() = runTest {
        val reversed = PreviewData.currentSwitch.members.reversed()
        coEvery { client.registerSwitch(any()) } returns PreviewData.currentSwitch.copy(uuid = "r", members = reversed)
        val vm = viewModel()
        vm.deselectAll()
        reversed.forEach { vm.toggle(it.uuid) }

        vm.submitSelection()

        coVerify { client.registerSwitch(reversed.map { it.uuid }) }
    }

    @Test
    fun `a second confirm while saving registers once`() = runTest {
        val answer = kotlinx.coroutines.CompletableDeferred<Switch>()
        coEvery { client.registerSwitch(any()) } coAnswers { answer.await() }
        val vm = viewModel()
        vm.pickSomeoneElse()

        vm.submitSelection()
        assertTrue(vm.state.value.submitting)
        vm.submitSelection()
        answer.complete(PreviewData.switchOut)

        coVerify(exactly = 1) { client.registerSwitch(any()) }
        assertFalse(vm.state.value.submitting)
    }

    @Test
    fun `a finished switch tells the screen it's done`() = runTest {
        coEvery { client.registerSwitch(any()) } returns PreviewData.switchOut
        val vm = viewModel()
        vm.pickSomeoneElse()
        var done = 0
        val collector = launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            vm.doneEvents.collect { done++ }
        }

        vm.submitSelection()
        advanceUntilIdle()

        assertEquals(1, done)
        collector.cancel()
    }

    @Test
    fun `members that won't load show an error, and retry recovers`() = runTest {
        coEvery { client.getOwnMembers() } throws PluralKitHttpException(500, "Oops")
        val vm = viewModel()
        assertTrue(vm.state.value.members is UiState.Error)

        coEvery { client.getOwnMembers() } returns m
        vm.loadMembers()

        assertEquals(UiState.Content(m), vm.state.value.members)
    }

    @Test
    fun `a rejected token offers sign-out`() = runTest {
        coEvery { client.getOwnMembers() } throws PluralKitHttpException(401, "Unauthorized")

        val error = viewModel().state.value.members as UiState.Error

        assertTrue(error.unauthorized)
    }

    @Test
    fun `without the current fronters, it opens empty and confirming asks PluralKit`() = runTest {
        coEvery { client.getCurrentFronters() } throws java.io.IOException("offline")
        coEvery { client.registerSwitch(any()) } returns PreviewData.switchOut
        val vm = viewModel()

        assertTrue(vm.state.value.selectedUuids.isEmpty())
        vm.submitSelection()

        coVerify(exactly = 1) { client.registerSwitch(emptyList()) }
    }
}
