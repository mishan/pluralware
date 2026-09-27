package me.pluralware.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.launch
import me.pluralware.shared.api.PluralKitClientFactory
import me.pluralware.shared.api.PluralKitToken
import me.pluralware.shared.mock.MockPluralKitClient
import me.pluralware.shared.repository.EncryptedTokenStore
import me.pluralware.shared.repository.InMemoryTokenStore
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.repository.TokenStore
import me.pluralware.shared.settings.LocalSettingsStore
import me.pluralware.shared.settings.SettingsStore
import me.pluralware.wear.ui.PluralWareApp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Benchmark variant skips encrypted storage and pre-seeds a token so
        // the baseline-profile producer can drive the real screens without a
        // paired phone. Production builds always take the encrypted path.
        val tokenStore: TokenStore = if (BuildConfig.BENCHMARK) {
            InMemoryTokenStore(initial = PluralKitToken("benchmark"))
        } else {
            EncryptedTokenStore.get(applicationContext)
        }
        val settingsStore = LocalSettingsStore.get(applicationContext)
        setContent {
            val token by tokenStore.tokenFlow.collectAsState()
            val scope = rememberCoroutineScope()
            when (val t = token) {
                null -> WaitingForPairingScreen()
                // Keyed on the raw token so a replacement starts from scratch:
                // a new repository, and a new nav graph whose ViewModels hold
                // it. Without the key the ViewModels outlive the swap and keep
                // polling the old system with the old token.
                else -> key(t.raw) {
                    ConnectedApp(
                        token = t,
                        settingsStore = settingsStore,
                        onSignOut = { scope.launch { tokenStore.clear() } },
                    )
                }
            }
        }
    }
}

@Composable
private fun ConnectedApp(
    token: PluralKitToken,
    settingsStore: SettingsStore,
    onSignOut: () -> Unit,
) {
    val repository = remember {
        val client = if (BuildConfig.BENCHMARK) {
            // Zero-latency mock keeps the macrobenchmark deterministic and
            // independent of network conditions.
            MockPluralKitClient(artificialLatencyMillis = 0L)
        } else {
            PluralKitClientFactory.create(
                token = token,
                appVersion = BuildConfig.VERSION_NAME,
                enableLogging = BuildConfig.DEBUG,
            )
        }
        PluralKitRepository(client)
    }
    PluralWareApp(repository = repository, settingsStore = settingsStore, onSignOut = onSignOut)
}

@Composable
private fun WaitingForPairingScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Pair with phone",
                style = MaterialTheme.typography.title3,
                color = MaterialTheme.colors.onBackground,
                textAlign = TextAlign.Center,
            )
            Text(
                text = "Open PluralWare on your phone and connect your PluralKit token.",
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}
