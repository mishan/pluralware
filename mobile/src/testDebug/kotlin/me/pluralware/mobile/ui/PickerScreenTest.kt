package me.pluralware.mobile.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import me.pluralware.mobile.ui.screens.PickerContent
import me.pluralware.mobile.ui.theme.PluralWareTheme
import me.pluralware.shared.model.Member
import me.pluralware.shared.preview.PreviewData
import me.pluralware.shared.ui.state.PickerState
import me.pluralware.shared.ui.state.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class PickerScreenTest {
    @get:Rule val compose = createComposeRule()

    private val m = PreviewData.members
    private val events = mutableListOf<String>()

    private fun show(state: PickerState, editing: Boolean = false) {
        compose.setContent {
            PluralWareTheme {
                PickerContent(
                    state = state,
                    onBack = { events += "back" },
                    onToggle = { events += "toggle:$it" },
                    onDeselectAll = { events += "deselect" },
                    onConfirm = { events += "confirm" },
                    onRetry = { events += "retry" },
                    onSignOut = { events += "signout" },
                    editing = editing,
                )
            }
        }
    }

    private fun selecting(vararg uuids: String) =
        PickerState(members = UiState.Content(m), selectedUuids = uuids.toList(), seeded = true)

    @Test
    fun `deselect all sits above the members, and confirm below them`() {
        show(selecting(m[0].uuid))

        val deselect = compose.onNodeWithText("Deselect all").getUnclippedBoundsInRoot()
        val first = compose.onNodeWithText(m[0].name).getUnclippedBoundsInRoot()
        val confirm = compose.onNodeWithText("Confirm switch").getUnclippedBoundsInRoot()
        assertTrue(deselect.bottom <= first.top)
        assertTrue(confirm.top >= first.bottom)
    }

    @Test
    fun `tapping a member toggles them`() {
        show(selecting())

        compose.onNodeWithText(m[2].name).performClick()

        assertEquals(listOf("toggle:${m[2].uuid}"), events)
    }

    @Test
    fun `selected members show their place in the front order`() {
        show(selecting(m[3].uuid, m[0].uuid))

        compose.onNodeWithText("1").assertIsDisplayed()
        compose.onNodeWithText("2").assertIsDisplayed()
        compose.onNodeWithText("2 selected · first is primary").assertIsDisplayed()
    }

    @Test
    fun `deselect all works only with someone selected`() {
        show(selecting())
        compose.onNodeWithText("Deselect all").assertIsNotEnabled()
    }

    @Test
    fun `deselect all deselects`() {
        show(selecting(m[0].uuid))

        compose.onNodeWithText("Deselect all").assertIsEnabled().performClick()

        assertEquals(listOf("deselect"), events)
    }

    @Test
    fun `with nobody selected, confirm switches out`() {
        show(selecting())

        compose.onNodeWithText("Switch out").performClick()

        assertEquals(listOf("confirm"), events)
    }

    @Test
    fun `while saving, confirm can't be pressed again`() {
        show(selecting(m[0].uuid).copy(submitting = true))

        compose.onNodeWithText("Saving…").assertIsNotEnabled()
    }

    @Test
    fun `after a switch, the button says so`() {
        show(selecting(m[0].uuid).copy(confirmed = true))

        compose.onNodeWithText("Switched ✓").assertIsNotEnabled()
    }

    @Test
    fun `a long member list can be searched`() {
        val many = m + (1..6).map { Member("x$it", "uuid-x$it", "Extra $it", null, null, null, null) }
        show(PickerState(members = UiState.Content(many), seeded = true))

        compose.onNodeWithText("Search members").performTextInput("ext")

        compose.onNodeWithText("Extra 1").assertIsDisplayed()
        compose.onNodeWithText(m[0].name).assertDoesNotExist()
    }

    @Test
    fun `a search that matches nobody says so`() {
        val many = m + (1..6).map { Member("x$it", "uuid-x$it", "Extra $it", null, null, null, null) }
        show(PickerState(members = UiState.Content(many), seeded = true))

        compose.onNodeWithText("Search members").performTextInput("zz")

        compose.onNodeWithText("No members match “zz”.").assertIsDisplayed()
    }

    @Test
    fun `a short member list has no search`() {
        show(selecting())
        compose.onNodeWithText("Search members").assertDoesNotExist()
    }

    @Test
    fun `a rejected token offers sign-out`() {
        show(PickerState(members = UiState.Error("No longer accepted.", unauthorized = true)))

        compose.onNodeWithText("Sign out").performClick()

        assertEquals(listOf("signout"), events)
        compose.onNodeWithText("Retry").assertDoesNotExist()
    }

    @Test
    fun `other errors offer a retry`() {
        show(PickerState(members = UiState.Error("Couldn't load members")))

        compose.onNodeWithText("Retry").performClick()

        assertEquals(listOf("retry"), events)
    }

    @Test
    fun `changing an earlier switch's members says so, and saves rather than switches`() {
        val cases = listOf(
            selecting(m[0].uuid) to listOf("Who was fronting?", "Save members"),
            selecting() to listOf("Who was fronting?", "Save as switched out"),
            selecting(m[0].uuid).copy(confirmed = true) to listOf("Saved ✓"),
        )
        var shown by mutableStateOf(cases[0].first)
        compose.setContent { PluralWareTheme { PickerContent(shown, {}, {}, {}, {}, {}, {}, editing = true) } }
        for ((state, texts) in cases) {
            shown = state
            for (text in texts) compose.onNodeWithText(text).assertIsDisplayed()
            compose.onNodeWithText("Confirm switch").assertDoesNotExist()
        }
    }

    @Test
    fun `back goes back`() {
        show(selecting())

        compose.onNodeWithContentDescription("Back").performClick()

        assertEquals(listOf("back"), events)
    }
}
