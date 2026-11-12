package me.pluralware.mobile.watch

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import me.pluralware.shared.api.PluralKitToken
import me.pluralware.shared.preview.PreviewData
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchLinkTest {

    private val token = PluralKitToken("token")
    private val pushed = mutableListOf<PluralKitToken>()
    private val writes = mutableListOf<String>()

    private fun TestScope.link(
        scope: CoroutineScope = backgroundScope,
        pushSettings: suspend () -> Unit = {},
        watches: suspend () -> List<String>,
    ) = WatchLink(
        scope = scope,
        connectedWatches = watches,
        pushToken = {
            pushed += it
            writes += "token"
        },
        pushSettings = pushSettings,
        pushSignOut = { writes += "sign-out" },
    )

    private val noWear: suspend () -> List<String> = {
        throw ApiException(Status(CommonStatusCodes.API_NOT_CONNECTED))
    }

    @Test
    fun `a phone without Wear OS sends nothing and says so quietly`() = runTest(UnconfinedTestDispatcher()) {
        val link = link(watches = noWear)

        link.send(token)

        assertEquals(WatchState.Unsupported, link.state.value)
        assertTrue(pushed.isEmpty())
    }

    @Test
    fun `sends to a connected watch`() = runTest(UnconfinedTestDispatcher()) {
        val link = link { listOf("Pixel Watch") }

        link.send(token)

        assertEquals(WatchState.Sent("Pixel Watch"), link.state.value)
        assertEquals(listOf(token), pushed)
    }

    @Test
    fun `queues the token for a paired watch that isn't connected`() = runTest(UnconfinedTestDispatcher()) {
        val link = link { emptyList() }

        link.send(token)

        assertEquals(WatchState.NotConnected(queued = true), link.state.value)
        assertEquals(listOf(token), pushed)
    }

    @Test
    fun `a send still looking for the watch doesn't undo a sign-out`() = runTest(UnconfinedTestDispatcher()) {
        val watches = CompletableDeferred<List<String>>()
        val link = link { watches.await() }
        link.send(token)

        link.signOut()
        watches.complete(listOf("Pixel Watch"))

        assertEquals(listOf("sign-out"), writes)
    }

    @Test
    fun `a check still looking for the watch leaves a send's result alone`() = runTest(UnconfinedTestDispatcher()) {
        // Whatever the send came to: sent, queued for a watch out of reach, or failed.
        val cases = listOf(
            listOf("Pixel Watch") to WatchState.Sent("Pixel Watch"),
            emptyList<String>() to WatchState.NotConnected(queued = true),
            null to WatchState.Failed("no watch API"),
        )
        for ((watchesAtSend, sendResult) in cases) {
            val firstLookup = CompletableDeferred<List<String>>()
            var lookups = 0
            val link = link {
                if (lookups++ == 0) firstLookup.await() else watchesAtSend ?: error("no watch API")
            }
            link.check()
            link.send(token)

            firstLookup.complete(listOf("Pixel Watch"))

            assertEquals(sendResult, link.state.value)
        }
    }

    @Test
    fun `a cancelled send writes no token`() = runTest(UnconfinedTestDispatcher()) {
        val sends = CoroutineScope(backgroundScope.coroutineContext + Job())
        val link = link(scope = sends, pushSettings = { CompletableDeferred<Unit>().await() }) { listOf("Pixel Watch") }
        link.send(token)

        sends.cancel()

        assertTrue(pushed.isEmpty())
    }

    @Test
    fun `check reports a connected watch`() = runTest(UnconfinedTestDispatcher()) {
        val link = link { listOf("Pixel Watch") }

        link.check()

        assertEquals(WatchState.Connected("Pixel Watch"), link.state.value)
    }

    @Test
    fun `check leaves a send's result alone`() = runTest(UnconfinedTestDispatcher()) {
        val link = link { listOf("Pixel Watch") }
        link.send(token)

        link.check()

        assertEquals(WatchState.Sent("Pixel Watch"), link.state.value)
    }

    @Test
    fun `sharing a switch without Wear OS tries once`() = runTest(UnconfinedTestDispatcher()) {
        var tries = 0
        val link = WatchLink(
            scope = backgroundScope,
            connectedWatches = noWear,
            pushToken = {},
            pushSettings = {},
            pushSignOut = {},
            pushSwitch = { _, _ ->
                tries++
                throw ApiException(Status(CommonStatusCodes.API_NOT_CONNECTED))
            },
        )

        link.shareSwitch(PreviewData.currentSwitch, token)
        link.shareSwitch(PreviewData.currentSwitch, token)

        assertEquals(1, tries)
        assertEquals(WatchState.Unsupported, link.state.value)
    }

    @Test
    fun `a failed send says why and can be retried`() = runTest(UnconfinedTestDispatcher()) {
        var fail = true
        val link = WatchLink(
            scope = backgroundScope,
            connectedWatches = { listOf("Pixel Watch") },
            pushToken = { if (fail) error("Data Layer unavailable") else pushed += it },
            pushSettings = {},
            pushSignOut = {},
        )

        link.send(token)
        assertEquals(WatchState.Failed("Data Layer unavailable"), link.state.value)

        fail = false
        link.send(token)
        assertEquals(WatchState.Sent("Pixel Watch"), link.state.value)
    }

    @Test
    fun `a watch lookup that fails isn't mistaken for no Wear OS`() = runTest(UnconfinedTestDispatcher()) {
        val link = link { error("Binder died") }

        link.check()

        assertEquals(WatchState.Failed("Binder died"), link.state.value)
    }

    @Test
    fun `settings go before the token, and their failure doesn't stop it`() = runTest(UnconfinedTestDispatcher()) {
        val order = mutableListOf<String>()
        val link = WatchLink(
            scope = backgroundScope,
            connectedWatches = { listOf("Pixel Watch") },
            pushToken = { order += "token" },
            pushSettings = {
                order += "settings"
                error("settings failed")
            },
            pushSignOut = {},
        )

        link.send(token)

        assertEquals(listOf("settings", "token"), order)
        assertEquals(WatchState.Sent("Pixel Watch"), link.state.value)
    }

    @Test
    fun `signing out tells the watch and starts over`() = runTest(UnconfinedTestDispatcher()) {
        var signedOut = 0
        val link = WatchLink(
            scope = backgroundScope,
            connectedWatches = { listOf("Pixel Watch") },
            pushToken = {},
            pushSettings = {},
            pushSignOut = { signedOut++ },
        )
        link.send(token)

        link.signOut()

        assertEquals(1, signedOut)
        assertEquals(WatchState.Checking, link.state.value)
    }

    @Test
    fun `a switch is shared with a watch, and a failure is left to the caller`() = runTest(UnconfinedTestDispatcher()) {
        val shared = mutableListOf<String>()
        var fail = false
        val link = WatchLink(
            scope = backgroundScope,
            connectedWatches = { listOf("Pixel Watch") },
            pushToken = {},
            pushSettings = {},
            pushSignOut = {},
            pushSwitch = { switch, _ -> if (fail) error("out of reach") else shared += switch.uuid },
        )

        link.shareSwitch(PreviewData.currentSwitch, token)
        assertEquals(listOf(PreviewData.currentSwitch.uuid), shared)

        fail = true
        val thrown = runCatching { link.shareSwitch(PreviewData.currentSwitch, token) }.exceptionOrNull()
        assertEquals("out of reach", thrown?.message)
    }
}
