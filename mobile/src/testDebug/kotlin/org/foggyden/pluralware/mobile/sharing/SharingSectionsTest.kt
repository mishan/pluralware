package org.foggyden.pluralware.mobile.sharing

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.foggyden.pluralware.mobile.ui.theme.PluralWareTheme
import org.foggyden.pluralware.shared.notify.Friend
import org.foggyden.pluralware.shared.notify.SharingConfig
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharingSectionsTest {
    @get:Rule val compose = createComposeRule()

    private val server = Friend.Discord("d1", "Server", "123456789012345678", "tok_en")
    private val kit = Friend.Simple("s1", "Kit", "pw_kit")

    @Test
    fun `a Discord channel shows its name, never its link, and why it stopped`() {
        compose.setContent {
            PluralWareTheme {
                FriendsSection(
                    SharingConfig(friends = listOf(server, kit)),
                    gone = setOf("d1"),
                    failures = mapOf("s1" to "HTTP 500"),
                    onRemove = {},
                )
            }
        }

        compose.onNodeWithText("Server").assertExists()
        compose.onNodeWithText("Discord webhook").assertExists()
        compose.onNodeWithText("tok_en", substring = true).assertDoesNotExist()
        compose.onNodeWithText("Discord deleted or reset this webhook", substring = true).assertExists()
        compose.onNodeWithText("The last send failed: HTTP 500").assertExists()
    }

    @Test
    fun `adding a Discord channel hands over the link and name`() {
        val added = mutableListOf<Pair<String, String>>()
        compose.setContent {
            PluralWareTheme { DiscordSection(error = null, testing = false, onAdd = { url, label, _ -> added += url to label }) }
        }

        compose.onNode(hasSetTextAction() and hasText("Name", substring = true)).performTextReplacement("Server")
        compose.onNode(hasSetTextAction() and hasText("Webhook link")).performTextReplacement("https://discord.com/api/webhooks/1/t")
        compose.onNodeWithText("Post a test message and add").performClick()

        assertEquals(listOf("https://discord.com/api/webhooks/1/t" to "Server"), added)
    }

    @Test
    fun `while the test post is out, adding again waits`() {
        compose.setContent {
            PluralWareTheme { DiscordSection(error = null, testing = true, onAdd = { _, _, _ -> }) }
        }

        compose.onNode(hasSetTextAction() and hasText("Webhook link")).performTextReplacement("https://discord.com/api/webhooks/1/t")

        compose.onNodeWithText("Posting a test message…").assertIsNotEnabled()
    }
}
