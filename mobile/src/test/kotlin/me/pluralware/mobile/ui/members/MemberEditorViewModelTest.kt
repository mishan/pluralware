package me.pluralware.mobile.ui.members

import io.mockk.coEvery
import io.mockk.mockk
import java.io.IOException
import java.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import me.pluralware.shared.api.PluralKitClient
import me.pluralware.shared.api.PluralKitHttpException
import me.pluralware.shared.mock.MockPluralKitClient
import me.pluralware.shared.model.Member
import me.pluralware.shared.model.MemberDraft
import me.pluralware.shared.model.MemberField
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.ui.state.UiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MemberEditorViewModelTest {

    /** PluralKit, recording each member edit it's sent. */
    private class RecordingClient(val pk: MockPluralKitClient = MockPluralKitClient(artificialLatencyMillis = 0)) :
        PluralKitClient by pk {
        val edits = mutableListOf<Map<MemberField, String?>>()
        var editFailure: Exception? = null
        var holdEdit: CompletableDeferred<Unit>? = null

        override suspend fun updateMember(uuid: String, changes: Map<MemberField, String?>): Member {
            edits += changes
            holdEdit?.await()
            editFailure?.let { throw it }
            return pk.updateMember(uuid, changes)
        }
    }

    private val client = RecordingClient()
    private lateinit var alex: Member

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        alex = kotlinx.coroutines.runBlocking { client.pk.getOwnMembers().first() }
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun editor(uuid: String = alex.uuid, client: PluralKitClient = this.client) =
        MemberEditorViewModel(PluralKitRepository(client), uuid)

    private fun MemberEditorViewModel.loaded() = (state.value.member as UiState.Content).value

    @Test
    fun `it loads the member fresh from PluralKit`() = runTest {
        val repository = PluralKitRepository(client)
        repository.refreshMembers()
        // Renamed in Discord since the app last fetched members.
        client.pk.updateMember(alex.uuid, mapOf(MemberField.Name to "Renamed"))

        val vm = MemberEditorViewModel(repository, alex.uuid)

        assertEquals("Renamed", vm.loaded().name)
    }

    @Test
    fun `a member that's gone, or a list that won't load, is an error`() = runTest {
        val gone = editor(uuid = "no-such-member").state.value.member as UiState.Error
        assertTrue(gone.message, gone.message.contains("isn't in PluralKit"))

        val failing = mockk<PluralKitClient>()
        coEvery { failing.getOwnMembers() } throws PluralKitHttpException(401, "Unauthorized")
        assertTrue((editor(client = failing).state.value.member as UiState.Error).unauthorized)
    }

    @Test
    fun `saving sends only what was edited, then closes`() = runTest {
        val vm = editor()

        vm.save(alex, MemberDraft.of(alex).copy(pronouns = " xe/xem ", color = "#ABCDEF"))

        assertEquals(listOf(mapOf(MemberField.Pronouns to "xe/xem", MemberField.Color to "abcdef")), client.edits)
        assertTrue(vm.state.value.saved)
        assertEquals(alex.copy(pronouns = "xe/xem", color = "abcdef"), client.pk.getOwnMembers().first())
    }

    @Test
    fun `saving with nothing changed closes without asking PluralKit`() = runTest {
        val vm = editor()

        vm.save(alex, MemberDraft.of(alex).copy(name = " ${alex.name} "))

        assertEquals(emptyList<Map<MemberField, String?>>(), client.edits)
        assertTrue(vm.state.value.saved)
    }

    @Test
    fun `an invalid draft isn't sent`() = runTest {
        val vm = editor()

        vm.save(alex, MemberDraft.of(alex).copy(color = "red"))

        assertEquals(emptyList<Map<MemberField, String?>>(), client.edits)
        assertFalse(vm.state.value.saved)
    }

    @Test
    fun `the diff is against the member the form loaded, not one fetched since`() = runTest {
        val vm = editor()
        val asLoaded = alex
        // Edited in Discord while the form was open, then the screen reloaded.
        client.pk.updateMember(alex.uuid, mapOf(MemberField.Description to "Set in Discord."))
        vm.load()

        vm.save(asLoaded, MemberDraft.of(asLoaded).copy(pronouns = "xe/xem"))

        assertEquals(listOf(mapOf(MemberField.Pronouns to "xe/xem")), client.edits)
        assertEquals("Set in Discord.", client.pk.getOwnMembers().first().description)
    }

    @Test
    fun `PluralKit's complaint about a field shows under that field, until it's edited`() = runTest {
        client.editFailure = PluralKitHttpException(
            400, "Error parsing JSON model", code = 40001,
            fieldErrors = mapOf("avatar_url" to listOf("Value must be a publicly-accessible URL."), "pronouns" to listOf("Too long.")),
        )
        val vm = editor()

        vm.save(alex, MemberDraft.of(alex).copy(avatarUrl = "https://localhost/a.png", pronouns = "they"))

        assertEquals(
            mapOf(MemberField.AvatarUrl to "Value must be a publicly-accessible URL.", MemberField.Pronouns to "Too long."),
            vm.state.value.fieldErrors,
        )
        assertNull(vm.state.value.saveError)
        assertFalse(vm.state.value.saved)

        vm.edited(MemberField.AvatarUrl)

        assertEquals(mapOf(MemberField.Pronouns to "Too long."), vm.state.value.fieldErrors)
    }

    @Test
    fun `a failed save says why, and the form is kept`() = runTest {
        val cases = listOf(
            IOException("Network unreachable") to "Couldn't reach PluralKit",
            PluralKitHttpException(429, "Too Many Requests", retryAfter = Duration.ofSeconds(3)) to "try again in 3 seconds",
            PluralKitHttpException(429, "Too Many Requests") to "try again in a minute",
            PluralKitHttpException(500, "Internal Server Error") to "Internal Server Error",
            PluralKitHttpException(429, "Too Many Requests", retryAfter = Duration.ofSeconds(1)) to "try again in 1 second.",
            // Deleted elsewhere since the form loaded.
            PluralKitHttpException(404, "Member not found.", code = 20002) to "isn't in PluralKit anymore",
            // A field the form doesn't have: said plainly rather than lost.
            PluralKitHttpException(400, "Error parsing JSON model", code = 40001, fieldErrors = mapOf("privacy" to listOf("Bad."))) to "Bad.",
        )
        for ((failure, says) in cases) {
            client.editFailure = failure
            val vm = editor()

            vm.save(alex, MemberDraft.of(alex).copy(pronouns = "xe/xem"))

            val error = vm.state.value.saveError
            assertTrue("$failure: $error", error != null && error.contains(says, ignoreCase = true))
            assertFalse("$failure: $error", error!!.contains("PluralKit API returned"))
            assertFalse(vm.state.value.saving)
            assertFalse(vm.state.value.saved)
        }
    }

    @Test
    fun `a rejected token offers sign-out`() = runTest {
        client.editFailure = PluralKitHttpException(401, "Unauthorized")
        val vm = editor()

        vm.save(alex, MemberDraft.of(alex).copy(pronouns = "xe/xem"))

        assertTrue(vm.state.value.unauthorized)
    }

    @Test
    fun `a save under way shows, and a second tap sends nothing more`() = runTest {
        val hold = CompletableDeferred<Unit>()
        client.holdEdit = hold
        val vm = editor()
        val draft = MemberDraft.of(alex).copy(pronouns = "xe/xem")

        vm.save(alex, draft)
        assertTrue(vm.state.value.saving)
        vm.save(alex, draft)
        hold.complete(Unit)

        assertEquals(1, client.edits.size)
        assertTrue(vm.state.value.saved)
        assertFalse(vm.state.value.saving)
    }

    @Test
    fun `once saved, another tap sends nothing more`() = runTest {
        val vm = editor()
        val draft = MemberDraft.of(alex).copy(pronouns = "xe/xem")
        vm.save(alex, draft)

        vm.save(alex, draft)

        assertEquals(1, client.edits.size)
    }

    @Test
    fun `a failed save then a retry clears the old error`() = runTest {
        client.editFailure = IOException("Network unreachable")
        val vm = editor()
        val draft = MemberDraft.of(alex).copy(pronouns = "xe/xem")
        vm.save(alex, draft)

        client.editFailure = null
        vm.save(alex, draft)

        assertNull(vm.state.value.saveError)
        assertTrue(vm.state.value.saved)
    }
}
