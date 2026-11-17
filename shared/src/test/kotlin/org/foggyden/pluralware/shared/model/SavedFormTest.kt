package org.foggyden.pluralware.shared.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SavedFormTest {

    private val member = Member(
        id = "abcde",
        uuid = "uuid-alex",
        name = "Alex",
        displayName = "Alex ✨",
        pronouns = "they/them",
        color = "e6a12b",
        avatarUrl = "https://example.com/alex.png",
        description = "Likes tea.\nAnd \"quotes\".",
        birthday = "0004-03-15",
        proxyTags = listOf(ProxyTag("[", "]"), ProxyTag(null, "-a")),
        privateParts = setOf(MemberPrivacy.Name, MemberPrivacy.Visibility),
    )

    @Test
    fun `a member and its form come back as they were saved`() {
        val draft = MemberDraft.of(member).copy(pronouns = "xe/xem", proxyTags = listOf(ProxyTagDraft("A: ", "")))

        assertEquals(member, SavedForm.member(SavedForm.encode(member)))
        assertEquals(draft, SavedForm.draft(SavedForm.encode(draft)))
    }

    @Test
    fun `what can't be read comes back as nothing, rather than as something else`() {
        // Saved by another version, say: the screen then loads the member afresh.
        for (saved in listOf("", "not json", """{"id":"abcde"}""")) {
            assertNull(saved, SavedForm.member(saved))
            assertNull(saved, SavedForm.draft(saved))
        }
    }
}
