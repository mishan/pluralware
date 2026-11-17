package org.foggyden.pluralware.shared.ui.viewmodel

import java.io.IOException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.foggyden.pluralware.shared.api.PluralKitClient
import org.foggyden.pluralware.shared.mock.MockPluralKitClient
import org.foggyden.pluralware.shared.model.Switch
import org.foggyden.pluralware.shared.repository.PluralKitRepository
import org.foggyden.pluralware.shared.ui.state.UiState
import org.foggyden.pluralware.shared.util.MainDispatcherRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The picker opened from History, to change an earlier switch's members. */
class PickerFixTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    /** PluralKit, counting members fixes, which can be made to fail. */
    private class Client(val pk: MockPluralKitClient = MockPluralKitClient(artificialLatencyMillis = 0)) :
        PluralKitClient by pk {
        val fixes = mutableListOf<List<String>>()
        var failure: Exception? = null

        override suspend fun setSwitchMembers(uuid: String, memberUuids: List<String>): Switch {
            fixes += memberUuids
            failure?.let { throw it }
            return pk.setSwitchMembers(uuid, memberUuids)
        }
    }

    private val client = Client()
    private val registered = mutableListOf<Switch>()
    private val repository = PluralKitRepository(client) { registered += it }
    private val older: Switch = runBlocking { client.pk.getRecentSwitches(10)[1] }
    private val members = runBlocking { client.pk.getOwnMembers() }

    private fun fixing(switch: Switch = older) = PickerViewModel(
        repository,
        confirmationLingerMillis = 0,
        editing = PickerViewModel.Editing(switch.uuid, switch.members.map { it.uuid }),
    )

    @Test
    fun `it opens with that switch's members selected, in their order`() = runTest {
        val vm = fixing()

        assertEquals(older.members.map { it.uuid }, vm.state.value.selectedUuids)
        assertTrue(vm.state.value.seeded)
    }

    @Test
    fun `saving sets that switch's members, and registers no new switch`() = runTest {
        val vm = fixing()
        val added = members.first { it.uuid !in vm.state.value.selectedUuids }.uuid
        vm.toggle(added)
        val done = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.doneEvents.first() }

        vm.submitSelection()

        val expected = older.members.map { it.uuid } + added
        assertEquals(expected, client.pk.getRecentSwitches(10)[1].members.map { it.uuid })
        assertEquals(emptyList<Switch>(), registered)
        assertTrue(vm.state.value.confirmed)
        assertTrue(done.isCompleted)
    }

    @Test
    fun `saving the members it already has sends nothing`() = runTest {
        val vm = fixing()
        val done = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.doneEvents.first() }

        vm.submitSelection()

        assertEquals(emptyList<List<String>>(), client.fixes)
        assertTrue(done.isCompleted)
    }

    @Test
    fun `a fix that fails says so`() = runTest {
        val vm = fixing()
        vm.deselectAll()
        client.failure = IOException("offline")

        vm.submitSelection()

        assertTrue(vm.state.value.members is UiState.Error)
        assertEquals(listOf(emptyList<String>()), client.fixes)
    }

    @Test
    fun `a member deleted since History loaded drops out, so saving still works`() = runTest {
        val gone = "uuid-deleted-since"
        val vm = PickerViewModel(
            repository,
            confirmationLingerMillis = 0,
            editing = PickerViewModel.Editing(older.uuid, older.members.map { it.uuid } + gone),
        )

        assertEquals(older.members.map { it.uuid }, vm.state.value.selectedUuids)
        vm.submitSelection()
        assertEquals("what PluralKit has already", emptyList<List<String>>(), client.fixes)
    }

    @Test
    fun `after a save that may have landed, going back to the old members is sent, and PluralKit having them is a save`() =
        runTest {
            val vm = fixing()
            val original = vm.state.value.selectedUuids
            vm.deselectAll()
            client.failure = IOException("never reached PluralKit")
            vm.submitSelection()
            client.failure = null

            vm.loadMembers()
            original.forEach(vm::toggle)
            val done = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.doneEvents.first() }
            vm.submitSelection()

            assertEquals(listOf(emptyList(), original), client.fixes)
            assertTrue("PluralKit said it already had them", vm.state.value.confirmed)
            assertTrue(done.isCompleted)
        }

    @Test
    fun `a member list that answers after a newer one can't take a member back out`() = runTest {
        val added = members.first { it.uuid !in older.members.map { m -> m.uuid } }
        val firstAnswer = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        val slowFirst = object : PluralKitClient by client {
            override suspend fun getOwnMembers(): List<org.foggyden.pluralware.shared.model.Member> {
                if (++calls == 1) {
                    firstAnswer.await()
                    return members - added
                }
                return members
            }
        }
        val repository = PluralKitRepository(slowFirst)
        val vm = PickerViewModel(
            repository,
            confirmationLingerMillis = 0,
            editing = PickerViewModel.Editing(older.uuid, older.members.map { it.uuid }),
        )

        repository.forgetMembers()
        vm.toggle(added.uuid)
        firstAnswer.complete(Unit)

        assertTrue(added.uuid in vm.state.value.selectedUuids)
        assertEquals(UiState.Content(members), vm.state.value.members)
    }

    @Test
    fun `when the newest member list fails, the picker says so rather than loading forever`() = runTest {
        val firstAnswer = kotlinx.coroutines.CompletableDeferred<Unit>()
        var calls = 0
        val flaky = object : PluralKitClient by client {
            override suspend fun getOwnMembers(): List<org.foggyden.pluralware.shared.model.Member> {
                if (++calls == 1) {
                    firstAnswer.await()
                    return members
                }
                throw IOException("offline")
            }
        }
        val repository = PluralKitRepository(flaky)
        val vm = PickerViewModel(
            repository,
            confirmationLingerMillis = 0,
            editing = PickerViewModel.Editing(older.uuid, older.members.map { it.uuid }),
        )

        repository.forgetMembers()
        firstAnswer.complete(Unit)

        assertTrue(vm.state.value.members is UiState.Error)
    }
}
