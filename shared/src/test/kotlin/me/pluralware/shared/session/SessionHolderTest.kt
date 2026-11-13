package me.pluralware.shared.session

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.pluralware.shared.FlakyClient
import me.pluralware.shared.api.PluralKitToken
import me.pluralware.shared.mock.MockPluralKitClient
import me.pluralware.shared.model.Switch
import me.pluralware.shared.repository.PluralKitRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionHolderTest {

    class ScreenViewModel : ViewModel() {
        var cleared = false
        override fun onCleared() { cleared = true }
    }

    private fun repo() = PluralKitRepository(MockPluralKitClient(artificialLatencyMillis = 0))
    private fun screenIn(session: Session) =
        ViewModelProvider(session)[ScreenViewModel::class.java]

    @Test
    fun `the same token keeps its session, repository and screens`() {
        val holder = SessionHolder()
        val first = holder.sessionFor(PluralKitToken("a"), ::repo)
        val screen = screenIn(first)

        val again = holder.sessionFor(PluralKitToken("a"), ::repo)

        assertSame(first, again)
        assertSame(first.repository, again.repository)
        assertSame(screen, screenIn(again))
        assertFalse(screen.cleared)
    }

    @Test
    fun `a new token releases the old screens and starts fresh`() {
        val holder = SessionHolder()
        val old = holder.sessionFor(PluralKitToken("a"), ::repo)
        val oldScreen = screenIn(old)

        val new = holder.sessionFor(PluralKitToken("b"), ::repo)

        assertTrue(oldScreen.cleared)
        assertNotSame(old.repository, new.repository)
        assertNotSame(oldScreen, screenIn(new))
    }

    /** A session whose repository is mid-write: the write waits on the returned hold. */
    private fun TestScope.sessionMidWrite(holder: SessionHolder, corrected: MutableList<Switch?>): CompletableDeferred<Unit> {
        val client = FlakyClient()
        val session = holder.sessionFor(PluralKitToken("a")) {
            PluralKitRepository(client, onFrontCorrected = { corrected += it })
        }
        val hold = CompletableDeferred<Unit>()
        client.holdNextWrite = hold
        launch {
            val latest = client.pk.getRecentSwitches(1).single()
            val other = client.pk.getOwnMembers().first { m -> latest.members.none { it.uuid == m.uuid } }
            session.repository.setSwitchMembers(latest.uuid, listOf(other.uuid))
        }
        runCurrent()
        return hold
    }

    @Test
    fun `signing out stops the old session's writes, so friends hear nothing more from it`() = runTest {
        val holder = SessionHolder()
        val corrected = mutableListOf<Switch?>()
        val hold = sessionMidWrite(holder, corrected)

        holder.end()
        hold.complete(Unit)
        advanceUntilIdle()

        assertEquals(emptyList<Switch?>(), corrected)
    }

    @Test
    fun `leaving the app lets a write under way finish`() = runTest {
        val activity = ViewModelStore()
        val holder = ViewModelProvider(activity, ViewModelProvider.NewInstanceFactory())[SessionHolder::class.java]
        val corrected = mutableListOf<Switch?>()
        val hold = sessionMidWrite(holder, corrected)

        activity.clear()
        hold.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, corrected.size)
    }

    @Test
    fun `signing out releases the screens`() {
        val holder = SessionHolder()
        val screen = screenIn(holder.sessionFor(PluralKitToken("a"), ::repo))

        holder.end()

        assertTrue(screen.cleared)
    }
}
