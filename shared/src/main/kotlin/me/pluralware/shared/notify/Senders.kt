package me.pluralware.shared.notify

import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.pluralware.shared.api.userAgent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** What happened to one notification. */
sealed interface SendOutcome {
    data object Delivered : SendOutcome

    /** The subscription no longer exists (404/410); stop sending to it. */
    data object Gone : SendOutcome

    data class Failed(val reason: String) : SendOutcome
}

/** Private mode: one encrypted, VAPID-signed Web Push request (docs/notifications-design.md §4.2). */
class WebPushSender(
    private val http: OkHttpClient,
    private val clock: () -> Instant = Instant::now,
) {
    suspend fun send(friend: Friend.Private, vapid: VapidKeys, payload: SwitchPayload): SendOutcome =
        withContext(Dispatchers.IO) {
            val code = friend.followCode
            val request = Request.Builder()
                .url(code.endpoint)
                .header("Content-Encoding", "aes128gcm")
                .header("TTL", TTL_SECONDS.toString())
                .header("Urgency", "normal")
                .header("Authorization", Vapid.authorization(code.endpoint, vapid, clock()))
                .post(WebPushCrypto.encrypt(code, payload.toBytes()).toRequestBody(OCTET_STREAM))
                .build()
            http.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> SendOutcome.Delivered
                    response.code == 404 || response.code == 410 -> SendOutcome.Gone
                    else -> SendOutcome.Failed("HTTP ${response.code}")
                }
            }
        }

    private companion object {
        /** Twelve hours: an offline friend still gets the latest switch (§4.2). */
        const val TTL_SECONDS = 12 * 60 * 60
        val OCTET_STREAM = "application/octet-stream".toMediaType()
    }
}

/** Simple mode: one JSON publish to an ntfy server (§5.3). */
class NtfySender(private val http: OkHttpClient) {
    suspend fun send(server: NtfyServer, topic: String, title: String, message: String): SendOutcome =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("topic", topic)
                put("title", title)
                put("message", message)
            }.toString()
            val request = Request.Builder()
                .url(server.baseUrl.trimEnd('/') + "/")
                .apply { server.accessToken?.let { header("Authorization", "Bearer $it") } }
                .post(body.toRequestBody(JSON))
                .build()
            http.newCall(request).execute().use { response ->
                if (response.isSuccessful) SendOutcome.Delivered else SendOutcome.Failed("HTTP ${response.code}")
            }
        }

    private companion object {
        val JSON = "application/json".toMediaType()
    }
}

/** The HTTP client both senders share: our User-Agent and short timeouts. */
object NotifyHttp {
    fun create(appVersion: String): OkHttpClient {
        val ua = userAgent(appVersion)
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", ua).build())
            }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}

/** Retry an I/O failure once; anything else is final. */
internal suspend fun withOneRetry(send: suspend () -> SendOutcome): SendOutcome =
    try {
        send()
    } catch (e: IOException) {
        try {
            send()
        } catch (e: IOException) {
            SendOutcome.Failed(e.message ?: "network error")
        }
    }
