package me.pluralware.mobile.widget

import android.content.Context
import android.content.Intent
import androidx.glance.action.actionParametersOf
import androidx.test.core.app.ApplicationProvider
import androidx.glance.appwidget.testing.unit.runGlanceAppWidgetUnitTest
import androidx.glance.appwidget.testing.unit.assertHasStartActivityClickAction
import androidx.glance.testing.unit.hasContentDescriptionEqualTo
import androidx.glance.testing.unit.hasText
import androidx.glance.testing.unit.hasTextEqualTo
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import me.pluralware.mobile.MainActivity
import me.pluralware.shared.fronter.FronterDisplay
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FronterWidgetContentTest {

    private val zone = ZoneId.of("America/Los_Angeles")
    private val clock = Clock.fixed(Instant.parse("2026-11-17T23:00:00Z"), zone) // 3:00 PM there
    private val alex = FronterDisplay(
        title = "Fronter",
        text = "Fronting: Alex",
        description = "Current fronter: Alex",
        since = Instant.parse("2026-11-17T22:04:00Z"),
    )

    private val locale = Locale.getDefault()

    @Before
    fun usLocale() = Locale.setDefault(Locale.US)

    @After
    fun restoreLocale() = Locale.setDefault(locale)

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun `shows who's fronting and since what time, and opens the app`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable { FronterWidgetContent(alex, clock, wide = false) }

        onNode(hasTextEqualTo("Fronting: Alex")).assertExists()
        onNode(hasText("since 2:04")).assertExists()
        onNode(hasTextEqualTo("Fronter")).assertDoesNotExist()
        onNode(hasTextEqualTo("Switch")).assertDoesNotExist()
        onNode(hasContentDescriptionEqualTo("Current fronter: Alex")).assertHasStartActivityClickAction(openApp(context))
    }

    @Test
    fun `a switch from an earlier day shows its date`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable { FronterWidgetContent(alex.copy(since = Instant.parse("2026-11-14T22:04:00Z")), clock, wide = false) }

        onNode(hasTextEqualTo("since Nov 14, 2026")).assertExists()
    }

    @Test
    fun `the wide size's Switch button opens the picker`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable { FronterWidgetContent(alex, clock, wide = true) }

        onNode(hasTextEqualTo("Switch"))
            .assertHasStartActivityClickAction(openApp(context), actionParametersOf(SCREEN to MainActivity.SCREEN_PICK))
    }

    @Test
    fun `a last known line says so`() = runGlanceAppWidgetUnitTest {
        setContext(context)
        provideComposable { FronterWidgetContent(alex.copy(title = "Last known"), clock, wide = false) }

        onNode(hasTextEqualTo("Last known")).assertExists()
    }

    @Test
    fun `a tap brings the open app forward as it was, rather than another copy`() {
        val flags = openApp(context).flags
        val wanted = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        assertEquals(wanted, flags and wanted)
    }
}
