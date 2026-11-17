package me.pluralware.shared.notify

import java.io.File
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.pluralware.shared.model.Member
import me.pluralware.shared.model.Switch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The cases in the fixture file the relay's tests read too, so the two can't word things differently. */
class DiscordWebhookTest {

    private val fixtures = Json.parseToJsonElement(File(System.getProperty("pluralware.announcementFixtures")).readText()).jsonObject

    private class Case(val name: String, val title: String, val switch: Switch, val shared: Set<String>, val text: String, val discord: JsonObject)

    private fun cases(key: String) = fixtures.getValue(key).jsonArray.map { it.jsonObject }.map { case ->
        val front = case.getValue("front").jsonArray.map { it.jsonObject }
        Case(
            name = case.getValue("case").jsonPrimitive.content,
            title = case.getValue("title").jsonPrimitive.content,
            switch = Switch("s", Instant.EPOCH, front.map { m ->
                val uuid = m.getValue("uuid").jsonPrimitive.content
                Member(uuid, uuid, m.getValue("label").jsonPrimitive.content, null, null, null, null)
            }),
            shared = front.filter { it.getValue("shared").jsonPrimitive.boolean }.mapTo(HashSet()) { it.getValue("uuid").jsonPrimitive.content },
            text = case.getValue("text").jsonPrimitive.content,
            discord = case.getValue("discord").jsonObject,
        )
    }

    @Test
    fun `each switch is worded, and posted to Discord, as the fixtures say`() {
        for (case in cases("announcements")) {
            assertEquals(case.name, case.text, SwitchAnnouncement.text(case.switch, case.shared))
            assertEquals(case.name, case.discord, DiscordWebhook.payload(case.title, case.text))
        }
        for (case in cases("corrections")) {
            assertEquals(case.name, case.text, SwitchAnnouncement.correction(case.switch, case.shared))
            assertEquals(case.name, case.discord, DiscordWebhook.payload(case.title, case.text))
        }
    }

    @Test
    fun `only a Discord webhook's address is taken, and it's posted to as discord-dot-com`() {
        val webhooks = fixtures.getValue("webhooks").jsonObject
        for (accepted in webhooks.getValue("accepted").jsonArray.map { it.jsonObject }) {
            val url = accepted.getValue("url").jsonPrimitive.content
            val id = accepted.getValue("id").jsonPrimitive.content
            val token = accepted.getValue("token").jsonPrimitive.content
            val address = DiscordWebhook.parse(url)
            assertEquals(url, DiscordWebhook.Address(id, token), address)
            assertEquals("https://discord.com/api/webhooks/$id/$token", address!!.url)
        }
        for (refused in webhooks.getValue("refused").jsonArray.map { it.jsonPrimitive.content }) {
            assertNull(refused, DiscordWebhook.parse(refused))
        }
    }

    @Test
    fun `a message longer than Discord takes is cut to fit, as escaped, never mid-emoji`() {
        val cases = listOf(
            "*".repeat(1200) to "escaped, it's 2,400",
            "x".repeat(1998) + "\uD83C\uDF19".repeat(10) to "the cut falls inside an emoji",
        )
        for ((text, what) in cases) {
            val content = DiscordWebhook.payload("Sample", text).getValue("content").jsonPrimitive.content
            assertTrue(what, content.length <= 2000)
            assertTrue(what, content.endsWith("…"))
            assertTrue(what, !Character.isHighSurrogate(content[content.length - 2]))
        }
    }
}
