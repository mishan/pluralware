package me.pluralware.mobile.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import me.pluralware.mobile.ui.screens.FrontersContent
import me.pluralware.mobile.ui.screens.HistoryContent
import me.pluralware.mobile.ui.screens.SwitchWindow
import me.pluralware.mobile.ui.screens.SwitchTimeDialog
import me.pluralware.mobile.ui.screens.SwitchFixViewModel
import me.pluralware.mobile.ui.screens.FixNotice
import me.pluralware.mobile.ui.theme.PluralWareTheme
import me.pluralware.mobile.watch.WatchState
import me.pluralware.shared.model.Switch
import me.pluralware.shared.preview.PreviewData
import me.pluralware.shared.settings.RefreshInterval
import me.pluralware.shared.ui.state.FronterStreak
import me.pluralware.shared.ui.state.FrontersState
import me.pluralware.shared.ui.state.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ScreensTest {
    @get:Rule val compose = createComposeRule()

    private val m = PreviewData.members
    private val now = Instant.now()
    private val current = Switch("now", now.minus(45, ChronoUnit.MINUTES), listOf(m[0], m[3]))
    private val events = mutableListOf<String>()

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { PluralWareTheme { content() } }
    }

    // Home

    private fun home(state: UiState<FrontersState>) = show {
        FrontersContent(
            state = state,
            onRefresh = { events += "refresh" },
            onRetry = { events += "retry" },
            onChangeFronters = { events += "change" },
            onOpenHistory = { events += "history" },
            onOpenMembers = { events += "members" },
            onOpenSettings = { events += "settings" },
            onSignOut = { events += "signout" },
        )
    }

    @Test
    fun `home shows each fronter with how long they've fronted`() {
        home(
            UiState.Content(
                FrontersState(
                    switch = current,
                    streaks = mapOf(
                        m[0].uuid to FronterStreak(current.timestamp),
                        m[3].uuid to FronterStreak(now.minus(3, ChronoUnit.DAYS), truncated = true),
                    ),
                ),
            ),
        )

        compose.onNodeWithText("Fronting now").assertIsDisplayed()
        compose.onNodeWithText("Since 45m ago").assertIsDisplayed()
        compose.onNodeWithText("Alex").assertIsDisplayed()
        compose.onNodeWithText("she/her · fronting for 45m").assertIsDisplayed()
        compose.onNodeWithText("she/they · fronting for >3d").assertIsDisplayed()
    }

    @Test
    fun `home's buttons go where they say`() {
        home(UiState.Content(FrontersState(current)))

        compose.onNodeWithText("Change fronters").performClick()
        compose.onNodeWithText("Recent switches").performClick()
        compose.onNodeWithText("Members").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Settings").performClick()

        assertEquals(listOf("change", "history", "members", "settings"), events)
    }

    @Test
    fun `home after a switch-out says nobody's fronting and offers to switch in`() {
        home(UiState.Content(FrontersState(Switch("out", now.minus(2, ChronoUnit.HOURS), emptyList()))))

        compose.onNodeWithText("Nobody's fronting").assertIsDisplayed()
        compose.onNodeWithText("Switched out 2h ago").assertIsDisplayed()
        compose.onNodeWithText("Switch in").performClick()
        assertEquals(listOf("change"), events)
    }

    @Test
    fun `home for a system that never switched invites a first switch`() {
        home(UiState.Content(FrontersState(null)))

        compose.onNodeWithText("No switches yet").assertIsDisplayed()
        compose.onNodeWithText("Log a switch").performClick()
        compose.onNodeWithText("Members").performClick()
        assertEquals(listOf("change", "members"), events)
    }

    @Test
    fun `a rejected token offers sign-out and says sharing is kept`() {
        home(UiState.Error("PluralKit no longer accepts this token.", unauthorized = true))

        compose.onNodeWithText("Signed out by PluralKit").assertIsDisplayed()
        compose.onNodeWithText(
            "PluralKit no longer accepts this token. Who you share switches with is kept for when you're back.",
        ).assertIsDisplayed()
        compose.onNodeWithText("Sign out").performClick()
        assertEquals(listOf("signout"), events)
    }

    @Test
    fun `other errors offer a retry`() {
        home(UiState.Error("Couldn't load fronters"))

        compose.onNodeWithText("Retry").performClick()
        assertEquals(listOf("retry"), events)
    }

    // History

    private val history = listOf(
        current,
        Switch("s2", now.minus(3, ChronoUnit.HOURS), listOf(m[1])),
        Switch("s3", now.minus(26, ChronoUnit.HOURS), emptyList()),
        Switch("s4", now.minus(5, ChronoUnit.DAYS), listOf(m[2], m[4])),
    )

    private fun history(state: UiState<List<Switch>>, fix: SwitchFixViewModel.State = SwitchFixViewModel.State()) = show {
        HistoryContent(
            state = state,
            onBack = { events += "back" },
            onRetry = { events += "retry" },
            onSignOut = { events += "signout" },
            fix = fix,
            onMove = { uuid, to -> events += "move:$uuid:$to" },
            onDelete = { events += "delete:$it" },
            onNoticeShown = { events += "shown" },
            zone = ZoneOffset.UTC,
        )
    }

    @Test
    fun `history lists every switch, the current one marked`() {
        history(UiState.Content(history))

        compose.onNodeWithText("Now").assertIsDisplayed()
        compose.onNodeWithText("Alex").assertIsDisplayed()
        compose.onNodeWithText("Bea").assertIsDisplayed()
        compose.onNodeWithText("Switched out").assertIsDisplayed()
        compose.onNodeWithText("Cam").assertIsDisplayed()
        compose.onNodeWithText("5d ago", substring = true).assertIsDisplayed()
    }

    @Test
    fun `history shows ten switches, keeping the one before them only to know its time`() {
        val eleven = (0 until 11).map { Switch("s$it", now.minus(it + 1L, ChronoUnit.HOURS), listOf(m[it % m.size])) }
        history(UiState.Content(eleven))

        val list = compose.onNode(hasScrollAction())
        list.performScrollToNode(hasText("10h ago", substring = true))
        compose.onNodeWithText("10h ago", substring = true).assertIsDisplayed()
        list.performScrollToIndex(9)
        compose.onNodeWithText("11h ago", substring = true).assertDoesNotExist()
    }

    @Test
    fun `tapping a switch offers to change its time or delete it, and changes nothing yet`() {
        history(UiState.Content(history))

        compose.onNodeWithText("Bea").performClick()

        compose.onNodeWithText("Change time").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertIsDisplayed()
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun `deleting asks first, saying what the front becomes`() {
        history(UiState.Content(history))
        compose.onNodeWithText("Alex").performClick()
        compose.onNodeWithText("Delete").performClick()

        compose.onNodeWithText("Front goes back to Bea", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(emptyList<String>(), events)

        compose.onNodeWithText("Alex").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.onNode(hasText("Delete") and hasAnyAncestor(isDialog())).performClick()
        assertEquals(listOf("delete:now"), events)
    }

    @Test
    fun `a switch can't be tapped while a fix is under way`() {
        history(UiState.Content(history), SwitchFixViewModel.State(busy = true))

        compose.onNodeWithText("Bea").performClick()

        compose.onNodeWithText("Change time").assertDoesNotExist()
    }

    @Test
    fun `how a fix went is shown once, with sign-out for a rejected token`() {
        history(
            UiState.Content(history),
            SwitchFixViewModel.State(notice = FixNotice.Failed("PluralKit no longer accepts this token.", unauthorized = true)),
        )

        compose.onNodeWithText("PluralKit no longer accepts this token.").assertIsDisplayed()
        compose.onNodeWithText("Sign out").performClick()
        compose.waitForIdle()
        assertEquals(listOf("signout", "shown"), events)
    }

    @Test
    fun `a system with no switches says so`() {
        history(UiState.Content(emptyList()))

        compose.onNodeWithText("No switches yet").assertIsDisplayed()
    }

    // The time dialog

    private val after = Instant.parse("2026-01-15T12:00:00Z")
    private val at = Instant.parse("2026-01-15T14:40:00Z")
    private val before = Instant.parse("2026-01-15T16:00:00Z")

    private fun timeDialog(
        window: SwitchWindow,
        initial: Instant = at,
        zone: java.time.ZoneId = ZoneOffset.UTC,
        now: Instant = before.plusSeconds(3600),
    ) = show {
        SwitchTimeDialog(
            initial = initial,
            window = window,
            zone = zone,
            clock = java.time.Clock.fixed(now, zone),
            onConfirm = { events += "move:$it" },
            onDismiss = { events += "dismiss" },
        )
    }

    @Test
    fun `a switch's time changes to the one picked`() {
        timeDialog(SwitchWindow(after, before))
        compose.onNodeWithText("Change").assertIsNotEnabled()

        compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("10")
        compose.onNodeWithText("Change").performClick()

        assertEquals(listOf("move:2026-01-15T14:10:00Z"), events)
    }

    @Test
    fun `the time picked is read in the phone's zone, on the day picked`() {
        // 9 PM on Jan 14 in UTC-8.
        timeDialog(SwitchWindow(null, before), initial = Instant.parse("2026-01-15T05:00:00Z"), zone = ZoneOffset.ofHours(-8))

        compose.onNodeWithText("Jan 14, 2026").performClick()
        compose.onNodeWithText("January 13, 2026", substring = true).performClick()
        compose.onNodeWithText("OK").performClick()
        compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("10")
        compose.onNodeWithText("Change").performClick()

        assertEquals(listOf("move:2026-01-14T05:10:00Z"), events)
    }

    @Test
    fun `a switch in the hour that repeats when clocks go back keeps its hour`() {
        val zone = java.time.ZoneId.of("America/Los_Angeles")
        // 1:30 AM PST, the second 1:30 that night.
        timeDialog(
            SwitchWindow(null, null),
            initial = Instant.parse("2026-11-01T09:30:00Z"),
            zone = zone,
            now = Instant.parse("2026-11-02T00:00:00Z"),
        )
        compose.onNodeWithText("Change").assertIsNotEnabled()

        compose.onAllNodes(hasSetTextAction())[1].performTextReplacement("10")
        compose.onNodeWithText("Change").performClick()

        assertEquals(listOf("move:2026-11-01T09:10:00Z"), events)
    }

    @Test
    fun `a time skipped when clocks go forward can't be picked`() {
        // 1:10 AM PST; that night there is no 2:00 to 2:59.
        timeDialog(
            SwitchWindow(null, null),
            initial = Instant.parse("2026-03-08T09:10:00Z"),
            zone = java.time.ZoneId.of("America/Los_Angeles"),
            now = Instant.parse("2026-03-09T00:00:00Z"),
        )

        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("2")

        compose.onNodeWithText("That time doesn't exist", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Change").assertIsNotEnabled()
    }

    @Test
    fun `a switch in the same minute as the one before it opens without an error`() {
        timeDialog(SwitchWindow(at.plusSeconds(5), before), initial = at.plusSeconds(23))

        compose.onNodeWithText("Pick a time after", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Change").assertIsNotEnabled()
    }

    @Test
    fun `a time outside the switches either side can't be picked, and says where it may go`() {
        timeDialog(SwitchWindow(after, before))

        compose.onAllNodes(hasSetTextAction())[0].performTextReplacement("11")

        compose.onNodeWithText("Pick a time after", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Change").assertIsNotEnabled()
    }

    @Test
    fun `history back goes back`() {
        history(UiState.Content(history))

        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(listOf("back"), events)
    }

    // Settings

    private fun settings(watch: WatchState, signingOut: Boolean = false) = show {
        SettingsContent(
            system = PreviewData.system,
            refreshInterval = RefreshInterval.MIN_1,
            watch = watch,
            signingOut = signingOut,
            onBack = { events += "back" },
            onDisconnect = { events += "disconnect" },
            onSendToWatch = { events += "send" },
            onSelectInterval = { events += "interval:$it" },
            onOpenSharing = { events += "sharing" },
            onOpenFollowing = { events += "following" },
        )
    }

    @Test
    fun `settings names the account`() {
        settings(WatchState.Unsupported)

        compose.onNodeWithText("Connected as Sample System").assertIsDisplayed()
    }

    @Test
    fun `a phone without Wear OS shows nothing about watches`() {
        settings(WatchState.Unsupported)

        compose.onNodeWithText("Watch").assertDoesNotExist()
        compose.onNodeWithText("Send to watch").assertDoesNotExist()
    }

    @Test
    fun `the watch card says where the watch stands, and its button sends`() {
        var watch by mutableStateOf<WatchState>(WatchState.Sending)
        show {
            SettingsContent(
                system = PreviewData.system,
                refreshInterval = RefreshInterval.MIN_1,
                watch = watch,
                signingOut = false,
                onBack = {}, onDisconnect = {}, onSendToWatch = { events += "send" }, onSelectInterval = {},
                onOpenSharing = {}, onOpenFollowing = {},
            )
        }
        listOf(
            WatchCard(WatchState.NotConnected(), "No watch is connected", "Send to watch"),
            WatchCard(WatchState.Connected("Pixel Watch"), "Pixel Watch is connected.", "Send to watch"),
            WatchCard(WatchState.Sent("Pixel Watch"), "Sent to Pixel Watch.", "Resend"),
            WatchCard(WatchState.Failed("Couldn't reach your watch."), "Couldn't reach your watch.", "Try again"),
            WatchCard(WatchState.Sending, "Sending to your watch…", button = null),
        ).forEach { (state, message, button) ->
            watch = state
            events.clear()
            compose.onNodeWithText(message, substring = true).assertIsDisplayed()
            if (button == null) {
                listOf("Send to watch", "Send again", "Resend", "Try again").forEach {
                    compose.onNodeWithText(it).assertDoesNotExist()
                }
            } else {
                compose.onNodeWithText(button).performClick()
                assertEquals("$state", listOf("send"), events)
            }
        }
    }

    private data class WatchCard(val state: WatchState, val message: String, val button: String?)

    @Test
    fun `disconnecting asks first, and cancel keeps you connected`() {
        settings(WatchState.Unsupported)

        compose.onNodeWithText("Disconnect").performClick()
        compose.onNodeWithText("Disconnect?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()

        assertTrue(events.isEmpty())
        compose.onNodeWithText("Disconnect?").assertDoesNotExist()
    }

    @Test
    fun `confirming disconnects`() {
        settings(WatchState.Unsupported)

        compose.onNodeWithText("Disconnect").performClick()
        compose.onNode(hasText("Disconnect") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()

        assertEquals(listOf("disconnect"), events)
    }

    @Test
    fun `while disconnecting, the button waits`() {
        settings(WatchState.Unsupported, signingOut = true)

        compose.onNodeWithText("Disconnecting…").assertIsDisplayed()
        compose.onNode(hasText("Disconnecting…") and hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun `the refresh interval shows the choice and changes it`() {
        settings(WatchState.Unsupported)

        compose.onNodeWithText("Every minute").assertIsSelected()
        compose.onNodeWithText("Every 5 minutes").performScrollTo().performClick()

        assertEquals(listOf("interval:MIN_5"), events)
    }

    @Test
    fun `sharing and following open from settings`() {
        settings(WatchState.Unsupported)

        compose.onNodeWithText("Share with friends").performScrollTo().performClick()
        compose.onNodeWithText("Following").performScrollTo().performClick()

        assertEquals(listOf("sharing", "following"), events)
    }

    // Connect

    private fun connect(state: ConnectState) = show {
        ConnectContent(
            state = state,
            onInputChange = { events += "input:$it" },
            onConnect = { events += "connect" },
            onOpenFollowing = { events += "following" },
        )
    }

    @Test
    fun `connect waits for a token`() {
        connect(ConnectState())

        compose.onNodeWithText("Connect").assertIsNotEnabled()
    }

    @Test
    fun `connect with a token connects`() {
        connect(ConnectState(input = "token"))

        compose.onNodeWithText("Connect").assertIsEnabled().performClick()
        assertEquals(listOf("connect"), events)
    }

    @Test
    fun `while checking, connect can't be pressed again`() {
        connect(ConnectState(input = "token", validating = true))

        compose.onNodeWithText("Connecting…").assertIsDisplayed()
        compose.onNode(hasText("Connecting…") and hasClickAction()).assertIsNotEnabled()
    }

    @Test
    fun `a rejected token is explained`() {
        connect(ConnectState(input = "bad", error = "That token wasn't accepted by PluralKit."))

        compose.onNodeWithText("That token wasn't accepted by PluralKit.").assertIsDisplayed()
    }

    @Test
    fun `connecting doesn't depend on a watch, and following needs no account`() {
        connect(ConnectState())

        compose.onNodeWithText("Connect & send to watch").assertDoesNotExist()
        compose.onNodeWithText("Following").performScrollTo().performClick()
        assertEquals(listOf("following"), events)
    }
}
