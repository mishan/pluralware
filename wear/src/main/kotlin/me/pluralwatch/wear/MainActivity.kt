package me.pluralwatch.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import me.pluralwatch.shared.mock.MockPluralKitClient
import me.pluralwatch.shared.repository.PluralKitRepository
import me.pluralwatch.wear.ui.PluralWatchApp

class MainActivity : ComponentActivity() {

    // Manual DI for now. When the dependency graph grows, swap for Hilt or Koin —
    // but at this size, a top-level object container would be overkill.
    private val repository by lazy {
        // TODO: replace with PluralKotClient(token) once the token-handoff path is built.
        PluralKitRepository(MockPluralKitClient())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PluralWatchApp(repository = repository)
        }
    }
}
