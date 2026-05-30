package me.pluralware.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.wear.tooling.preview.devices.WearDevices
import androidx.compose.ui.tooling.preview.Preview
import me.pluralware.shared.mock.MockPluralKitClient
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.settings.InMemorySettingsStore
import me.pluralware.wear.ui.screens.FrontersScreen
import me.pluralware.wear.ui.screens.HistoryScreen
import me.pluralware.wear.ui.screens.MemberPickerScreen
import me.pluralware.wear.ui.theme.PluralWareTheme

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
    PluralWareTheme {
        FrontersScreen(
            repository = previewRepository(),
            settingsStore = remember { InMemorySettingsStore() },
            onChangeFronter = {},
            onOpenHistory = {},
        )
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true, name = "Fronters · round small")
@Composable
private fun FrontersScreenSmallPreview() {
    PluralWareTheme {
        FrontersScreen(
            repository = previewRepository(),
            settingsStore = remember { InMemorySettingsStore() },
            onChangeFronter = {},
            onOpenHistory = {},
        )
    }
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "Picker")
@Composable
private fun MemberPickerScreenPreview() {
    PluralWareTheme {
        MemberPickerScreen(repository = previewRepository(), onDone = {})
    }
}

@Preview(device = WearDevices.LARGE_ROUND, showSystemUi = true, name = "History")
@Composable
private fun HistoryScreenPreview() {
    PluralWareTheme {
        HistoryScreen(repository = previewRepository(), onDone = {})
    }
}
