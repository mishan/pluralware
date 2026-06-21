package me.pluralware.wear.ui

import androidx.compose.runtime.Composable
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.settings.SettingsStore
import me.pluralware.wear.ui.screens.FrontersScreen
import me.pluralware.wear.ui.screens.HistoryScreen
import me.pluralware.wear.ui.screens.MemberPickerScreen
import me.pluralware.wear.ui.theme.PluralWareTheme

object Routes {
    const val FRONTERS = "fronters"
    const val PICK_MEMBERS = "pick"
    const val HISTORY = "history"
}

@Composable
fun PluralWareApp(
    repository: PluralKitRepository,
    settingsStore: SettingsStore,
    onSwitchRegistered: () -> Unit = {},
) {
    PluralWareTheme {
        val navController = rememberSwipeDismissableNavController()
        SwipeDismissableNavHost(
            navController = navController,
            startDestination = Routes.FRONTERS,
        ) {
            composable(Routes.FRONTERS) {
                FrontersScreen(
                    repository = repository,
                    settingsStore = settingsStore,
                    onChangeFronter = { navController.navigate(Routes.PICK_MEMBERS) },
                    onOpenHistory = { navController.navigate(Routes.HISTORY) },
                )
            }
            composable(Routes.PICK_MEMBERS) {
                MemberPickerScreen(
                    repository = repository,
                    onDone = { navController.popBackStack() },
                    onSwitchRegistered = onSwitchRegistered,
                )
            }
            composable(Routes.HISTORY) {
                HistoryScreen(
                    repository = repository,
                    onDone = { navController.popBackStack(Routes.FRONTERS, inclusive = false) },
                )
            }
        }
    }
}
