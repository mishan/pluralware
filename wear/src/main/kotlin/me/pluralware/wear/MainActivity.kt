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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import me.pluralware.shared.api.PluralKitClientFactory
import me.pluralware.shared.api.PluralKitToken
import me.pluralware.shared.repository.EncryptedTokenStore
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.wear.ui.PluralWareApp

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val tokenStore = EncryptedTokenStore.get(applicationContext)
        setContent {
            val token by tokenStore.tokenFlow.collectAsState()
            when (val t = token) {
                null -> WaitingForPairingScreen()
                else -> ConnectedApp(t)
            }
        }
    }
}

@Composable
private fun ConnectedApp(token: PluralKitToken) {
    // Re-key on the raw token: a token replacement recreates the repository so
    // we don't accidentally serve cached member data for the wrong system.
    val repository = remember(token.raw) {
        PluralKitRepository(
            PluralKitClientFactory.create(
                token = token,
                enableLogging = BuildConfig.DEBUG,
            )
        )
    }
    PluralWareApp(repository = repository)
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
