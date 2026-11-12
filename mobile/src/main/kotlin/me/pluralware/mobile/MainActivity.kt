package me.pluralware.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import me.pluralware.mobile.ui.theme.PluralWareTheme

class MainActivity : ComponentActivity() {

    // A screen asked for by an intent, until the app has opened it.
    private var requestedRoute by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 15+ draws behind the system bars regardless; this keeps their
        // icons legible on older versions too. Each screen's Scaffold pads for them.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // On a restore, the back stack already holds whatever was asked for.
        if (savedInstanceState == null) requestedRoute = intent.route()
        val graph = AppGraph.get(applicationContext)
        setContent {
            PluralWareTheme {
                PhoneApp(
                    graph = graph,
                    requestedRoute = requestedRoute,
                    onRequestHandled = { requestedRoute = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.route()?.let { requestedRoute = it }
    }

    private fun Intent.route(): String? = when (getStringExtra(EXTRA_SCREEN)) {
        SCREEN_FOLLOWING -> Routes.FOLLOWING
        else -> null
    }

    companion object {
        /** Which screen to open; a friend's notification opens Following. */
        const val EXTRA_SCREEN = "me.pluralware.mobile.SCREEN"
        const val SCREEN_FOLLOWING = "following"
    }
}
