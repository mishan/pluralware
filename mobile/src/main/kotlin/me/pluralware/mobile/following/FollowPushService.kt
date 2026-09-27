package me.pluralware.mobile.following

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.pluralware.shared.notify.EncryptedFollowingStore
import me.pluralware.shared.notify.FollowCode
import me.pluralware.shared.notify.SwitchPayload
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * Receives pushes for the systems this person follows, one UnifiedPush
 * registration (instance) per system (docs/notifications-design.md §4.4).
 *
 * The connector decrypts each message with keys it generated and never
 * shares; we only read the result. The callbacks are brief — a small
 * encrypted-prefs write and a notification — so they block rather than
 * outlive the service.
 */
class FollowPushService : PushService() {

    private val store by lazy { EncryptedFollowingStore.get(applicationContext) }

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) = runBlocking(Dispatchers.IO) {
        val keys = endpoint.pubKeySet
        store.update(instance) { follow ->
            if (keys == null) {
                follow.copy(followCode = null, problem = "This push distributor can't receive encrypted messages.")
            } else {
                val code = FollowCode(
                    name = follow.myName,
                    endpoint = endpoint.url,
                    p256dh = keys.pubKey,
                    auth = keys.auth,
                )
                // A changed endpoint means a new code; the old one stops working.
                follow.copy(followCode = code.encode(), problem = null)
            }
        }
    }

    override fun onMessage(message: PushMessage, instance: String) = runBlocking(Dispatchers.IO) {
        // Only ever act on what the connector decrypted: an unencrypted message
        // could come from anyone who learned the endpoint.
        if (!message.decrypted) return@runBlocking
        val payload = SwitchPayload.fromBytes(message.content) ?: return@runBlocking
        if (store.follows.value.none { it.instance == instance }) return@runBlocking
        store.update(instance) { it.copy(lastText = payload.text, lastSwitchedAt = payload.switchedAt) }
        FollowNotifications.show(applicationContext, instance, payload)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) = runBlocking(Dispatchers.IO) {
        val problem = when (reason) {
            FailedReason.NETWORK -> "Your push distributor couldn't reach its server. Try again later."
            FailedReason.ACTION_REQUIRED -> "Your push distributor needs attention. Open it, then try again."
            FailedReason.VAPID_REQUIRED, FailedReason.INTERNAL_ERROR ->
                "Your push distributor couldn't register (${reason.name.lowercase()})."
        }
        store.update(instance) { it.copy(problem = problem) }
    }

    override fun onUnregistered(instance: String) = runBlocking(Dispatchers.IO) {
        store.remove(instance)
        FollowNotifications.cancel(applicationContext, instance)
    }
}
