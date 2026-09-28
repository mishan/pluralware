package me.pluralware.shared.notify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.pluralware.shared.model.Member
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * What the phone uploads to the relay (docs/notifications-design.md §10):
 * everything it needs to turn PluralKit's switch events into notifications,
 * and nothing that can act on the system. There's no PluralKit token, only
 * display labels for the members the user chose to share, keyed by UUID
 * (the form PluralKit's `CREATE_SWITCH` events use).
 */
@Serializable
data class RelayConfig(
    val enabled: Boolean,
    val webhookPath: String,
    val signingToken: String,
    val title: String,
    val names: Map<String, String>,
    val friends: List<Friend>,
    val vapid: VapidKeys? = null,
    val ntfy: NtfyServer? = null,
    val v: Int = 1,
) {
    companion object {
        /** Null until the relay has everything it needs: a signing token from PluralKit. */
        fun from(config: SharingConfig, members: List<Member>): RelayConfig? {
            val relay = config.relay ?: return null
            val token = relay.signingToken ?: return null
            return RelayConfig(
                enabled = relay.enabled,
                webhookPath = relay.webhookPath,
                signingToken = token,
                title = config.title,
                names = members
                    .filter { it.uuid in config.sharedMemberUuids }
                    .associate { it.uuid to it.displayLabel },
                friends = config.friends,
                vapid = config.vapid,
                ntfy = config.ntfy,
            )
        }
    }
}

/** The relay config's wire form; relay/test/config-interop.test.mjs reads the same bytes. */
internal object RelayJson {
    private val json = Json { ignoreUnknownKeys = true }
    fun encode(config: RelayConfig): String = json.encodeToString(RelayConfig.serializer(), config)
}

/** What the relay reports: whether it's set up, and friends whose subscriptions are gone. */
@Serializable
data class RelayStatus(
    val configured: Boolean = false,
    val enabled: Boolean = false,
    val gone: List<String> = emptyList(),
)

/** The phone's side of the relay's admin API: `PUT`/`DELETE /config`, `GET /status`. */
class RelayClient(private val http: OkHttpClient) {

    /** Thrown with the relay's own explanation when it refuses a request. */
    class RelayException(message: String) : Exception(message)

    suspend fun upload(settings: RelaySettings, config: RelayConfig) = call(
        Request.Builder()
            .url(endpoint(settings, "config"))
            .header("Authorization", "Bearer ${settings.adminSecret}")
            .put(RelayJson.encode(config).toRequestBody(JSON))
            .build(),
    )

    suspend fun clear(settings: RelaySettings) = call(
        Request.Builder()
            .url(endpoint(settings, "config"))
            .header("Authorization", "Bearer ${settings.adminSecret}")
            .delete()
            .build(),
    )

    suspend fun status(settings: RelaySettings): RelayStatus {
        val body = call(
            Request.Builder()
                .url(endpoint(settings, "status"))
                .header("Authorization", "Bearer ${settings.adminSecret}")
                .get()
                .build(),
        )
        return json.decodeFromString(RelayStatus.serializer(), body)
    }

    private fun endpoint(settings: RelaySettings, path: String) = settings.url.trimEnd('/') + "/" + path

    private suspend fun call(request: Request): String = withContext(Dispatchers.IO) {
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val reason = runCatching {
                    json.decodeFromString(ErrorBody.serializer(), body).error
                }.getOrNull()
                throw RelayException(
                    when (response.code) {
                        401 -> "The relay didn't accept its admin secret."
                        else -> "The relay said: ${reason ?: "HTTP ${response.code}"}"
                    },
                )
            }
            body
        }
    }

    @Serializable
    private data class ErrorBody(val error: String? = null)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val JSON = "application/json".toMediaType()

        fun create(appVersion: String) = RelayClient(NotifyHttp.create(appVersion))
    }
}

