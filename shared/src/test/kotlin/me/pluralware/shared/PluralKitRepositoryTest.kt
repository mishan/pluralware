package me.pluralware.shared

import app.cash.turbine.test
import io.mockk.coEvery
import io.mockk.mockk
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import me.pluralware.shared.api.PluralKitClient
import me.pluralware.shared.api.PluralKitHttpException
import me.pluralware.shared.mock.MockPluralKitClient
import me.pluralware.shared.repository.PkResult
import me.pluralware.shared.repository.PluralKitRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `a rejected token is a Failure that says so`() = runTest {
        val client = mockk<PluralKitClient>()
        coEvery { client.getCurrentFronters() } throws PluralKitHttpException(401, "Unauthorized")

        val result = PluralKitRepository(client).refreshFronters()

        assertTrue(result is PkResult.Failure)
        assertTrue((result as PkResult.Failure).isUnauthorized)
    }

    @Test
    fun `other failures are not unauthorized`() = runTest {
        val client = mockk<PluralKitClient>()
        coEvery { client.getCurrentFronters() } throws PluralKitHttpException(500, "Oops")

        val result = PluralKitRepository(client).refreshFronters() as PkResult.Failure

        assertFalse(result.isUnauthorized)
    }

    @Test(expected = CancellationException::class)
    fun `cancellation propagates instead of becoming a Failure`() = runTest {
        val client = mockk<PluralKitClient>()
        coEvery { client.getCurrentFronters() } throws CancellationException("gone")

        PluralKitRepository(client).refreshFronters()
    }

    @Test
    fun `loaded fronters tell no switches apart from not loaded yet`() = runTest {
        val client = mockk<PluralKitClient>()
        coEvery { client.getCurrentFronters() } returns null
        val r = PluralKitRepository(client)
        assertEquals(null, r.loadedFronters.value)

        r.refreshFronters()

        assertEquals(me.pluralware.shared.repository.LoadedFronters(null), r.loadedFronters.value)
    }

    @Test
    fun `the registered-switch hook fires for registrations only`() = runTest {
        val seen = mutableListOf<me.pluralware.shared.model.Switch>()
        val r = PluralKitRepository(MockPluralKitClient(artificialLatencyMillis = 0)) { seen += it }

        r.refreshFronters()
        assertTrue("observed switches are never announced", seen.isEmpty())

        val registered = (r.registerSwitch(emptyList()) as PkResult.Success).value
        assertEquals(listOf(registered), seen)
    }

    @Test
    fun `a failed registration fires nothing`() = runTest {
        val client = mockk<PluralKitClient>()
        coEvery { client.registerSwitch(any()) } throws PluralKitHttpException(500, "Oops")
        var fired = false

        PluralKitRepository(client) { fired = true }.registerSwitch(listOf("uuid"))

        assertFalse(fired)
    }
}
