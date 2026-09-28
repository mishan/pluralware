package me.pluralware.shared.notify

import java.security.GeneralSecurityException
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import me.pluralware.shared.model.Switch

/**
 * Tells every friend about a switch, each in their own mode, in parallel
 * (docs/notifications-design.md §7). Never throws: every friend gets an
 * outcome, and the caller records the [SendOutcome.Gone] ones.
 */
class SwitchSharer(
    private val webPush: WebPushSender,
    private val ntfy: NtfySender,
) {
    suspend fun announce(
        config: SharingConfig,
        switch: Switch,
        skipFriendIds: Set<String> = emptySet(),
    ): Map<Friend, SendOutcome> = coroutineScope {
        val payload = SwitchPayload.of(config.title, switch, config.sharedMemberUuids)
        config.friends
            .filter { it.id !in skipFriendIds }
            .map { friend -> async { friend to sendTo(friend, config, payload) } }
            .awaitAll()
            .toMap()
    }

    private suspend fun sendTo(friend: Friend, config: SharingConfig, payload: SwitchPayload): SendOutcome =
        try {
            when (friend) {
                is Friend.Private -> {
                    val vapid = config.vapid ?: return SendOutcome.Failed("no VAPID key")
                    withOneRetry { webPush.send(friend, vapid, payload) }
                }
                is Friend.Simple -> {
                    val server = config.ntfy ?: return SendOutcome.Failed("no ntfy server")
                    withOneRetry { ntfy.send(server, friend.topic, payload.system, payload.text) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GeneralSecurityException) {
            SendOutcome.Failed("encryption: ${e.message}")
        } catch (e: Exception) {
            // Anything else about one friend (a malformed follow code, an
            // endpoint OkHttp or URI rejects) fails that friend alone; it must
            // never take the others, or the app, down with it.
            SendOutcome.Failed(e.message ?: e.javaClass.simpleName)
        }

    companion object {
        /** A sharer over one HTTP client carrying the app's User-Agent. */
        fun create(appVersion: String): SwitchSharer {
            val http = NotifyHttp.create(appVersion)
            return SwitchSharer(WebPushSender(http), NtfySender(http))
        }
    }
}
