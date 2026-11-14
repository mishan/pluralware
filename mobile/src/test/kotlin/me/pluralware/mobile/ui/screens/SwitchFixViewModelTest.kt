package me.pluralware.mobile.ui.screens

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.pluralware.shared.api.PluralKitClient
import me.pluralware.shared.api.PluralKitHttpException
import me.pluralware.shared.mock.MockPluralKitClient
import me.pluralware.shared.model.Switch
import me.pluralware.shared.repository.PluralKitRepository
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SwitchFixViewModelTest {

    /** PluralKit, counting deletes, which can be held or made to fail. */
    private class Client(val pk: MockPluralKitClient = MockPluralKitClient(artificialLatencyMillis = 0)) :
        PluralKitClient by pk {
        var deletes = 0
        var holdDelete: CompletableDeferred<Unit>? = null
        var failure: Exception? = null

        override suspend fun deleteSwitch(uuid: String) {
            deletes++
            holdDelete?.await()
            failure?.let { throw it }
            pk.deleteSwitch(uuid)
        }
    }

    private val client = Client()
    private lateinit var latest: Switch
    private lateinit var older: Switch

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        runBlocking { client.pk.getRecentSwitches(10).let { latest = it[0]; older = it[1] } }
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private val vm by lazy { SwitchFixViewModel(PluralKitRepository(client)) }

    @Test
    fun `a move PluralKit takes says so`() = runTest {
        val to = older.timestamp.plusSeconds(60)

        vm.move(latest.uuid, to)

        assertEquals(FixNotice.Moved, vm.state.value.notice)
        assertEquals(to, client.pk.getRecentSwitches(10)[0].timestamp)
    }

    @Test
    fun `a move outside its neighbors, as PluralKit has them now, says where it may go`() = runTest {
        vm.move(latest.uuid, older.timestamp.minusSeconds(60))

        val notice = vm.state.value.notice as FixNotice.OutOfRange
        assertEquals(older.timestamp, notice.after)
        assertEquals(latest.timestamp, client.pk.getRecentSwitches(10)[0].timestamp)
    }

    @Test
    fun `a delete says whether it deleted the switch or found it gone`() = runTest {
        vm.delete(older.uuid)
        val first = vm.state.value.notice
        vm.noticeShown()
        vm.delete(older.uuid)

        assertEquals(listOf(FixNotice.Deleted, FixNotice.AlreadyGone), listOf(first, vm.state.value.notice))
    }

    @Test
    fun `a failed fix says why, and offers sign-out only for a rejected token`() = runTest {
        val cases = listOf(
            IOException("offline") to FixNotice.Failed("Couldn't reach PluralKit. Check your connection and try again."),
            PluralKitHttpException(500, "Internal error") to
                FixNotice.Failed("PluralKit couldn't delete it: Internal error"),
            PluralKitHttpException(401, "Unauthorized") to
                FixNotice.Failed("PluralKit no longer accepts this token. Sign out, then connect again.", unauthorized = true),
        )
        for ((error, expected) in cases) {
            client.failure = error
            vm.delete(older.uuid)
            assertEquals(error.toString(), expected, vm.state.value.notice)
            vm.noticeShown()
        }
    }

    @Test
    fun `one fix at a time`() = runTest {
        client.holdDelete = CompletableDeferred()

        vm.delete(older.uuid)
        assertTrue(vm.state.value.busy)
        vm.delete(older.uuid)
        vm.move(latest.uuid, older.timestamp.plusSeconds(60))
        client.holdDelete!!.complete(Unit)

        assertEquals(1, client.deletes)
        assertFalse(vm.state.value.busy)
        assertEquals("the move wasn't sent", latest.timestamp, client.pk.getRecentSwitches(10)[0].timestamp)
    }
}
