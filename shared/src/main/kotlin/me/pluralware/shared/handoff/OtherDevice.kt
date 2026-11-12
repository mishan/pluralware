package me.pluralware.shared.handoff

import android.content.Context
import com.google.android.gms.wearable.Wearable
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.tasks.await

/**
 * Picks the other device's DataItems out of a listener's changes. Each device
 * writes its own items, and its own changes come back to its listener too.
 */
object OtherDevice {

    /**
     * The payloads of [items] (each with its source node) that came from the
     * other device. If this device's node can't be looked up, all are kept:
     * for callers that cope with seeing their own state again.
     */
    suspend fun <T> payloads(context: Context, items: List<Pair<String?, T>>): List<T> =
        payloads(localNode(context), items)

    /**
     * As [payloads], but none when this device's node can't be looked up: for
     * state a device mustn't mistake its own for the other's.
     */
    suspend fun <T> knownPayloads(context: Context, items: List<Pair<String?, T>>): List<T> =
        localNode(context)?.let { payloads(it, items) }.orEmpty()

    internal fun <T> payloads(localNode: String?, items: List<Pair<String?, T>>): List<T> =
        items.filter { (node, _) -> localNode == null || node != localNode }.map { it.second }

    // A device's node id doesn't change while the app runs.
    @Volatile private var localNodeId: String? = null

    private suspend fun localNode(context: Context): String? =
        localNodeId ?: try {
            Wearable.getNodeClient(context).localNode.await().id.also { localNodeId = it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
}
