package me.pluralware.shared.model

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The member fields the phone can edit, with PluralKit's key and length limit for each. */
enum class MemberField(val apiKey: String, val maxLength: Int?) {
    Name("name", 100),
    DisplayName("display_name", 100),
    Pronouns("pronouns", 100),
    Color("color", null), // Six hex digits exactly; see [MemberEdit.validate].
    Description("description", 1000),
    AvatarUrl("avatar_url", 256),
}

/** A member edit form's fields, exactly as typed. */
data class MemberDraft(
    val name: String,
    val displayName: String,
    val pronouns: String,
    val color: String,
    val description: String,
    val avatarUrl: String,
) {
    operator fun get(field: MemberField): String = when (field) {
        MemberField.Name -> name
        MemberField.DisplayName -> displayName
        MemberField.Pronouns -> pronouns
        MemberField.Color -> color
        MemberField.Description -> description
        MemberField.AvatarUrl -> avatarUrl
    }

    companion object {
        fun of(member: Member) = MemberDraft(
            name = member.name,
            displayName = member.displayName.orEmpty(),
            pronouns = member.pronouns.orEmpty(),
            color = member.color.orEmpty(),
            description = member.description.orEmpty(),
            avatarUrl = member.avatarUrl.orEmpty(),
        )
    }
}

/**
 * Turns a member edit into what PluralKit is sent. The only place a member
 * update's body is decided: PluralKit leaves a field it isn't sent alone and
 * clears one sent as null, so sending a field by mistake destroys data.
 */
object MemberEdit {

    /** What is sent for [value]: trimmed, blank as cleared (null), colors without `#`, lowercase. */
    fun normalize(field: MemberField, value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        val stored = if (field == MemberField.Color) trimmed.removePrefix("#").trim().lowercase() else trimmed
        return stored.ifEmpty { null }
    }

    /**
     * A message per field that would be sent ([changes]) and PluralKit would
     * reject. Fields left as they were aren't checked: PluralKit took them
     * once, and an edit elsewhere mustn't be blocked by them. Empty when the
     * draft can be saved.
     */
    fun validate(original: Member, draft: MemberDraft): Map<MemberField, String> = buildMap {
        for (field in MemberField.entries) {
            val value = normalize(field, draft[field])
            if (value == normalize(field, valueOf(original, field))) continue
            val problem = when {
                value == null -> if (field == MemberField.Name) "A member needs a name." else null
                field == MemberField.Color ->
                    if (!HEX_COLOR.matches(value)) "Six hex digits, like e6a12b." else null
                field.maxLength != null && value.length > field.maxLength -> "Up to ${field.maxLength} characters."
                field == MemberField.AvatarUrl && value.toHttpUrlOrNull() == null ->
                    "A web address starting with https:// or http://."
                else -> null
            }
            if (problem != null) put(field, problem)
        }
    }

    /**
     * The fields to send: only those whose normalized value differs from
     * [original]'s, a cleared field as null. [draft] must be valid.
     */
    fun changes(original: Member, draft: MemberDraft): Map<MemberField, String?> {
        require(validate(original, draft).isEmpty()) { "Only a valid draft can be sent" }
        return buildMap {
            for (field in MemberField.entries) {
                val after = normalize(field, draft[field])
                if (after != normalize(field, valueOf(original, field))) put(field, after)
            }
        }
    }

    private val HEX_COLOR = Regex("^[0-9a-f]{6}$")

    internal fun valueOf(member: Member, field: MemberField): String? = when (field) {
        MemberField.Name -> member.name
        MemberField.DisplayName -> member.displayName
        MemberField.Pronouns -> member.pronouns
        MemberField.Color -> member.color
        MemberField.Description -> member.description
        MemberField.AvatarUrl -> member.avatarUrl
    }
}
