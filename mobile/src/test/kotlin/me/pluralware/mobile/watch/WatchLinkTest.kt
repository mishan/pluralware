package me.pluralware.mobile.watch

import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import com.google.android.gms.common.api.Status
import kotlinx.coroutines.CompletableDeferred
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

    private fun TestScope.link(watches: suspend () -> List<String>) = WatchLink(
        scope = backgroundScope,
        connectedWatches = watches,
        pushToken = {
            pushed += it
            writes += "token"
        },
        pushSettings = {},
        pushSignOut = { writes += "sign-out" },
    )

    private val noWear: suspend () -> List<String> = {
        throw ApiException(Status(CommonStatusCodes.API_NOT_CONNECTED))
    }

    @Test
    fun `a phone without Wear OS sends nothing and says so quietly`() = runTest(UnconfinedTestDispatcher()) {
        val link = link(noWear)

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
}
