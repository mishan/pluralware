package me.pluralware.shared.model

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MemberEditTest {

    private val alex = Member(
        id = "abcde",
        uuid = "uuid-alex",
        name = "Alex",
        displayName = "Alex ✨",
        pronouns = "they/them",
        color = "e6a12b",
        avatarUrl = "https://example.com/alex.png",
        description = "Likes tea.",
    )

    @Test
    fun `normalize stores values the way PluralKit does`() {
        val cases = listOf(
            Triple(MemberField.Name, "  Alex  ", "Alex"),
            Triple(MemberField.DisplayName, "", null),
            Triple(MemberField.Pronouns, "   ", null),
            Triple(MemberField.Pronouns, null, null),
            Triple(MemberField.Color, "#E6A12B", "e6a12b"),
            Triple(MemberField.Color, " E6A12B ", "e6a12b"),
            Triple(MemberField.Color, "#", null),
            Triple(MemberField.Description, "\n Likes tea. \n", "Likes tea."),
            Triple(MemberField.Description, "line one\nline two", "line one\nline two"),
        )
        for ((field, input, expected) in cases) {
            assertEquals("$field ${input?.let { "\"$it\"" }}", expected, MemberEdit.normalize(field, input))
        }
    }

    @Test
    fun `validate names each field PluralKit would reject`() {
        val ok = MemberDraft.of(alex)
        val cases = listOf(
            ok to emptySet<MemberField>(),
            ok.copy(name = "  ") to setOf(MemberField.Name),
            ok.copy(name = "x".repeat(100)) to emptySet<MemberField>(),
            ok.copy(name = "x".repeat(101)) to setOf(MemberField.Name),
            ok.copy(displayName = "x".repeat(101)) to setOf(MemberField.DisplayName),
            ok.copy(pronouns = "x".repeat(101)) to setOf(MemberField.Pronouns),
            ok.copy(description = "x".repeat(1000)) to emptySet<MemberField>(),
            ok.copy(description = "x".repeat(1001)) to setOf(MemberField.Description),
            // Over the limit only before trimming: PluralKit gets the trimmed value.
            ok.copy(pronouns = " " + "x".repeat(100) + " ") to emptySet<MemberField>(),
            ok.copy(color = "#ABCDEF") to emptySet<MemberField>(),
            ok.copy(color = "") to emptySet<MemberField>(),
            ok.copy(color = "abc") to setOf(MemberField.Color),
            ok.copy(color = "ggg000") to setOf(MemberField.Color),
            ok.copy(color = "abcdef0") to setOf(MemberField.Color),
            ok.copy(avatarUrl = "") to emptySet<MemberField>(),
            ok.copy(avatarUrl = "http://example.com/a.png") to emptySet<MemberField>(),
            ok.copy(avatarUrl = "example.com/a.png") to setOf(MemberField.AvatarUrl),
            ok.copy(avatarUrl = "ftp://example.com/a.png") to setOf(MemberField.AvatarUrl),
            ok.copy(avatarUrl = "https://") to setOf(MemberField.AvatarUrl),
            // PluralKit's URL check is .NET's, which takes all of these.
            ok.copy(avatarUrl = "HTTPS://example.com/a.png") to emptySet<MemberField>(),
            ok.copy(avatarUrl = "https://example.com/a b.png") to emptySet<MemberField>(),
            ok.copy(avatarUrl = "https://my_cdn.example.com/a.png") to emptySet<MemberField>(),
            // Length is in UTF-16 units, as PluralKit counts it: this emoji is two.
            ok.copy(name = "x".repeat(98) + "✨") to emptySet<MemberField>(),
            ok.copy(name = "x".repeat(99) + "\uD83C\uDF19") to setOf(MemberField.Name),
            ok.copy(avatarUrl = "https://example.com/" + "a".repeat(236)) to emptySet<MemberField>(),
            ok.copy(avatarUrl = "https://example.com/" + "a".repeat(236) + "b") to setOf(MemberField.AvatarUrl),
            ok.copy(name = "", color = "red") to setOf(MemberField.Name, MemberField.Color),
        )
        for ((draft, invalid) in cases) {
            val errors = MemberEdit.validate(alex, draft)
            assertEquals(draft.toString(), invalid, errors.keys)
            assertTrue("every error has a message", errors.values.all { it.isNotBlank() })
        }
    }

    @Test
    fun `changes holds only what was edited`() {
        val untouched = MemberDraft.of(alex)
        val cases = listOf<Pair<MemberDraft, Map<MemberField, String?>>>(
            untouched to emptyMap(),
            untouched.copy(name = " Alex ") to emptyMap(),
            untouched.copy(color = "#E6A12B") to emptyMap(),
            untouched.copy(pronouns = "she/her") to mapOf(MemberField.Pronouns to "she/her"),
            untouched.copy(pronouns = " she/her ") to mapOf(MemberField.Pronouns to "she/her"),
            untouched.copy(displayName = "") to mapOf(MemberField.DisplayName to null),
            untouched.copy(description = "   ") to mapOf(MemberField.Description to null),
            untouched.copy(color = "#FF0000", avatarUrl = "") to
                mapOf(MemberField.Color to "ff0000", MemberField.AvatarUrl to null),
        )
        for ((draft, expected) in cases) {
            assertEquals(draft.toString(), expected, MemberEdit.changes(alex, draft))
        }
    }

    @Test
    fun `a stored value PluralKit took but the phone wouldn't doesn't block other edits`() {
        val stored = alex.copy(avatarUrl = "not a url", description = "x".repeat(1200))
        val draft = MemberDraft.of(stored).copy(pronouns = "she/her")

        assertEquals(emptyMap<MemberField, String>(), MemberEdit.validate(stored, draft))
        assertEquals(mapOf(MemberField.Pronouns to "she/her"), MemberEdit.changes(stored, draft))
    }

    @Test
    fun `what PluralKit returns unnormalized isn't taken for an edit`() {
        val fromPluralKit = alex.copy(color = "E6A12B", description = "Likes tea.  ", pronouns = "")

        assertEquals(emptyMap<MemberField, String?>(), MemberEdit.changes(fromPluralKit, MemberDraft.of(fromPluralKit)))
    }

    @Test
    fun `an invalid draft is refused rather than sent`() {
        assertThrows(IllegalArgumentException::class.java) {
            MemberEdit.changes(alex, MemberDraft.of(alex).copy(name = ""))
        }
    }

    @Test
    fun `for any member and any edit, only edited fields are sent, and sending them gives the edit`() {
        val random = Random(20261113)
        val seen = mutableMapOf<String, Int>().withDefault { 0 }
        fun count(what: String) { seen[what] = seen.getValue(what) + 1 }
        repeat(2_000) { run ->
            val original = randomMember(random)
            val draft = randomEdit(random, original)
            if (MemberEdit.validate(original, draft).isNotEmpty()) return@repeat
            count("valid")

            val changes = MemberEdit.changes(original, draft)

            // What PluralKit holds afterwards: the sent fields replaced, the rest as they were.
            for (field in MemberField.entries) {
                val stored = MemberEdit.valueOf(original, field)
                val afterSave = if (field in changes) changes.getValue(field) else stored
                assertEquals("run $run: $field", MemberEdit.normalize(field, draft[field]),
                    MemberEdit.normalize(field, afterSave))
                when {
                    field !in changes -> count("$field untouched")
                    changes.getValue(field) == null -> count("$field cleared")
                    else -> count("$field edited")
                }
                if (field !in changes) assertEquals("run $run: $field", stored, afterSave)
            }
            assertEquals("run $run: an untouched form sends nothing", emptyMap<MemberField, String?>(),
                MemberEdit.changes(original, MemberDraft.of(original)))
        }
        // Prove the generator reaches every case, so a change to it can't quietly stop testing one.
        assertTrue("valid runs: ${seen.getValue("valid")}", seen.getValue("valid") >= 500)
        for (field in MemberField.entries) {
            assertTrue("$field untouched", seen.getValue("$field untouched") >= 50)
            assertTrue("$field edited", seen.getValue("$field edited") >= 50)
            if (field != MemberField.Name) assertTrue("$field cleared", seen.getValue("$field cleared") >= 20)
        }
    }

    private val pieces = listOf(
        "", " ", "  ", "\n", "\t", "\u00A0", "#", "A", "a", "é", "✨", "\uD83C\uDF19", "x y", "E6A12B", "e6a12b", "Alex",
        "https://example.com/p.png",
    )

    private fun randomText(random: Random): String? =
        if (random.nextInt(6) == 0) null else List(random.nextInt(4)) { pieces.random(random) }.joinToString("")

    private fun randomColor(random: Random): String? = when (random.nextInt(4)) {
        0 -> null
        1 -> ""
        else -> (if (random.nextBoolean()) "#" else "") +
            List(6) { "0123456789abcdefABCDEF".random(random) }.joinToString("") +
            (if (random.nextBoolean()) " " else "")
    }

    private fun randomMember(random: Random) = Member(
        id = "abcde",
        uuid = "uuid",
        name = randomText(random)?.takeIf { it.isNotBlank() } ?: "Alex",
        displayName = randomText(random),
        pronouns = randomText(random),
        color = randomColor(random)?.removePrefix("#")?.trim()?.takeIf { it.isNotEmpty() },
        // Sometimes a value PluralKit took that the phone's checks wouldn't: the edit must still go through.
        avatarUrl = listOf("https://example.com/a.png", null, "not a url").random(random),
        description = if (random.nextInt(10) == 0) "x".repeat(1200) else randomText(random),
    )

    /** Each field kept as is, re-typed with case or whitespace changes, or replaced. */
    private fun randomEdit(random: Random, member: Member): MemberDraft {
        fun keepOr(current: String, replacement: () -> String?): String = when (random.nextInt(3)) {
            0 -> current
            1 -> (if (random.nextBoolean()) " " else "") + current + (if (random.nextBoolean()) "\n" else "")
            else -> replacement().orEmpty()
        }
        val draft = MemberDraft.of(member)
        return MemberDraft(
            name = keepOr(draft.name) { randomText(random) },
            displayName = keepOr(draft.displayName) { randomText(random) },
            pronouns = keepOr(draft.pronouns) { randomText(random) },
            color = keepOr(draft.color.let { if (random.nextBoolean()) it.uppercase() else it }) { randomColor(random) },
            description = keepOr(draft.description) { randomText(random) },
            avatarUrl = keepOr(draft.avatarUrl) { if (random.nextBoolean()) "https://example.com/b.png" else "" },
        )
    }
}
