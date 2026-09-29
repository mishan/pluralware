package me.pluralware.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewmodel.compose.viewModel
import me.pluralware.mobile.following.FollowingScreen
import me.pluralware.mobile.following.FollowingViewModel
import me.pluralware.mobile.sharing.SharingScreen
import me.pluralware.mobile.sharing.SharingViewModel
import me.pluralware.mobile.ui.TokenEntryScreen
import me.pluralware.mobile.ui.TokenEntryViewModel
import me.pluralware.shared.notify.EncryptedFollowingStore
import me.pluralware.shared.notify.EncryptedSharingStore
import me.pluralware.shared.repository.EncryptedTokenStore
import me.pluralware.shared.settings.LocalSettingsStore

class MainActivity : ComponentActivity() {

    // Three screens, no back stack worth a navigation library: home, and one
    // level down to sharing or following.
    private var screen by mutableStateOf(SCREEN_HOME)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 15+ draws behind the system bars regardless; this keeps their
        // icons legible on older versions too. Each screen's Scaffold pads for them.
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        screen = savedInstanceState?.getString(EXTRA_SCREEN) ?: intent.screen() ?: SCREEN_HOME
        val tokenStore = EncryptedTokenStore.get(applicationContext)
        val settingsStore = LocalSettingsStore.get(applicationContext)
        val sharingStore = EncryptedSharingStore.get(applicationContext)
        val followingStore = EncryptedFollowingStore.get(applicationContext)
        setContent {
            MaterialTheme {
                BackHandler(enabled = screen != SCREEN_HOME) { screen = SCREEN_HOME }
                val home = { screen = SCREEN_HOME }
                when (screen) {
                    SCREEN_SHARING -> SharingScreen(
                        viewModel = viewModel(
                            factory = SharingViewModel.Factory(tokenStore, sharingStore, applicationContext),
                        ),
                        onBack = home,
                    )
                    SCREEN_FOLLOWING -> FollowingScreen(
                        viewModel = viewModel(
                            factory = FollowingViewModel.Factory(followingStore, applicationContext),
                        ),
                        onBack = home,
                    )
                    else -> TokenEntryScreen(
                        viewModel = viewModel(
                            factory = TokenEntryViewModel.Factory(
                                tokenStore,
                                settingsStore,
                                sharingStore,
                                applicationContext,
                            ),
                        ),
                        onOpenSharing = { screen = SCREEN_SHARING },
                        onOpenFollowing = { screen = SCREEN_FOLLOWING },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.screen()?.let { screen = it }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(EXTRA_SCREEN, screen)
    }

    private fun Intent.screen(): String? = getStringExtra(EXTRA_SCREEN)

    companion object {
        /** Which screen to open; a friend's notification opens Following. */
        const val EXTRA_SCREEN = "me.pluralware.mobile.SCREEN"
        const val SCREEN_HOME = "home"
        const val SCREEN_SHARING = "sharing"
        const val SCREEN_FOLLOWING = "following"
    }
}
