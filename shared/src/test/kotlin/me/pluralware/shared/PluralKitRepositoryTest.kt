package me.pluralware.shared

import app.cash.turbine.test
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import me.pluralware.shared.mock.MockPluralKitClient
import me.pluralware.shared.repository.PkResult
import me.pluralware.shared.repository.PluralKitRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PluralKitRepositoryTest {

    private fun repo() = PluralKitRepository(MockPluralKitClient(artificialLatencyMillis = 0))

    @Test
    fun `refreshFronters surfaces current switch and updates StateFlow`() = runTest {
        val r = repo()
        r.currentFronters.test {
            assertEquals(null, awaitItem()) // initial
            val result = r.refreshFronters()
            assertTrue(result is PkResult.Success)
            val emitted = awaitItem()
            assertNotNull(emitted)
        }
    }

    @Test
    fun `registerSwitch with empty list creates switch-out`() = runTest {
        val r = repo()
        val out = r.registerSwitch(emptyList())
        assertTrue(out is PkResult.Success)
        val switch = (out as PkResult.Success).value
        assertTrue(switch.isSwitchOut)
    }

    @Test
    fun `member cache is reused unless force-refreshed`() = runTest {
        val r = repo()
        val first = (r.refreshMembers() as PkResult.Success).value
        val second = (r.refreshMembers() as PkResult.Success).value
        // Same instances because the cache returned them, not the client.
        assertTrue(first === second)
    }
}
