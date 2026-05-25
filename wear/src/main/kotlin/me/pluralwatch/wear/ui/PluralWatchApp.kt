package me.pluralwatch.wear.ui

import androidx.compose.runtime.Composable
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import me.pluralwatch.shared.repository.PluralKitRepository
import me.pluralwatch.wear.ui.screens.FrontersScreen
import me.pluralwatch.wear.ui.screens.HistoryScreen
import me.pluralwatch.wear.ui.screens.MemberPickerScreen
import me.pluralwatch.wear.ui.theme.PluralWatchTheme

object Routes {
    const val FRONTERS = "fronters"
    const val PICK_MEMBERS = "pick"
    const val HISTORY = "history"
}

@Composable
fun PluralWatchApp(repository: PluralKitRepository) {
    PluralWatchTheme {
        val navController = rememberSwipeDismissableNavController()
        SwipeDismissableNavHost(
            navController = navController,
            startDestination = Routes.FRONTERS,
        ) {
            composable(Routes.FRONTERS) {
                FrontersScreen(
                    repository = repository,
                    onChangeFronter = { navController.navigate(Routes.PICK_MEMBERS) },
                    onOpenHistory = { navController.navigate(Routes.HISTORY) },
                )
            }
            composable(Routes.PICK_MEMBERS) {
                MemberPickerScreen(
                    repository = repository,
                    onDone = { navController.popBackStack() },
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
