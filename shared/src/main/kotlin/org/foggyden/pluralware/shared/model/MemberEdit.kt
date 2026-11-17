package org.foggyden.pluralware.shared.model

import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** The member fields the phone can edit, with PluralKit's key and length limit for each. */
enum class MemberField(val apiKey: String, val maxLength: Int?) {
    Name("name", 100),
    DisplayName("display_name", 100),
    Pronouns("pronouns", 100),
    Color("color", null), // Six hex digits exactly; see [MemberEdit.validate].
    Description("description", 1000),
    AvatarUrl("avatar_url", 256),
    Birthday("birthday", null), // A date; see [MemberEdit.normalize].
}

/** A member edit form's fields, exactly as typed. */
@Serializable
data class MemberDraft(
    val name: String,
    val displayName: String,
    val pronouns: String,
    val color: String,
    val description: String,
    val avatarUrl: String,
    val birthday: String,
    val proxyTags: List<ProxyTagDraft>,
    val privateParts: Set<MemberPrivacy>,
    val aliases: List<String>,
) {
    operator fun get(field: MemberField): String = when (field) {
        MemberField.Name -> name
        MemberField.DisplayName -> displayName
        MemberField.Pronouns -> pronouns
        MemberField.Color -> color
        MemberField.Description -> description
        MemberField.AvatarUrl -> avatarUrl
        MemberField.Birthday -> birthday
    }

    companion object {
        fun of(member: Member) = MemberDraft(
            name = member.name,
            displayName = member.displayName.orEmpty(),
            pronouns = member.pronouns.orEmpty(),
            color = member.color.orEmpty(),
            description = member.description.orEmpty(),
            avatarUrl = member.avatarUrl.orEmpty(),
            birthday = member.birthday.orEmpty().removePrefix(MemberEdit.NO_YEAR),
            proxyTags = member.proxyTags.map { ProxyTagDraft(it.prefix.orEmpty(), it.suffix.orEmpty()) },
            privateParts = member.privateParts,
            aliases = member.aliases,
        )
    }
}

/** A proxy tag's fields, exactly as typed. */
@Serializable
data class ProxyTagDraft(val prefix: String, val suffix: String)

/**
 * Turns a member edit into what PluralKit is sent. The only place a member
 * update's body is decided: PluralKit leaves a field it isn't sent alone and
 * clears one sent as null, so sending a field by mistake destroys data.
 */
object MemberEdit {

    /**
     * What is sent for [value]: trimmed, blank as cleared (null), colors without `#`, lowercase,
     * and a birthday typed without its year ("03-15") in year 4, as PluralKit keeps one.
     */
    fun normalize(field: MemberField, value: String?): String? {
        val trimmed = value?.trim().orEmpty()
        val stored = when (field) {
            MemberField.Color -> trimmed.removePrefix("#").trim().lowercase()
            MemberField.Birthday -> if (MONTH_DAY.matches(trimmed)) NO_YEAR + trimmed else trimmed
            else -> trimmed
        }
        return stored.ifEmpty { null }
    }

    /** The year PluralKit stores a birthday in when it has none: a leap year, so 02-29 fits. */
    const val NO_YEAR = "0004-"

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
                field == MemberField.Birthday && !isDate(value) -> "A date like 2002-03-15, or 03-15 without the year."
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

    /**
     * All of [draft]'s proxy tags, or null when they're as [original] has them:
     * PluralKit replaces the whole list. Not trimmed, since a tag's spaces are
     * matched too.
     */
    fun proxyTagChanges(original: Member, draft: MemberDraft): List<ProxyTag>? {
        val tags = draft.proxyTags.map { ProxyTag(it.prefix.ifEmpty { null }, it.suffix.ifEmpty { null }) }
        return tags.takeIf { it != original.proxyTags.map { tag -> ProxyTag(tag.prefix?.ifEmpty { null }, tag.suffix?.ifEmpty { null }) } }
    }

    /** Each part whose privacy [draft] changed: true for private, false for public. */
    fun privacyChanges(original: Member, draft: MemberDraft): Map<MemberPrivacy, Boolean> =
        MemberPrivacy.entries.filter { (it in draft.privateParts) != (it in original.privateParts) }
            .associateWith { it in draft.privateParts }

    /**
     * All of [draft]'s aliases, trimmed as PluralKit trims them, or null when
     * they're as [original] has them: PluralKit replaces the whole list.
     */
    fun aliasChanges(original: Member, draft: MemberDraft): List<String>? {
        val aliases = draft.aliases.map { it.trim() }
        return aliases.takeIf { it != original.aliases.map { alias -> alias.trim() } }
    }

    /** Why PluralKit would refuse [draft]'s aliases, if they changed; null if it wouldn't. */
    fun aliasProblem(original: Member, draft: MemberDraft): String? {
        val aliases = aliasChanges(original, draft) ?: return null
        val longest = MemberField.Name.maxLength!!
        return when {
            aliases.any { it.isEmpty() } -> "An alias can't be blank."
            aliases.any { it.length > longest } -> "An alias can be up to $longest characters."
            aliases.size > MAX_ALIASES -> "Up to $MAX_ALIASES aliases."
            // PluralKit compares them ignoring case.
            aliases.map { it.lowercase() }.toSet().size < aliases.size -> "Two aliases are the same."
            else -> null
        }
    }

    private const val MAX_ALIASES = 10

    /** Whether [draft] can be sent: no field [validate] objects to, no [proxyTagProblem], no [aliasProblem]. */
    fun isValid(original: Member, draft: MemberDraft) =
        validate(original, draft).isEmpty() && proxyTagProblem(original, draft) == null && aliasProblem(original, draft) == null

    /** Why PluralKit would refuse [draft]'s proxy tags, if they changed; null if it wouldn't. */
    fun proxyTagProblem(original: Member, draft: MemberDraft): String? {
        val tags = proxyTagChanges(original, draft) ?: return null
        return when {
            tags.any { it.prefix == null && it.suffix == null } -> "Each proxy tag needs a prefix or a suffix."
            tags.any { it.prefix.orEmpty().length + it.suffix.orEmpty().length > MAX_PROXY_TAG } ->
                "A proxy tag's prefix and suffix can be up to $MAX_PROXY_TAG characters together."
            tags.toSet().size < tags.size -> "Two proxy tags are the same."
            else -> null
        }
    }

    // PluralKit takes prefix + "text" + suffix up to 100 characters.
    private const val MAX_PROXY_TAG = 96

    private val HEX_COLOR = Regex("^[0-9a-f]{6}$")
    private val MONTH_DAY = Regex("^\\d{2}-\\d{2}$")
    // The shape PluralKit stores: Java's parser alone also takes a signed year beyond 9999.
    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")

    private fun isDate(value: String) =
        DATE.matches(value) && runCatching { java.time.LocalDate.parse(value) }.isSuccess

    internal fun valueOf(member: Member, field: MemberField): String? = when (field) {
        MemberField.Name -> member.name
        MemberField.DisplayName -> member.displayName
        MemberField.Pronouns -> member.pronouns
        MemberField.Color -> member.color
        MemberField.Description -> member.description
        MemberField.AvatarUrl -> member.avatarUrl
        MemberField.Birthday -> member.birthday
    }
}
