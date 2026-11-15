package me.pluralware.mobile.ui

import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import java.time.Instant
import me.pluralware.mobile.Routes
import me.pluralware.shared.model.Switch
import me.pluralware.shared.preview.PreviewData
import me.pluralware.shared.ui.viewmodel.PickerViewModel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import androidx.compose.ui.test.junit4.createComposeRule

@RunWith(RobolectricTestRunner::class)
class FixMembersRouteTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun `the picker gets the switch and its members, in order, through navigation`() {
        val m = PreviewData.members
        val switches = listOf(
            Switch("s-several", Instant.EPOCH, listOf(m[3], m[0], m[2])),
            Switch("s-out", Instant.EPOCH, emptyList()),
        )
        val received = mutableListOf<PickerViewModel.Editing>()
        var index = 0
        compose.setContent {
            val nav = rememberNavController()
            NavHost(nav, startDestination = "start") {
                composable("start") { LaunchedEffect(Unit) { nav.navigate(Routes.fixMembers(switches[index])) } }
                composable(Routes.FIX_MEMBERS) { entry ->
                    LaunchedEffect(entry) {
                        received += Routes.fixing(entry.arguments)
                        if (++index < switches.size) nav.navigate(Routes.fixMembers(switches[index]))
                    }
                }
            }
        }
        compose.waitForIdle()

        assertEquals(
            switches.map { s -> PickerViewModel.Editing(s.uuid, s.members.map { it.uuid }) },
            received,
        )
    }
}
