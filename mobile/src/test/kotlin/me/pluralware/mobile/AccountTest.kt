package me.pluralware.mobile

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import me.pluralware.shared.api.PluralKitToken
import me.pluralware.shared.model.SystemInfo
import me.pluralware.shared.notify.Friend
import me.pluralware.shared.notify.InMemorySharingStore
import me.pluralware.shared.notify.RelaySettings
import me.pluralware.shared.notify.SharingConfig
import me.pluralware.shared.repository.InMemoryTokenStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountTest {

    private val system = SystemInfo("sys", "uuid-sys", "Sample", null)
    private val other = SystemInfo("oth", "uuid-other", "Other", null)
    private val relay = RelaySettings(url = "https://relay.example", adminSecret = "secret", signingToken = "t", enabled = true)
    private val sharing = SharingConfig(friends = listOf(Friend.Simple("f1", "Kit", "pw_kit")), relay = relay)

    private val tokens = InMemoryTokenStore(PluralKitToken("token"))
    private val sharingStore = InMemorySharingStore(sharing)
    private val owner = InMemorySharingOwner("uuid-sys")
    private val events = mutableListOf<String>()
    private val pushedSharing = mutableListOf<SharingConfig>()
    private var relayReachable = true

    private fun TestScope.account() = Account(
        scope = backgroundScope,
        tokenStore = tokens,
        sharingStore = sharingStore,
        owner = owner,
        clearRelay = {
            events += "relay"
            relayReachable
        },
        signOutWatch = { events += "watch" },
        pushSharingToWatch = { pushedSharing += it },
    )

    @Test
    fun `disconnecting forgets the token, the watch's token, sharing and the relay's copy`() =
        runTest(UnconfinedTestDispatcher()) {
            account().disconnect()

            assertNull(tokens.getToken())
            assertEquals(listOf("watch", "relay"), events)
            assertEquals(SharingConfig(), sharingStore.configFlow.value)
            assertNull(owner.get())
        }

    @Test
    fun `an unreachable relay is remembered for deleting later`() = runTest(UnconfinedTestDispatcher()) {
        relayReachable = false

        account().disconnect()

        assertEquals(listOf(relay), sharingStore.orphanedRelays.value)
    }

    @Test
    fun `a relay that throws still lets the sign-out finish`() = runTest(UnconfinedTestDispatcher()) {
        val account = Account(
            scope = backgroundScope,
            tokenStore = tokens,
            sharingStore = sharingStore,
            owner = owner,
            clearRelay = { error("network down") },
            signOutWatch = {},
            pushSharingToWatch = {},
        )

        account.disconnect()

        assertNull(tokens.getToken())
        assertEquals(SharingConfig(), sharingStore.configFlow.value)
        assertFalse(account.signingOut.value)
    }

    @Test
    fun `a watch that can't be reached doesn't keep the phone signed in`() = runTest(UnconfinedTestDispatcher()) {
        val account = Account(
            scope = backgroundScope,
            tokenStore = tokens,
            sharingStore = sharingStore,
            owner = owner,
            clearRelay = { true },
            signOutWatch = { error("no watch") },
            pushSharingToWatch = {},
        )

        account.disconnect()

        assertNull(tokens.getToken())
        assertEquals(SharingConfig(), sharingStore.configFlow.value)
    }

    @Test
    fun `a token PluralKit rejected signs out but keeps sharing`() = runTest(UnconfinedTestDispatcher()) {
        account().signOutRejected()

        assertNull(tokens.getToken())
        assertEquals(listOf("watch"), events)
        assertEquals(sharing, sharingStore.configFlow.value)
        assertEquals("uuid-sys", owner.get())
    }

    @Test
    fun `reconnecting the same system keeps sharing and gives the watch its copy back`() =
        runTest(UnconfinedTestDispatcher()) {
            val account = account()
            account.signOutRejected()

            account.connected(system)

            assertEquals(sharing, sharingStore.configFlow.value)
            assertEquals(listOf(sharing.forWatch()), pushedSharing)
        }

    @Test
    fun `connecting a different system drops the last one's sharing, on the watch too`() = runTest(UnconfinedTestDispatcher()) {
        val account = account()
        account.signOutRejected()

        account.connected(other)

        assertEquals(SharingConfig(), sharingStore.configFlow.value)
        assertTrue("relay" in events)
        assertEquals("uuid-other", owner.get())
        assertEquals(listOf(SharingConfig()), pushedSharing)
    }

    @Test
    fun `connecting during a disconnect waits for its cleanup`() = runTest(UnconfinedTestDispatcher()) {
        val relayAnswer = CompletableDeferred<Boolean>()
        val account = Account(
            scope = backgroundScope,
            tokenStore = tokens,
            sharingStore = sharingStore,
            owner = owner,
            clearRelay = { relayAnswer.await() },
            signOutWatch = {},
            pushSharingToWatch = { pushedSharing += it },
        )
        account.disconnect()

        launch { account.connected(system) }
        relayAnswer.complete(true)

        assertEquals("uuid-sys", owner.get())
        assertTrue(pushedSharing.none { it.isSharing })
    }

    @Test
    fun `sharing saved before owners were recorded is claimed for its token's system`() =
        runTest(UnconfinedTestDispatcher()) {
            owner.set(null)

            account().claimUnowned { token -> if (token.raw == "token") system else other }

            assertEquals("uuid-sys", owner.get())
            assertEquals(sharing, sharingStore.configFlow.value)
        }

    @Test
    fun `sharing whose system couldn't be checked isn't given to the next system to connect`() =
        runTest(UnconfinedTestDispatcher()) {
            owner.set(null)
            val account = account()
            account.claimUnowned { error("token rejected") }

            account.connected(other)

            assertEquals(SharingConfig(), sharingStore.configFlow.value)
            assertEquals("uuid-other", owner.get())
        }

    @Test
    fun `nothing to share sends nothing to the watch`() = runTest(UnconfinedTestDispatcher()) {
        sharingStore.clear()

        account().connected(system)

        assertTrue(pushedSharing.isEmpty())
    }
}
