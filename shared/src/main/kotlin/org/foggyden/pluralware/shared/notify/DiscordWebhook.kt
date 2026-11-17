package org.foggyden.pluralware.shared.notify

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * A Discord channel's webhook (§15): what's taken from a pasted address,
 * and what each switch posts. The relay's `discord.js` does the same, and
 * both are tested against `relay/test/fixtures/announcements.json`.
 */
object DiscordWebhook {

    /** Posts always go to discord.com, rebuilt from the two parts: nothing else of a paste is kept. */
    data class Address(val id: String, val token: String) {
        val url: String get() = "https://discord.com/api/webhooks/$id/$token"
    }

    // The whole address, so a look-alike host, a port, userinfo, a query or extra path can't pass.
    private val ADDRESS = Regex("""^https://(?:discord\.com|discordapp\.com|canary\.discord\.com|ptb\.discord\.com)/api/webhooks/(\d{17,20})/([A-Za-z0-9_-]+)$""")

    fun parse(url: String): Address? =
        ADDRESS.matchEntire(url.trim())?.destructured?.let { (id, token) -> Address(id, token) }

    /**
     * [text], an announcement, as the system titled [title] posts it: names shown as
     * typed rather than as Discord formatting, pinging nobody, without link previews.
     */
    fun payload(title: String, text: String): JsonObject = buildJsonObject {
        put("content", fit(escape(text)))
        poster(title)?.let { put("username", it) }
        putJsonObject("allowed_mentions") { putJsonArray("parse") {} }
        put("flags", SUPPRESS_EMBEDS)
    }

    // "1." starting a line would make a numbered list.
    private fun escape(text: String) = text.replace(MARKDOWN) { "\\" + it.value }.replace(LIST_NUMBER, "$1\\\\.")

    private fun fit(text: String): String {
        if (text.length <= MAX_CONTENT) return text
        val cut = text.take(MAX_CONTENT - 1)
        // Not between the two halves of an emoji.
        return (if (Character.isHighSurrogate(cut.last())) cut.dropLast(1) else cut) + "…"
    }

    /** Discord refuses a poster's name with "discord" or "clyde" in it, or over 80 characters. */
    private fun poster(title: String): String? = title.trim().takeIf { name ->
        name.isNotEmpty() && name.length <= 80 && listOf("discord", "clyde").none { name.contains(it, ignoreCase = true) }
    }

    private val MARKDOWN = Regex("""[\\*_~`|>#\[\]()<-]""")
    private val LIST_NUMBER = Regex("""(?m)^([ \t]*\d+)\.""")
    private const val MAX_CONTENT = 2000
    private const val SUPPRESS_EMBEDS = 4
}
