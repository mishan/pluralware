package org.foggyden.pluralware.wear.ui

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CompletableDeferred
import org.foggyden.pluralware.wear.WaitingForPairingScreen
import org.foggyden.pluralware.wear.ui.theme.PluralWareTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PairingScreenTest {
    @get:Rule val compose = createComposeRule()

    private var opened = 0

    private fun tapOpenOnPhone(reached: Boolean) {
        compose.setContent { PluralWareTheme { WaitingForPairingScreen(openOnPhone = { opened++; reached }) } }
        compose.onNodeWithText("Open on phone").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `Open on phone opens the phone app and says to look there`() {
        tapOpenOnPhone(reached = true)

        compose.onNodeWithText("Check your phone", substring = true).assertExists()
        compose.onNodeWithText("Open on phone").assertDoesNotExist()
    }

    @Test
    fun `if the phone can't be reached, it says so and the button stays`() {
        tapOpenOnPhone(reached = false)

        compose.onNodeWithText("Couldn't reach your phone", substring = true).assertExists()
        compose.onNodeWithText("Open on phone").assertExists()
    }

    @Test
    fun `a second tap while the first is still trying does nothing`() {
        val answer = CompletableDeferred<Boolean>()
        compose.setContent { PluralWareTheme { WaitingForPairingScreen(openOnPhone = { opened++; answer.await() }) } }

        compose.onNodeWithText("Open on phone").performClick()
        compose.onNodeWithText("Open on phone").performClick()
        answer.complete(false)

        assertEquals(1, opened)
        compose.onNodeWithText("Open on phone").assertIsEnabled()
    }
}
