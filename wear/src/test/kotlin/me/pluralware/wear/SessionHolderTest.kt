package me.pluralware.wear

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import me.pluralware.shared.api.PluralKitToken
import me.pluralware.shared.mock.MockPluralKitClient
import me.pluralware.shared.repository.PluralKitRepository
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

    @Test
    fun `signing out releases the screens`() {
        val holder = SessionHolder()
        val screen = screenIn(holder.sessionFor(PluralKitToken("a"), ::repo))

        holder.end()

        assertTrue(screen.cleared)
    }
}
