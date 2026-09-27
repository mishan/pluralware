package me.pluralware.wear.complication

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import me.pluralware.shared.api.PluralKitClient
import me.pluralware.shared.api.PluralKitHttpException
import me.pluralware.shared.api.PluralKitToken
import me.pluralware.shared.preview.PreviewData
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.settings.InMemoryLastFronterCache
import me.pluralware.shared.settings.LastFronter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

class FronterSourceTest {

    private val client = mockk<PluralKitClient>()
    private val cache = InMemoryLastFronterCache()
    private var token: PluralKitToken? = PluralKitToken("token")
    private var now = 10_000_000L

    private val source = FronterSource(
        token = { token },
        repositoryFor = { PluralKitRepository(client) },
        cache = cache,
        clock = { now },
    )

    private val cachedAlex = LastFronter("Fronting: Alex", "Current fronter: Alex", fetchedAtEpochMillis = now)

    @Test
    fun `no token asks for setup, even with a cached line`() = runTest {
        token = null
        cache.set(cachedAlex)

        assertEquals(FronterSource.SETUP, source.current())
    }

    @Test
    fun `a fresh cached line is answered without fetching`() = runTest {
        cache.set(cachedAlex)
        now += FronterSource.FRESH_FOR_MILLIS - 1

        val display = source.current()

        assertEquals("Fronting: Alex", display.text)
        assertEquals(FronterComplicationFormatter.TITLE, display.title)
        coVerify(exactly = 0) { client.getCurrentFronters() }
    }

    @Test
    fun `a stale cached line is refetched and replaced`() = runTest {
        cache.set(cachedAlex)
        now += FronterSource.FRESH_FOR_MILLIS
        coEvery { client.getCurrentFronters() } returns PreviewData.switchOut

        val display = source.current()

        assertEquals("Switched out", display.text)
        assertEquals("Switched out", cache.get()?.text)
        assertEquals(now, cache.get()?.fetchedAtEpochMillis)
    }

    @Test
    fun `a failed fetch falls back to the cached line, marked last known`() = runTest {
        cache.set(cachedAlex.copy(fetchedAtEpochMillis = 0))
        coEvery { client.getCurrentFronters() } throws IOException("offline")

        val display = source.current()

        assertEquals("Last known", display.title)
        assertEquals("Fronting: Alex", display.text)
        assertEquals("Last known. Current fronter: Alex", display.description)
    }

    @Test
    fun `a failed fetch with nothing cached is unavailable`() = runTest {
        coEvery { client.getCurrentFronters() } throws IOException("offline")

        assertEquals(FronterSource.UNAVAILABLE, source.current())
    }

    @Test
    fun `a rejected token clears the cache and asks to sign in again`() = runTest {
        cache.set(cachedAlex.copy(fetchedAtEpochMillis = 0))
        coEvery { client.getCurrentFronters() } throws PluralKitHttpException(401, "Unauthorized")

        assertEquals(FronterSource.SIGN_IN_AGAIN, source.current())
        assertNull(cache.get())
    }

    @Test
    fun `a system with no switches reads as such`() = runTest {
        coEvery { client.getCurrentFronters() } returns null

        assertEquals("No switches yet", source.current().text)
    }

    @Test
    fun `remember caches a switch the app already has, stamped now`() = runTest {
        source.remember(PreviewData.switchOut)

        assertEquals(LastFronter("Switched out", "Switched out — nobody fronting", now), cache.get())
    }
}
