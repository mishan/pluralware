package me.pluralware.wear.ui.viewmodel

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import me.pluralware.shared.mock.MockPluralKitClient
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.wear.ui.state.UiState
import me.pluralware.wear.util.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PickerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private fun viewModel(onSwitchRegistered: () -> Unit) = PickerViewModel(
        repository = PluralKitRepository(MockPluralKitClient(artificialLatencyMillis = 0)),
        confirmationLingerMillis = 0,
        onSwitchRegistered = onSwitchRegistered,
    )

    @Test
    fun `onSwitchRegistered fires once when a real switch is registered`() = runTest {
        var fired = 0
        val vm = viewModel { fired++ }

        // init seeds the selection to the current fronter; pick a different member
        // so submit() actually registers a switch instead of short-circuiting.
        val members = (vm.state.value.members as UiState.Content).value
        vm.deselectAll()
        vm.toggle(members[2].uuid)
        vm.submitSelection()

        assertEquals(1, fired)
    }

    @Test
    fun `onSwitchRegistered does not fire on a no-op resubmit`() = runTest {
        var fired = 0
        val vm = viewModel { fired++ }

        // Seeded selection already equals the current fronter — this is a no-op,
        // so the complication shouldn't be poked.
        vm.submitSelection()

        assertEquals(0, fired)
    }
}
