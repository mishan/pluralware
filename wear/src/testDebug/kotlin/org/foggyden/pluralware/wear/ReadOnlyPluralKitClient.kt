package org.foggyden.pluralware.wear

import java.time.Instant
import org.foggyden.pluralware.shared.api.PluralKitClient
import org.foggyden.pluralware.shared.model.Member
import org.foggyden.pluralware.shared.model.MemberField
import org.foggyden.pluralware.shared.model.MemberPrivacy
import org.foggyden.pluralware.shared.model.ProxyTag
import org.foggyden.pluralware.shared.model.Switch

/** A test client for the watch's screens, which read but never edit; an edit fails the test. */
abstract class ReadOnlyPluralKitClient : PluralKitClient {
    override suspend fun updateMember(
        uuid: String,
        changes: Map<MemberField, String?>,
        proxyTags: List<ProxyTag>?,
        privacy: Map<MemberPrivacy, Boolean>,
        aliases: List<String>?,
    ): Member = noEdits()
    override suspend fun createMember(name: String): Member = noEdits()
    override suspend fun deleteMember(uuid: String): Unit = noEdits()
    override suspend fun moveSwitch(uuid: String, to: Instant): Switch = noEdits()
    override suspend fun setSwitchMembers(uuid: String, memberUuids: List<String>): Switch = noEdits()
    override suspend fun deleteSwitch(uuid: String): Unit = noEdits()

    private fun noEdits(): Nothing = error("The watch doesn't edit")
}
