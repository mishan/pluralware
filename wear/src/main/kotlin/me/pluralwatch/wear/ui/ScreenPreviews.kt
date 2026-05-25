package me.pluralwatch.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.wear.tooling.preview.devices.WearDevices
import androidx.compose.ui.tooling.preview.Preview
import me.pluralwatch.shared.mock.MockPluralKitClient
import me.pluralwatch.shared.repository.PluralKitRepository
import me.pluralwatch.wear.ui.screens.FrontersScreen
import me.pluralwatch.wear.ui.screens.HistoryScreen
import me.pluralwatch.wear.ui.screens.MemberPickerScreen
import me.pluralwatch.wear.ui.theme.PluralWatchTheme

/**
 * Previews use the [MockPluralKitClient] with zero latency, so the design tab
 * shows content immediately instead of perpetual loading spinners.
 */
@Composable
private fun previewRepository(): PluralKitRepository = remember {
    PluralKitRepository(MockPluralKitClient(artificialLatencyMillis = 0))
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Fronters · round large")
@Composable
private fun FrontersScreenPreview() {
    PluralWatchTheme {
        FrontersScreen(repository = previewRepository(), onChangeFronter = {}, onOpenHistory = {})
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true, name = "Fronters · round small")
@Composable
private fun FrontersScreenSmallPreview() {
    PluralWatchTheme {
        FrontersScreen(repository = previewRepository(), onChangeFronter = {}, onOpenHistory = {})
    }
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Picker")
@Composable
private fun MemberPickerScreenPreview() {
    PluralWatchTheme {
        MemberPickerScreen(repository = previewRepository(), onDone = {})
    }
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "History")
@Composable
private fun HistoryScreenPreview() {
    PluralWatchTheme {
        HistoryScreen(repository = previewRepository(), onDone = {})
    }
}
