package me.pluralware.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import me.pluralware.shared.mock.MockPluralKitClient
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.wear.ui.PluralWareApp

class MainActivity : ComponentActivity() {

    // Manual DI for now. When the dependency graph grows, swap for Hilt or Koin —
    // but at this size, a top-level object container would be overkill.
    private val repository by lazy {
        // TODO: once the phone→watch token handoff lands, read the token from
        // EncryptedTokenStore and use:
        //   PluralKitRepository(PluralKitClientFactory.create(token, enableLogging = BuildConfig.DEBUG))
        PluralKitRepository(MockPluralKitClient())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PluralWareApp(repository = repository)
        }
    }
}
