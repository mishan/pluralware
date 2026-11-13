package me.pluralware.mobile.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.systemBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import java.time.Clock
import java.time.ZoneOffset
import me.pluralware.mobile.ui.LocalClock
import me.pluralware.mobile.ui.screens.FrontersContent
import me.pluralware.mobile.ui.screens.HistoryContent
import me.pluralware.mobile.ui.screens.PickerContent
import me.pluralware.mobile.ui.theme.PluralWareTheme
import me.pluralware.shared.preview.PreviewData
import me.pluralware.shared.ui.state.FronterStreak
import me.pluralware.shared.ui.state.FrontersState
import me.pluralware.shared.ui.state.PickerState
import me.pluralware.shared.ui.state.UiState
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The phone screenshots the README shows, rendered from [PreviewData] with
 * the clock stopped at [PreviewData.anchor], so they come out the same
 * every time. Skipped unless asked for: tools/screenshots.sh runs them.
 */
@RunWith(RobolectricTestRunner::class)
// Shorter than the UI tests' phone: the README shows these small.
@Config(qualifiers = "w411dp-h760dp-xhdpi")
class ReadmeScreenshots {
    @get:Rule val compose = createComposeRule()

    private val out: String? = System.getProperty("pluralware.screenshots")
    private val clock = Clock.fixed(PreviewData.anchor, ZoneOffset.UTC)

    @Before fun onlyWhenAsked() = assumeTrue("screenshots not requested", out != null)

    private fun shot(name: String, content: @Composable () -> Unit) {
        compose.setContent {
            // A device draws the status bar over the inset; a picture of the
            // app alone has none to leave room for.
            Box(Modifier.consumeWindowInsets(WindowInsets.systemBars)) {
                CompositionLocalProvider(LocalClock provides clock) {
                    PluralWareTheme { content() }
                }
            }
        }
        compose.onRoot().captureRoboImage("$out/$name.png")
    }

    private val m = PreviewData.members
    private val current = PreviewData.currentSwitch

    // The streaks the app works out from PreviewData's history: Alex joined
    // two hours ago, Dani was already fronting with Bea five hours ago.
    private val home = UiState.Content(
        FrontersState(
            switch = current,
            streaks = mapOf(
                m[0].uuid to FronterStreak(current.timestamp),
                m[3].uuid to FronterStreak(PreviewData.historyRecent[1].timestamp),
            ),
        ),
    )

    @Test fun home() = shot("phone-home") { FrontersContent(home, {}, {}, {}, {}, {}, {}, {}) }

    @Test fun picker() = shot("phone-picker") {
        PickerContent(
            state = PickerState(
                members = UiState.Content(m),
                selectedUuids = listOf(m[3].uuid, m[0].uuid),
                seeded = true,
            ),
            onBack = {}, onToggle = {}, onDeselectAll = {}, onConfirm = {}, onRetry = {}, onSignOut = {},
        )
    }

    @Test fun history() = shot("phone-history") {
        HistoryContent(UiState.Content(PreviewData.historyRecent), {}, {}, {})
    }
}
