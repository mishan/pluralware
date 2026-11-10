package me.pluralware.wear.complication

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import me.pluralware.shared.api.PluralKitClient
import me.pluralware.shared.api.PluralKitHttpException
import me.pluralware.shared.api.PluralKitToken
import me.pluralware.shared.handoff.SwitchSync
import me.pluralware.shared.preview.PreviewData
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.settings.InMemoryLastFronterCache
import me.pluralware.shared.settings.LastFronter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private val cachedAlex = LastFronter(
        "Fronting: Alex",
        "Current fronter: Alex",
        fetchedAtEpochMillis = now,
        tokenId = PluralKitToken("token").fingerprint,
    )

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
        source.remember(PreviewData.switchOut, PluralKitToken("token"))

        assertEquals(
            LastFronter(
                "Switched out",
                "Switched out — nobody fronting",
                now,
                PluralKitToken("token").fingerprint,
                switchUuid = PreviewData.switchOut.uuid,
                switchAtEpochMillis = PreviewData.switchOut.timestamp.toEpochMilli(),
            ),
            cache.get(),
        )
    }

    @Test
    fun `a line cached for another token is never shown`() = runTest {
        cache.set(cachedAlex.copy(tokenId = PluralKitToken("other system").fingerprint))
        coEvery { client.getCurrentFronters() } throws IOException("offline")

        // Fresh, and the fetch fails, yet it isn't even used as "last known".
        assertEquals(FronterSource.UNAVAILABLE, source.current())
    }

    @Test
    fun `a fetch that finishes after a re-pair isn't cached`() = runTest {
        coEvery { client.getCurrentFronters() } coAnswers {
            token = PluralKitToken("new system") // re-paired while in flight
            PreviewData.currentSwitch
        }

        source.current()

        assertNull(cache.get())
    }

    @Test
    fun `the app can't publish a switch fetched with an old token`() = runTest {
        source.remember(PreviewData.currentSwitch, fetchedWith = PluralKitToken("old system"))

        assertNull(cache.get())
    }

    @Test
    fun `the cache key never contains the token`() {
        val id = PluralKitToken("secret-token-value").fingerprint
        assertEquals(32, id.length)
        assertTrue("secret" !in id)
    }

    @Test
    fun `a different switch from the phone makes the next request fetch`() = runTest {
        source.remember(PreviewData.currentSwitch, PluralKitToken("token"))
        coEvery { client.getCurrentFronters() } returns PreviewData.switchOut

        assertTrue(source.expire(SwitchSync.noticeOf(PreviewData.switchOut, PluralKitToken("token"))))

        assertEquals("Switched out", source.current().text)
        coVerify(exactly = 1) { client.getCurrentFronters() }
    }

    @Test
    fun `the phone's echo of the cached switch changes nothing`() = runTest {
        source.remember(PreviewData.currentSwitch, PluralKitToken("token"))
        val before = cache.get()

        assertFalse(source.expire(SwitchSync.noticeOf(PreviewData.currentSwitch, PluralKitToken("token"))))

        assertEquals(before, cache.get())
        source.current()
        coVerify(exactly = 0) { client.getCurrentFronters() }
    }

    @Test
    fun `a moved switch is news, though its uuid is the same`() = runTest {
        source.remember(PreviewData.currentSwitch, PluralKitToken("token"))
        val moved = PreviewData.currentSwitch.copy(timestamp = PreviewData.currentSwitch.timestamp.minusSeconds(600))

        assertTrue(source.expire(SwitchSync.noticeOf(moved, PluralKitToken("token"))))
        assertEquals(0L, cache.get()?.fetchedAtEpochMillis)
    }

    @Test
    fun `with nothing cached, a switch from the phone asks the surfaces to update`() = runTest {
        assertTrue(source.expire(SwitchSync.noticeOf(PreviewData.switchOut, PluralKitToken("token"))))
    }

    @Test
    fun `a switch for another system leaves the cache alone`() = runTest {
        cache.set(cachedAlex)

        assertFalse(source.expire(SwitchSync.noticeOf(PreviewData.switchOut, PluralKitToken("other system"))))

        assertEquals(cachedAlex, cache.get())
    }

    @Test
    fun `signed out, a switch from the phone is ignored`() = runTest {
        token = null

        assertFalse(source.expire(SwitchSync.noticeOf(PreviewData.switchOut, PluralKitToken("token"))))
    }
}
