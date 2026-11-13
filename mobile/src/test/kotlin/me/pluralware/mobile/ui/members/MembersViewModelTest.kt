package me.pluralware.mobile.ui.members

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.pluralware.shared.api.PluralKitClient
import me.pluralware.shared.api.PluralKitHttpException
import me.pluralware.shared.mock.MockPluralKitClient
import me.pluralware.shared.model.Member
import me.pluralware.shared.model.MemberField
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.ui.state.UiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MembersViewModelTest {

    private val pk = MockPluralKitClient(artificialLatencyMillis = 0)

    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())

    @After fun tearDown() = Dispatchers.resetMain()

    private fun MembersViewModel.shown() = (state.value as UiState.Content<List<Member>>).value

    @Test
    fun `it lists the members, fresh each time it loads`() = runTest {
        val vm = MembersViewModel(PluralKitRepository(pk))
        val first = vm.shown()
        // Renamed in Discord while the list was open; the screen reloads on return.
        pk.updateMember(first[0].uuid, mapOf(MemberField.Name to "Renamed"))

        vm.load()

        assertEquals(pk.getOwnMembers(), vm.shown())
        assertEquals("Renamed", vm.shown()[0].name)
    }

    @Test
    fun `a list that won't load is an error, a rejected token says so`() = runTest {
        val cases = listOf(
            PluralKitHttpException(500, "Internal Server Error") to false,
            PluralKitHttpException(401, "Unauthorized") to true,
        )
        for ((failure, unauthorized) in cases) {
            val client = mockk<PluralKitClient>()
            coEvery { client.getOwnMembers() } throws failure
            val vm = MembersViewModel(PluralKitRepository(client))

            assertEquals(unauthorized, (vm.state.value as UiState.Error).unauthorized)
        }
    }

    @Test
    fun `reloading keeps the list on screen rather than flashing a spinner`() = runTest {
        val vm = MembersViewModel(PluralKitRepository(pk))
        val seen = mutableListOf<UiState<List<Member>>>()
        val watching = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.state.collect { seen += it } }

        vm.load()
        watching.cancel()

        assertTrue(seen.none { it is UiState.Loading })
    }
}
