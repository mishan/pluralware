package me.pluralware.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import java.time.Instant
import java.time.temporal.ChronoUnit
import me.pluralware.shared.api.PluralKitHttpException
import me.pluralware.shared.model.Member
import me.pluralware.shared.model.Switch
import me.pluralware.shared.model.SystemInfo
import me.pluralware.shared.preview.PreviewData
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.settings.InMemorySettingsStore
import me.pluralware.wear.ReadOnlyPluralKitClient
import me.pluralware.wear.ui.screens.FrontersScreen
import me.pluralware.wear.ui.screens.HistoryScreen
import me.pluralware.wear.ui.screens.MemberPickerScreen
import me.pluralware.wear.ui.theme.PluralWareTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The watch's screens, driven through a fake PluralKit on a small round watch. */
@RunWith(RobolectricTestRunner::class)
class WatchScreensTest {
    @get:Rule val compose = createComposeRule()

    private val m = PreviewData.members
    private val now = Instant.now()
    private val current = Switch("now", now.minus(45, ChronoUnit.MINUTES), listOf(m[0], m[3]))
    private val history = listOf(
        current,
        Switch("s2", now.minus(3, ChronoUnit.HOURS), listOf(m[1])),
        Switch("s3", now.minus(2, ChronoUnit.DAYS), listOf(m[2], m[4], m[5])),
        Switch("s4", now.minus(5, ChronoUnit.DAYS), listOf(m[5])),
    )

    private val registered = mutableListOf<List<String>>()
    private var unauthorized = false
    private val events = mutableListOf<String>()

    private val client = object : ReadOnlyPluralKitClient() {
        private fun check() {
            if (unauthorized) throw PluralKitHttpException(401, "Unauthorized")
        }
        override suspend fun getOwnSystem(): SystemInfo = PreviewData.system
        override suspend fun getOwnMembers(): List<Member> = m.also { check() }
        override suspend fun getCurrentFronters(): Switch = current.also { check() }
        override suspend fun getRecentSwitches(limit: Int): List<Switch> = history.also { check() }
        override suspend fun registerSwitch(memberUuids: List<String>): Switch {
            check()
            registered += memberUuids
            return Switch("new", now, m.filter { it.uuid in memberUuids })
        }
    }

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { PluralWareTheme { content() } }
        compose.waitForIdle()
    }

    private fun picker() = show {
        MemberPickerScreen(
            repository = PluralKitRepository(client),
            onDone = { events += "done" },
            onSignOut = { events += "signout" },
        )
    }

    private fun scrollTo(text: String) = compose.onNode(hasScrollAction()).performScrollToNode(hasText(text))

    @Test
    fun `the picker puts deselect all above the members`() {
        picker()

        val deselect = compose.onNodeWithText("Deselect all").getUnclippedBoundsInRoot()
        val first = compose.onNodeWithText(m[0].name).getUnclippedBoundsInRoot()
        assertTrue(deselect.bottom <= first.top)
    }

    @Test
    fun `the picker puts confirm below the members`() {
        picker()

        scrollTo("Confirm switch")
        val last = compose.onNodeWithText(m.last().name).getUnclippedBoundsInRoot()
        val confirm = compose.onNodeWithText("Confirm switch").getUnclippedBoundsInRoot()
        assertTrue(confirm.top >= last.bottom)
    }

    @Test
    fun `the picker opens with the current fronters numbered in front order`() {
        picker()

        compose.onNodeWithText("1").assertIsDisplayed()
    }

    @Test
    fun `picking members and confirming registers them in tap order`() {
        picker()

        compose.onNodeWithText("Deselect all").performClick()
        scrollTo(m[2].name)
        compose.onNodeWithText(m[2].name).performClick()
        scrollTo(m[1].name)
        compose.onNodeWithText(m[1].name).performClick()
        scrollTo("Confirm switch")
        compose.onNodeWithText("Confirm switch").performClick()
        compose.waitForIdle()

        assertEquals(listOf(listOf(m[2].uuid, m[1].uuid)), registered)
    }

    @Test
    fun `with nobody picked, confirming switches out`() {
        picker()

        compose.onNodeWithText("Deselect all").performClick()
        scrollTo("Switch out")
        compose.onNodeWithText("Switch out").performClick()
        compose.waitForIdle()

        assertEquals(listOf(emptyList<String>()), registered)
    }

    @Test
    fun `history leaves out the current switch`() {
        show { HistoryScreen(repository = PluralKitRepository(client), onSignOut = {}) }

        compose.onNodeWithText("Bea").assertIsDisplayed()
        compose.onNodeWithText("45m ago").assertDoesNotExist()
    }

    @Test
    fun `history rows can't be tapped, so nothing switches by accident`() {
        show { HistoryScreen(repository = PluralKitRepository(client), onSignOut = {}) }

        compose.onNodeWithText("Bea").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithText("3h ago").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithText("Bea").performClick()
        compose.waitForIdle()
        assertTrue(registered.isEmpty())
    }

    @Test
    fun `home shows the fronters and opens the picker and history`() {
        show {
            FrontersScreen(
                repository = PluralKitRepository(client),
                settingsStore = InMemorySettingsStore(),
                onChangeFronter = { events += "change" },
                onOpenHistory = { events += "history" },
                onSignOut = { events += "signout" },
            )
        }

        compose.onNodeWithText(m[0].name).assertIsDisplayed()
        scrollTo("Change fronter")
        compose.onNodeWithText("Change fronter").performClick()
        scrollTo("Recent switches")
        compose.onNodeWithText("Recent switches").performClick()
        assertEquals(listOf("change", "history"), events)
    }

    @Test
    fun `a rejected token offers sign-out instead of retry`() {
        unauthorized = true
        show {
            FrontersScreen(
                repository = PluralKitRepository(client),
                settingsStore = InMemorySettingsStore(),
                onChangeFronter = {},
                onOpenHistory = {},
                onSignOut = { events += "signout" },
            )
        }

        compose.onNodeWithText("Sign out").performClick()
        assertEquals(listOf("signout"), events)
    }
}
