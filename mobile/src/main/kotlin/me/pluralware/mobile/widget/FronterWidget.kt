package me.pluralware.mobile.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import me.pluralware.mobile.MainActivity
import me.pluralware.shared.fronter.FronterDisplay
import me.pluralware.shared.fronter.FronterFormatter

/** Who's fronting and since when; the wide size adds a Switch button. See docs/complication-design.md. */
class FronterWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Responsive(setOf(SMALL, WIDE))

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val surfaces = PhoneWidgetSurfaces.get(context)
        provideContent {
            // Within the content, not before it: a slow fetch mustn't outlast the session waiting for it.
            LaunchedEffect(Unit) { surfaces.prepare() }
            // Read from the flow: a widget already showing is recomposed on update, not provided again.
            val display by surfaces.display.collectAsState()
            GlanceTheme {
                display?.let {
                    FronterWidgetContent(it, Clock.systemDefaultZone(), wide = LocalSize.current.width >= WIDE.width)
                }
            }
        }
    }

    companion object {
        val SMALL = DpSize(110.dp, 40.dp)
        val WIDE = DpSize(250.dp, 40.dp)
    }
}

class FronterWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = FronterWidget()

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        // The 30-minute poll: ask PluralKit again rather than redraw what's held.
        PhoneWidgetSurfaces.get(context).invalidate()
        super.onUpdate(context, appWidgetManager, appWidgetIds)
    }
}

internal val SCREEN = ActionParameters.Key<String>(MainActivity.EXTRA_SCREEN)

/** Brings the app forward, as it was, rather than opening another copy on top. */
internal fun openApp(context: Context): Intent =
    Intent(context, MainActivity::class.java)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

@Composable
internal fun FronterWidgetContent(display: FronterDisplay, clock: Clock, wide: Boolean) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(GlanceTheme.colors.widgetBackground)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clickable(actionStartActivity(openApp(context)))
            .semantics { contentDescription = display.description },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = GlanceModifier.defaultWeight()) {
            if (display.title != FronterFormatter.TITLE) {
                Text(display.title, style = TextStyle(color = GlanceTheme.colors.primary, fontSize = 12.sp), maxLines = 1)
            }
            Text(
                display.text,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium),
                // One line in the small size, so "since" always fits.
                maxLines = if (wide) 2 else 1,
            )
            display.since?.let {
                Text(since(it, clock), style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 13.sp), maxLines = 1)
            }
        }
        if (wide) {
            Spacer(GlanceModifier.width(8.dp))
            Button(
                text = "Switch",
                onClick = actionStartActivity(openApp(context), actionParametersOf(SCREEN to MainActivity.SCREEN_PICK)),
            )
        }
    }
}

/** A time, not a duration: the widget redraws only when updated, and a duration would go stale. */
internal fun since(at: Instant, clock: Clock): String {
    val local = at.atZone(clock.zone)
    val shown = if (local.toLocalDate() == LocalDate.now(clock)) {
        local.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
    } else {
        local.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
    }
    return "since $shown"
}
