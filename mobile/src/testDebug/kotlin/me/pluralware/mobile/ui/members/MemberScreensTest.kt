package me.pluralware.mobile.ui.members

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import me.pluralware.mobile.ui.theme.PluralWareTheme
import kotlinx.coroutines.runBlocking
import me.pluralware.shared.mock.MockPluralKitClient
import me.pluralware.shared.model.Member
import me.pluralware.shared.model.MemberDraft
import me.pluralware.shared.model.MemberField
import me.pluralware.shared.preview.PreviewData
import me.pluralware.shared.repository.PkResult
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.ui.state.UiState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class MemberScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val members = PreviewData.members
    private val alex = members[0].copy(description = "Likes tea.")
    private val events = mutableListOf<String>()
    private val saves = mutableListOf<Pair<Member, MemberDraft>>()

    // --- Members -------------------------------------------------------------------------------

    private fun showMembers(state: UiState<List<Member>>, reloadError: String? = null) = compose.setContent {
        PluralWareTheme {
            MembersContent(
                state = state,
                reloadError = reloadError,
                onBack = { events += "back" },
                onOpenMember = { events += "open:$it" },
                onRetry = { events += "retry" },
                onSignOut = { events += "signout" },
            )
        }
    }

    @Test
    fun `members are listed with their pronouns, and tapping one opens it`() {
        showMembers(UiState.Content(members))

        compose.onNodeWithText(members[1].displayLabel).assertExists()
        members[1].pronouns?.let { compose.onAllNodesWithText(it, substring = true).onFirst().assertExists() }
        compose.onNodeWithText(members[2].displayLabel).performClick()

        assertEquals(listOf("open:${members[2].uuid}"), events)
    }

    @Test
    fun `a long member list can be searched`() {
        val many = (1..12).map { members[it % members.size].copy(uuid = "u$it", id = "id$it", name = "Member $it", displayName = null) }
        showMembers(UiState.Content(many))

        compose.onNodeWithTag("member-search").performTextReplacement("Member 11")

        val row = hasClickAction() and !hasSetTextAction()
        compose.onNode(hasText("Member 11") and row).assertExists()
        compose.onNode(hasText("Member 2") and row).assertDoesNotExist()
    }

    @Test
    fun `a list that shrinks below where search starts drops the search, so no filter is left stuck`() {
        val many = (1..12).map { members[it % members.size].copy(uuid = "u$it", id = "id$it", name = "Member $it", displayName = null) }
        var state by mutableStateOf<UiState<List<Member>>>(UiState.Content(many))
        compose.setContent {
            PluralWareTheme { MembersContent(state, null, {}, {}, {}, {}) }
        }
        compose.onNodeWithTag("member-search").performTextReplacement("Nobody")

        state = UiState.Content(many.take(3))

        compose.onNodeWithTag("member-search").assertDoesNotExist()
        for (name in listOf("Member 1", "Member 2", "Member 3")) {
            compose.onNode(hasText(name) and hasClickAction()).assertExists()
        }
    }

    @Test
    fun `a reload that fails keeps the list, says so, and offers a retry`() {
        showMembers(UiState.Content(members), reloadError = "Couldn't reach PluralKit.")

        compose.onNodeWithText(members[0].displayLabel).assertExists()
        compose.onNodeWithText("Couldn't reach PluralKit.", substring = true).assertExists()
        compose.onNodeWithText("Retry").performClick()

        assertEquals(listOf("retry"), events)
    }

    @Test
    fun `turning the phone doesn't load the list again`() {
        val pk = MockPluralKitClient(artificialLatencyMillis = 0)
        var loads = 0
        val counting = object : me.pluralware.shared.api.PluralKitClient by pk {
            override suspend fun getOwnMembers() = pk.getOwnMembers().also { loads++ }
        }
        val repository = PluralKitRepository(counting)
        val vm = MembersViewModel(repository)
        val restore = StateRestorationTester(compose)
        restore.setContent { PluralWareTheme { MembersScreen(repository, {}, {}, {}, vm = vm) } }
        compose.waitForIdle()

        restore.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        assertEquals(1, loads)
    }

    @Test
    fun `a system with no members says so`() {
        showMembers(UiState.Content(emptyList()))

        compose.onNodeWithText("No members yet", substring = true).assertExists()
    }

    // --- Editor --------------------------------------------------------------------------------

    private fun showEditor(state: EditorState) = compose.setContent {
        PluralWareTheme { editor(state) }
    }

    @androidx.compose.runtime.Composable
    private fun editor(state: EditorState) = MemberEditorContent(
        state = state,
        onSave = { original, draft -> saves += original to draft },
        onEdited = { events += "edited:${it.apiKey}" },
        onBack = { events += "back" },
        onRetry = { events += "retry" },
        onSignOut = { events += "signout" },
    )

    private fun loaded(member: Member = alex) = EditorState(member = UiState.Content(member))
    private fun field(field: MemberField) = compose.onNodeWithTag("field-${field.apiKey}")

    @Test
    fun `the form starts as the member is, and Save waits for a change`() {
        showEditor(loaded())

        field(MemberField.Name).assertTextContains(alex.name)
        field(MemberField.Description).assertTextContains("Likes tea.")
        compose.onNodeWithText("Save").assertIsNotEnabled()

        field(MemberField.Pronouns).performTextReplacement("xe/xem")
        compose.onNodeWithText("Save").assertIsEnabled().performClick()

        assertEquals(listOf(alex to MemberDraft.of(alex).copy(pronouns = "xe/xem")), saves)
    }

    @Test
    fun `a value PluralKit would refuse says why, and can't be saved`() {
        showEditor(loaded())

        field(MemberField.Color).performTextReplacement("red")

        compose.onNodeWithText("Six hex digits", substring = true).assertExists()
        compose.onNodeWithText("Save").assertIsNotEnabled()

        field(MemberField.Color).performTextReplacement("#ff0000")

        compose.onNodeWithText("Six hex digits", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Save").assertIsEnabled()
    }

    @Test
    fun `the avatar shows once its https address has been still a moment, not while it's typed`() {
        showEditor(loaded(alex.copy(avatarUrl = null)))
        val preview = { compose.onNodeWithTag("avatar-preview", useUnmergedTree = true) }
        compose.mainClock.autoAdvance = false
        val cases = listOf(
            "example.com/a.png" to false,
            // Android blocks plain http, so it could never show.
            "http://example.com/a.png" to false,
            "https://example.com/a.png" to true,
        )
        for ((url, shows) in cases) {
            field(MemberField.AvatarUrl).performTextReplacement(url)
            compose.mainClock.advanceTimeBy(300)
            preview().assertDoesNotExist()
            compose.mainClock.advanceTimeBy(500)
            if (shows) preview().assertExists() else preview().assertDoesNotExist()
        }
    }

    @Test
    fun `PluralKit's complaint shows under its field, and editing the field clears it`() {
        showEditor(loaded().copy(fieldErrors = mapOf(MemberField.AvatarUrl to "Value must be a publicly-accessible URL.")))

        compose.onNodeWithText("Value must be a publicly-accessible URL.").assertExists()
        field(MemberField.AvatarUrl).performTextReplacement("https://example.com/b.png")

        assertEquals(listOf("edited:avatar_url"), events)
    }

    @Test
    fun `a failed save says why, and a rejected token offers sign-out`() {
        var state by mutableStateOf(loaded().copy(saveError = "Couldn't reach PluralKit. Check your connection and try again."))
        compose.setContent { PluralWareTheme { editor(state) } }

        compose.onNodeWithText("Couldn't reach PluralKit", substring = true).assertExists()

        state = loaded().copy(unauthorized = true, saveError = "PluralKit no longer accepts this token.")
        compose.onNodeWithText("Sign out").performClick()

        assertEquals(listOf("signout"), events)
    }

    @Test
    fun `while saving, Save says so, and nothing can be changed until it's done`() {
        showEditor(loaded().copy(saving = true))

        compose.onNodeWithText("Saving…").assertIsNotEnabled()
        for (f in MemberField.entries) field(f).assertIsNotEnabled()
    }

    @Test
    fun `leaving while a save is under way says it's still saving, and staying shows how it went`() {
        var state by mutableStateOf(loaded())
        compose.setContent { PluralWareTheme { editor(state) } }
        field(MemberField.Pronouns).performTextReplacement("xe/xem")
        state = state.copy(saving = true)

        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Still saving", substring = true).assertExists()
        compose.onNodeWithText("Discard your changes?").assertDoesNotExist()
        compose.onNodeWithText("Stay").performClick()
        state = state.copy(saving = false, saveError = "Couldn't reach PluralKit.")
        compose.onNodeWithText("Couldn't reach PluralKit.").assertExists()

        state = state.copy(saving = true, saveError = null)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Leave").performClick()

        assertEquals(listOf("back"), events.filterNot { it.startsWith("edited:") })
    }

    @Test
    fun `why a save failed is announced, not only shown`() {
        var state by mutableStateOf(loaded().copy(saveError = "Couldn't reach PluralKit."))
        compose.setContent { PluralWareTheme { editor(state) } }

        compose.onNodeWithText("Couldn't reach PluralKit.").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun `a failed reload is announced too`() {
        showMembers(UiState.Content(members), reloadError = "Couldn't reach PluralKit.")

        compose.onNodeWithText("Couldn't reach PluralKit.", substring = true)
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.LiveRegion))
    }

    @Test
    fun `a saved edit goes back once`() {
        val repository = PluralKitRepository(MockPluralKitClient(artificialLatencyMillis = 0))
        val uuid = (runBlocking { repository.refreshMembers() } as PkResult.Success).value.first().uuid
        val vm = MemberEditorViewModel(repository, uuid)
        compose.setContent {
            PluralWareTheme {
                MemberEditorScreen(
                    repository = repository,
                    uuid = uuid,
                    onBack = { events += "back" },
                    onSaved = { events += "saved" },
                    onSignOut = {},
                    vm = vm,
                )
            }
        }
        compose.waitUntil(5_000) { vm.state.value.member is UiState.Content }
        field(MemberField.Pronouns).performTextReplacement("xe/xem")

        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(5_000) { events.contains("saved") }

        assertEquals(listOf("saved"), events)
    }

    @Test
    fun `leaving with no changes just goes back`() {
        showEditor(loaded())

        compose.onNodeWithContentDescription("Back").performClick()

        assertEquals(listOf("back"), events)
    }

    @Test
    fun `leaving with changes asks first, by the back arrow or the system's back`() {
        var round by mutableStateOf(0)
        compose.setContent { PluralWareTheme { key(round) { editor(loaded()) } } }
        for (leave in listOf<() -> Unit>(
            { compose.onNodeWithContentDescription("Back").performClick() },
            {
                compose.waitForIdle()
                compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
            },
        )) {
            events.clear()
            round++
            field(MemberField.Pronouns).performTextReplacement("xe/xem")

            leave()
            compose.onNode(isDialog()).assertExists()
            compose.onNodeWithText("Keep editing").performClick()
            field(MemberField.Pronouns).assertTextContains("xe/xem")
            leave()
            compose.onNodeWithText("Discard").performClick()

            assertEquals(listOf("back"), events.filterNot { it.startsWith("edited:") })
        }
    }

    @Test
    fun `the form, and the member as it was loaded, survive the process being stopped`() {
        val restore = StateRestorationTester(compose)
        var state by mutableStateOf(loaded())
        restore.setContent { PluralWareTheme { editor(state) } }
        field(MemberField.Pronouns).performTextReplacement("xe/xem")

        // Back in, the screen loads afresh and finds the member edited elsewhere meanwhile.
        state = EditorState()
        restore.emulateSavedInstanceStateRestore()
        state = loaded(alex.copy(description = "Set in Discord."))

        field(MemberField.Pronouns).assertTextContains("xe/xem")
        compose.onNodeWithText("Save").performClick()
        assertEquals(alex, saves.single().first)
    }
}
