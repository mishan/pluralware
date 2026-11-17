package org.foggyden.pluralware.shared.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/**
 * Members and member edit forms as text, for a screen's saved state. Every
 * field is included as the classes have it, so a new one can't be left out.
 * What can't be read (saved by another version, say) comes back as null.
 */
object SavedForm {
    fun encode(member: Member): String = Json.encodeToString(Member.serializer(), member)
    fun member(saved: String): Member? = decode(Member.serializer(), saved)
    fun encode(draft: MemberDraft): String = Json.encodeToString(MemberDraft.serializer(), draft)
    fun draft(saved: String): MemberDraft? = decode(MemberDraft.serializer(), saved)

    private fun <T> decode(serializer: KSerializer<T>, saved: String): T? =
        runCatching { Json.decodeFromString(serializer, saved) }.getOrNull()
}
