package me.pluralware.wear.screenshots

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import java.io.File
import java.time.Clock
import java.time.ZoneOffset
import me.pluralware.shared.model.Member
import me.pluralware.shared.model.Switch
import me.pluralware.shared.model.SystemInfo
import me.pluralware.shared.preview.PreviewData
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.settings.InMemorySettingsStore
import me.pluralware.wear.ReadOnlyPluralKitClient
import me.pluralware.wear.ui.LocalClock
import me.pluralware.wear.ui.screens.FrontersScreen
import me.pluralware.wear.ui.screens.HistoryScreen
import me.pluralware.wear.ui.screens.MemberPickerScreen
import me.pluralware.wear.ui.theme.PluralWareTheme
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The watch screenshots the README shows: the real screens, fed [PreviewData]
 * by a fake PluralKit, on a large round watch, with the clock stopped at
 * [PreviewData.anchor]. Skipped unless asked for: tools/screenshots.sh runs them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w227dp-h227dp-small-notlong-round-watch-xhdpi-keyshidden-nonav")
class ReadmeScreenshots {
    @get:Rule val compose = createComposeRule()

    private val out: String? = System.getProperty("pluralware.screenshots")

    @Before fun onlyWhenAsked() = assumeTrue("screenshots not requested", out != null)

    private val client = object : ReadOnlyPluralKitClient() {
        override suspend fun getOwnSystem(): SystemInfo = PreviewData.system
        override suspend fun getOwnMembers(): List<Member> = PreviewData.members
        override suspend fun getCurrentFronters(): Switch = PreviewData.currentSwitch
        override suspend fun getRecentSwitches(limit: Int): List<Switch> = PreviewData.historyRecent
        override suspend fun registerSwitch(memberUuids: List<String>): Switch = PreviewData.currentSwitch
    }

    private fun shot(name: String, content: @Composable (PluralKitRepository) -> Unit) {
        val repository = PluralKitRepository(client)
        compose.setContent {
            CompositionLocalProvider(LocalClock provides Clock.fixed(PreviewData.anchor, ZoneOffset.UTC)) {
                PluralWareTheme { content(repository) }
            }
        }
        compose.waitForIdle()
        val file = "$out/$name.png"
        compose.onRoot().captureRoboImage(file)
        roundOff(file)
    }

    /**
     * A round watch's screen is a circle: clear the corners of the square
     * picture, so it sits on any page background. Drawn here, with Android's
     * own canvas, so the edge comes out the same wherever it's rendered.
     */
    private fun roundOff(file: String) {
        val square = BitmapFactory.decodeFile(file)
        val round = Bitmap.createBitmap(square.width, square.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(round)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawCircle(square.width / 2f, square.height / 2f, minOf(square.width, square.height) / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(square, 0f, 0f, paint)
        File(file).outputStream().use { round.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun home() = shot("watch-home") {
        FrontersScreen(it, InMemorySettingsStore(), onChangeFronter = {}, onOpenHistory = {}, onSignOut = {})
    }

    @Test fun picker() = shot("watch-picker") {
        MemberPickerScreen(it, onDone = {}, onSignOut = {})
    }

    @Test fun history() = shot("watch-history") {
        HistoryScreen(it, onSignOut = {})
    }
}
