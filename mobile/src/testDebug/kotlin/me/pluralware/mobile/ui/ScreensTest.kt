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
import androidx.compose.ui.test.hasText
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
        compose.onNodeWithContentDescription("Settings").performClick()

        assertEquals(listOf("change", "history", "settings"), events)
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
        assertEquals(listOf("change"), events)
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

    private fun history(state: UiState<List<Switch>>) = show {
        HistoryContent(
            state = state,
            onBack = { events += "back" },
            onRetry = { events += "retry" },
            onSignOut = { events += "signout" },
            zone = ZoneOffset.UTC,
        )
    }

    @Test
    fun `history leaves out the current switch and lists the rest`() {
        history(UiState.Content(history))

        compose.onNodeWithText("Alex").assertDoesNotExist()
        compose.onNodeWithText("Bea").assertIsDisplayed()
        compose.onNodeWithText("Switched out").assertIsDisplayed()
        compose.onNodeWithText("Cam").assertIsDisplayed()
        compose.onNodeWithText("5d ago", substring = true).assertIsDisplayed()
    }

    @Test
    fun `history rows can't be tapped, so nothing switches by accident`() {
        history(UiState.Content(history))

        compose.onNodeWithText("Bea").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNodeWithText("5d ago", substring = true)
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
    }

    @Test
    fun `history with only the current switch says there's nothing earlier`() {
        history(UiState.Content(listOf(current)))

        compose.onNodeWithText("No earlier switches").assertIsDisplayed()
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
