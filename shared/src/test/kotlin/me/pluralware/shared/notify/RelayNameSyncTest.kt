package me.pluralware.shared.notify

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import me.pluralware.shared.model.Member
import me.pluralware.shared.model.MemberField
import me.pluralware.shared.preview.PreviewData
import org.junit.Assert.assertEquals
import org.junit.Test

class RelayNameSyncTest {

    private val alex = PreviewData.members[0]
    private val bea = PreviewData.members[1]
    private val relay = RelaySettings(url = "https://relay.example", adminSecret = "admin", signingToken = "pk-token", enabled = true)
    private val sharingAlex = SharingConfig(
        sharedMemberUuids = setOf(alex.uuid),
        friends = listOf(Friend.Simple("f1", "Kit", "pw_kit")),
        relay = relay,
    )
    private val store = InMemorySharingStore(sharingAlex)
    private val relayWrites = Mutex()

    private val uploads = mutableListOf<Map<String, String>>()
    private var uploadGate: CompletableDeferred<Unit>? = null
    private var memberFetches = 0
    private var current = PreviewData.members

    private val sync = RelayNameSync(store, relayWrites) { _, uploaded ->
        uploadGate?.await()
        uploads += uploaded.names
    }

    private val members: suspend () -> List<Member> = {
        memberFetches++
        current
    }

    private fun save(edited: Member) {
        current = current.map { if (it.uuid == edited.uuid) edited else it }
    }

    private suspend fun rename(edited: Member, vararg fields: MemberField) {
        save(edited)
        sync.memberEdited(edited, fields.toSet(), members)
    }

    @Test
    fun `renaming a shared member uploads the relay's names`() = runTest {
        rename(alex.copy(displayName = "Lex"), MemberField.DisplayName)

        assertEquals(listOf(mapOf(alex.uuid to "Lex")), uploads)
    }

    @Test
    fun `an edit that can't change what the relay says uploads nothing`() = runTest {
        val cases = listOf(
            "an edit that leaves the names alone" to (sharingAlex to (alex.copy(pronouns = "xe/xem") to MemberField.Pronouns)),
            "a member who isn't shared" to (sharingAlex to (bea.copy(name = "Bee") to MemberField.Name)),
            "no relay" to (sharingAlex.copy(relay = null) to (alex.copy(name = "Lex") to MemberField.Name)),
            "a relay PluralKit hasn't given a signing token" to
                (sharingAlex.copy(relay = relay.copy(signingToken = null)) to (alex.copy(name = "Lex") to MemberField.Name)),
        )
        for ((what, case) in cases) {
            val (config, edit) = case
            store.set(config)
            rename(edit.first, edit.second)
            assertEquals(what, emptyList<Map<String, String>>(), uploads)
        }
        assertEquals("not even a member fetch", 0, memberFetches)
    }

    @Test
    fun `members that can't be fetched upload nothing`() = runTest {
        sync.memberEdited(alex.copy(name = "Lex"), setOf(MemberField.Name)) { null }

        assertEquals(emptyList<Map<String, String>>(), uploads)
    }

    @Test
    fun `an upload waits for other relay writes and goes by the config they leave`() = runTest {
        val cases = listOf(
            "the relay was let go" to sharingAlex.copy(relay = null),
            "the member stopped being shared" to sharingAlex.copy(sharedMemberUuids = setOf(bea.uuid)),
        )
        for ((what, after) in cases) {
            store.set(sharingAlex)
            relayWrites.lock()
            launch { rename(alex.copy(name = "Lex"), MemberField.Name) }
            runCurrent()

            store.set(after)
            relayWrites.unlock()
            runCurrent()

            assertEquals(what, emptyList<Map<String, String>>(), uploads)
        }
    }

    @Test
    fun `quick renames fetch members one at a time, each after the upload before it`() = runTest {
        val sharingBoth = sharingAlex.copy(sharedMemberUuids = setOf(alex.uuid, bea.uuid))
        store.set(sharingBoth)
        uploadGate = CompletableDeferred()

        launch { rename(alex.copy(name = "Lex"), MemberField.Name) }
        runCurrent()
        launch { rename(bea.copy(name = "Bee"), MemberField.Name) }
        runCurrent()
        assertEquals("the second waits to fetch", 1, memberFetches)

        uploadGate!!.complete(Unit)
        runCurrent()

        assertEquals(2, memberFetches)
    }
}
