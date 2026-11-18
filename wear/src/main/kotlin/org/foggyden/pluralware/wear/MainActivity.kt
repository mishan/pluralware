package org.foggyden.pluralware.wear

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import org.foggyden.pluralware.wear.pairing.openOnPhone
import androidx.wear.compose.material.CompactChip
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.foggyden.pluralware.shared.api.PluralKitClientFactory
import org.foggyden.pluralware.shared.api.PluralKitToken
import org.foggyden.pluralware.shared.mock.MockPluralKitClient
import org.foggyden.pluralware.shared.handoff.SharingHandoff
import org.foggyden.pluralware.shared.handoff.MemberSync
import org.foggyden.pluralware.shared.handoff.SwitchSync
import org.foggyden.pluralware.shared.notify.EncryptedSharingStore
import org.foggyden.pluralware.shared.notify.SwitchAnnouncer
import org.foggyden.pluralware.shared.repository.EncryptedTokenStore
import org.foggyden.pluralware.shared.repository.InMemoryTokenStore
import org.foggyden.pluralware.shared.repository.PluralKitRepository
import org.foggyden.pluralware.shared.repository.TokenStore
import org.foggyden.pluralware.shared.session.Session
import org.foggyden.pluralware.shared.session.SessionHolder
import org.foggyden.pluralware.shared.settings.LocalSettingsStore
import org.foggyden.pluralware.shared.settings.SettingsStore
import org.foggyden.pluralware.wear.complication.FronterSurfaces
import org.foggyden.pluralware.wear.ui.PluralWareApp

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
        // The phone may have sent the token before this app was installed. Not in the
        // composition: storing the token leaves the unpaired screen, which would cancel it.
        if (!BuildConfig.BENCHMARK) {
            lifecycleScope.launch {
                if (tokenStore.getToken() == null) runCatching { catchUpWithPhone(applicationContext) }
            }
        }
        setContent {
            val token by tokenStore.tokenFlow.collectAsState()
            val scope = rememberCoroutineScope()
            val sessions: SessionHolder = viewModel()
            when (val t = token) {
                null -> {
                    SideEffect { sessions.end() }
                    WaitingForPairingScreen(openOnPhone = { openOnPhone(applicationContext) })
                }
                // Keyed on the raw token so a replacement starts from scratch:
                // a new session (repository and ViewModel store) and a new nav
                // graph. Without it the screens' ViewModels would outlive the
                // swap and keep polling the old system with the old token.
                else -> key(t.raw) {
                    val session = sessions.sessionFor(t) { newRepository(t, applicationContext) }
                    ConnectedApp(
                        session = session,
                        settingsStore = settingsStore,
                        onSignOut = {
                            scope.launch {
                                tokenStore.clear()
                                EncryptedSharingStore.get(applicationContext).clear()
                                FronterSurfaces.onTokenChanged(applicationContext)
                            }
                        },
                    )
                }
            }
        }
    }
}

private fun newRepository(token: PluralKitToken, appContext: Context): PluralKitRepository {
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
    // Friends hear about each switch this repository registers (never ones it
    // only observes); see SwitchAnnouncer.
    return PluralKitRepository(client) { switch ->
        if (!BuildConfig.BENCHMARK) announcer.onSwitchRegistered(appContext, switch)
    }
}

// Process-wide, like the sends it runs. Friends this watch finds gone are
// reported to the phone, which skips them too and shows who stopped receiving.
private val announcer = SwitchAnnouncer(BuildConfig.VERSION_NAME) { context, gone ->
    SharingHandoff.pushStatus(context, gone)
}

@Composable
private fun ConnectedApp(
    session: Session,
    settingsStore: SettingsStore,
    onSignOut: () -> Unit,
) {
    // The screens' ViewModels (through the nav graph's back-stack entries)
    // live in the session's store, not the activity's.
    val appContext = LocalContext.current.applicationContext
    // Every switch the app sees — from the picker, the phone, or any
    // refresh — lands in the repository; hand each change to the
    // complication and tile, including "no switches yet".
    LaunchedEffect(session) {
        session.repository.loadedFronters.filterNotNull().collect {
            FronterSurfaces.publish(appContext, it.switch, session.token)
        }
    }
    // While the app is showing, keep the phone on the same switch, both ways,
    // and catch up on members edited there.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(session, lifecycleOwner) {
        if (BuildConfig.BENCHMARK) return@LaunchedEffect
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            launch {
                SwitchSync.keepInSync(session.repository, session.token, push = { switch ->
                    SwitchSync.push(appContext, switch, session.token)
                })
            }
            MemberSync.keepInSync(session.repository, session.token, push = {
                MemberSync.push(appContext, session.token)
            }, progress = session.memberSync)
        }
    }
    CompositionLocalProvider(LocalViewModelStoreOwner provides session) {
        PluralWareApp(repository = session.repository, settingsStore = settingsStore, onSignOut = onSignOut)
    }
}

@Composable
internal fun WaitingForPairingScreen(openOnPhone: suspend () -> Boolean) {
    // Null until tapped, then whether the phone was reached.
    var opened by remember { mutableStateOf<Boolean?>(null) }
    // One attempt at a time, so an older answer can't overwrite a newer one.
    var opening by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
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
                text = when (opened) {
                    true -> "Check your phone, and connect your PluralKit token there."
                    false -> "Couldn't reach your phone. Open PluralWare there and connect your PluralKit token."
                    null -> "Open PluralWare on your phone and connect your PluralKit token."
                },
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (opened != true) {
                CompactChip(
                    onClick = {
                        opening = true
                        scope.launch {
                            try {
                                opened = openOnPhone()
                            } finally {
                                opening = false
                            }
                        }
                    },
                    enabled = !opening,
                    label = { Text("Open on phone") },
                )
            }
        }
    }
}
