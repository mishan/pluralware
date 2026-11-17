package me.pluralware.shared.notify

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Posts to a Discord channel's webhook (§15). The webhook's address is its
 * credential, so no outcome or error carries it.
 */
class DiscordSender(
    private val http: OkHttpClient,
    // Tests point it at a local server; posts otherwise go to discord.com, whatever was pasted.
    private val base: String = "https://discord.com",
) {
    suspend fun send(friend: Friend.Discord, title: String, text: String): SendOutcome =
        post(DiscordWebhook.Address(friend.webhookId, friend.webhookToken), DiscordWebhook.payload(title, text))

    /** A first post: it catches a wrong or deleted webhook before saving. */
    suspend fun test(address: DiscordWebhook.Address, title: String): SendOutcome =
        post(address, DiscordWebhook.payload(title, TEST_MESSAGE))

    private suspend fun post(address: DiscordWebhook.Address, payload: JsonObject): SendOutcome =
        withContext(Dispatchers.IO) {
            val (code, body) = call(address, payload)
            // Discord bars more poster names than it documents: post as the webhook's own name instead.
            if (code == 400 && "\"username\"" in body && "username" in payload) {
                outcome(call(address, JsonObject(payload - "username")).first)
            } else {
                outcome(code)
            }
        }

    private fun call(address: DiscordWebhook.Address, payload: JsonObject): Pair<Int, String> {
        val request = Request.Builder()
            // Without wait, Discord can answer success for a message it didn't save.
            .url("$base/api/webhooks/${address.id}/${address.token}?wait=true")
            .post(payload.toString().toRequestBody(JSON))
            .build()
        return try {
            http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        } catch (e: IOException) {
            // Its message may name the address; the type is enough to say what happened.
            throw IOException("Couldn't reach Discord (${e.javaClass.simpleName})")
        }
    }

    private fun outcome(code: Int): SendOutcome = when (code) {
        in 200..299 -> SendOutcome.Delivered
        // Deleted, or its token reset: Discord asks that it not be tried again.
        404, 401 -> SendOutcome.Gone
        else -> SendOutcome.Failed("HTTP $code")
    }

    private companion object {
        const val TEST_MESSAGE = "PluralWare will post switches here."
        val JSON = "application/json".toMediaType()
    }
}
