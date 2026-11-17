package me.pluralware.mobile.widget

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.pluralware.shared.api.PluralKitClient
import me.pluralware.shared.api.PluralKitToken
import me.pluralware.shared.fronter.FronterDisplay
import me.pluralware.shared.fronter.FronterSource
import me.pluralware.shared.fronter.FronterWording
import me.pluralware.shared.handoff.MemberSync
import me.pluralware.shared.handoff.SwitchSync
import me.pluralware.shared.model.Member
import me.pluralware.shared.model.Switch
import me.pluralware.shared.preview.PreviewData
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.settings.InMemoryLastFronterCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetSurfacesTest {

    private val client = mockk<PluralKitClient>()
    private val cache = InMemoryLastFronterCache()
    private val tokens = MutableStateFlow<PluralKitToken?>(PluralKitToken("token"))
    private var widgets = 2
    private var updates = 0

    private fun switchOf(uuid: String, name: String) =
        Switch(uuid, PreviewData.anchor, listOf(Member(id = name, uuid = "m-$name", name = name, displayName = null, pronouns = null, color = null, avatarUrl = null)))

    private val alex = switchOf("s1", "Alex")
    private val sam = switchOf("s2", "Sam")

    private fun TestScope.surfaces() = WidgetSurfaces(
        source = FronterSource(
            token = { tokens.value },
            repositoryFor = { PluralKitRepository(client) },
            cache = cache,
            wording = FronterWording(FronterDisplay("Fronter", "Set up", "-"), FronterDisplay("Fronter", "Sign in", "-")),
        ),
        token = { tokens.value },
        cache = cache,
        hasWidgets = { widgets > 0 },
        updateWidgets = { updates++ },
        scope = backgroundScope,
    )

    private fun shown(surfaces: WidgetSurfaces) = surfaces.display.value?.text

    @Test
    fun `a switch the app publishes is shown without a fetch`() = runTest {
        val surfaces = surfaces()

        surfaces.publish(alex, PluralKitToken("token"))

        runCurrent()

        assertEquals("Fronting: Alex", shown(surfaces))
        assertEquals(1, updates)
        coVerify(exactly = 0) { client.getCurrentFronters() }
    }

    @Test
    fun `widgets drawing together share one fetch`() = runTest {
        val surfaces = surfaces()
        val reply = CompletableDeferred<Switch>()
        coEvery { client.getCurrentFronters() } coAnswers { reply.await() }

        repeat(3) { launch { surfaces.prepare() } }
        runCurrent()
        reply.complete(alex)
        runCurrent()

        assertEquals("Fronting: Alex", shown(surfaces))
        coVerify(exactly = 1) { client.getCurrentFronters() }
    }

    @Test
    fun `Android's poll fetches again, and a fetch begun before the watch reports a new switch isn't shown`() = runTest {
        val surfaces = surfaces()
        surfaces.publish(alex, PluralKitToken("token"))
        runCurrent()
        cache.set(cache.get()!!.copy(fetchedAtEpochMillis = 0L))
        val first = CompletableDeferred<Switch>()
        coEvery { client.getCurrentFronters() } coAnswers { first.await() } andThen sam

        surfaces.poll()
        runCurrent()
        surfaces.onRemoteSwitch(SwitchSync.noticeOf(sam, PluralKitToken("token")))
        runCurrent()
        first.complete(alex)
        runCurrent()

        assertEquals("Fronting: Sam", shown(surfaces))
    }

    @Test
    fun `a fetch is shown even if the widget that asked stopped waiting`() = runTest {
        val surfaces = surfaces()
        val reply = CompletableDeferred<Switch>()
        coEvery { client.getCurrentFronters() } coAnswers { reply.await() }

        val waiting = launch { surfaces.prepare() }
        runCurrent()
        waiting.cancel()
        reply.complete(alex)
        runCurrent()

        assertEquals("Fronting: Alex", shown(surfaces))
        assertEquals(1, updates)
    }

    @Test
    fun `widgets that fail to update don't stop the next update`() = runTest {
        var failing = true
        val surfaces = WidgetSurfaces(
            source = FronterSource({ tokens.value }, { PluralKitRepository(client) }, cache, FronterWording(FronterDisplay("", "", ""), FronterDisplay("", "", ""))),
            token = { tokens.value },
            cache = cache,
            hasWidgets = { true },
            updateWidgets = { if (failing) error("launcher went away") else updates++ },
            // Supervised, as the app's is.
            scope = CoroutineScope(backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext.job)),
        )

        surfaces.publish(alex, PluralKitToken("token"))
        runCurrent()
        failing = false
        surfaces.publish(sam, PluralKitToken("token"))
        runCurrent()

        assertEquals("Fronting: Sam", shown(surfaces))
        assertEquals(1, updates)
    }

    @Test
    fun `a display fetched for a token that's since gone is fetched again`() = runTest {
        val surfaces = surfaces()
        val first = CompletableDeferred<Switch>()
        coEvery { client.getCurrentFronters() } coAnswers { first.await() } andThen sam

        launch { surfaces.prepare() }
        runCurrent()
        tokens.value = PluralKitToken("another")
        first.complete(alex)
        runCurrent()

        assertEquals("Fronting: Sam", shown(surfaces))
    }

    @Test
    fun `a member edit for this system fetches again, so a rename shows`() = runTest {
        val surfaces = surfaces()
        surfaces.publish(alex, PluralKitToken("token"))
        runCurrent()
        coEvery { client.getCurrentFronters() } returns switchOf("s1", "Alexis")

        surfaces.onMemberEdit(MemberSync.noticeOf(PluralKitToken("token"), 1L))

        runCurrent()

        assertEquals("Fronting: Alexis", shown(surfaces))
        assertEquals(2, updates)
    }

    @Test
    fun `news about another system, or with no widgets placed, fetches nothing`() = runTest {
        val surfaces = surfaces()
        surfaces.publish(alex, PluralKitToken("token"))
        runCurrent()

        surfaces.onRemoteSwitch(SwitchSync.noticeOf(sam, PluralKitToken("another")))
        runCurrent()
        surfaces.onMemberEdit(MemberSync.noticeOf(PluralKitToken("another"), 1L))
        widgets = 0
        surfaces.onRemoteSwitch(SwitchSync.noticeOf(sam, PluralKitToken("token")))
        runCurrent()

        assertEquals("Fronting: Alex", shown(surfaces))
        assertEquals(1, updates)
        coVerify(exactly = 0) { client.getCurrentFronters() }
    }

    @Test
    fun `a new token or signing out forgets the old system's line, but the token it starts with doesn't`() = runTest {
        val surfaces = surfaces()
        surfaces.publish(alex, PluralKitToken("token"))
        runCurrent()
        backgroundScope.launch { surfaces.followToken(tokens) }
        runCurrent()
        assertEquals("Fronting: Alex", cache.get()?.text)

        coEvery { client.getCurrentFronters() } returns sam
        tokens.value = PluralKitToken("another")
        runCurrent()

        assertEquals("Fronting: Sam", shown(surfaces))
        assertEquals(PluralKitToken("another").fingerprint, cache.get()?.tokenId)

        tokens.value = null
        runCurrent()

        assertEquals("Set up", shown(surfaces))
        assertNull(cache.get())
    }
}
