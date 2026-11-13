package me.pluralware.wear

import java.time.Instant
import me.pluralware.shared.api.PluralKitClient
import me.pluralware.shared.model.Member
import me.pluralware.shared.model.MemberField
import me.pluralware.shared.model.Switch

/** A test client for the watch's screens, which read but never edit; an edit fails the test. */
abstract class ReadOnlyPluralKitClient : PluralKitClient {
    override suspend fun updateMember(uuid: String, changes: Map<MemberField, String?>): Member = noEdits()
    override suspend fun moveSwitch(uuid: String, to: Instant): Switch = noEdits()
    override suspend fun setSwitchMembers(uuid: String, memberUuids: List<String>): Switch = noEdits()
    override suspend fun deleteSwitch(uuid: String): Unit = noEdits()

    private fun noEdits(): Nothing = error("The watch doesn't edit")
}
