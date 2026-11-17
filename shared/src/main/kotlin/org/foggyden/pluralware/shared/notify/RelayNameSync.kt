package org.foggyden.pluralware.shared.notify

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.foggyden.pluralware.shared.model.Member
import org.foggyden.pluralware.shared.model.MemberField

/**
 * Keeps the relay's names current. The relay announces switches with the
 * shared members' names as they were when its config was last uploaded, so a
 * rename or delete made in the app is uploaded too.
 */
class RelayNameSync(
    private val sharing: SharingStore,
    /**
     * Held for every write to the relay. An upload from a config read before
     * the sharing screen changed it, or after Disconnect cleared it, would
     * undo that change.
     */
    private val relayWrites: Mutex,
    /** Uploads [RelayConfig]. One that fails is caught up when the sharing screen next opens. */
    private val upload: suspend (RelaySettings, RelayConfig) -> Unit,
) {
    /**
     * The member [uuid] is gone; [members] gives the system's members as
     * PluralKit has them now. The relay names them from its last upload, so
     * it's given the names again. The sharing config keeps the uuid, which
     * nothing can match again: taking it out would race the correction that
     * reads it.
     */
    suspend fun memberDeleted(uuid: String, members: suspend () -> List<Member>?) = upload(members) { config ->
        uuid in config.sharedMemberUuids
    }

    /**
     * [edited] was saved with [changed]; [members] gives the system's members
     * as PluralKit has them now. Fetched under the lock, so the last upload
     * has every rename before it.
     */
    suspend fun memberEdited(edited: Member, changed: Set<MemberField>, members: suspend () -> List<Member>?) {
        if (MemberField.Name !in changed && MemberField.DisplayName !in changed) return
        upload(members) { config -> edited.uuid in config.sharedMemberUuids }
    }

    /** Uploads the relay's names, if there's a relay and [names] says they need it. */
    private suspend fun upload(members: suspend () -> List<Member>?, names: (SharingConfig) -> Boolean) {
        relayWrites.withLock {
            val config = sharing.configFlow.value
            val relay = config.relay ?: return
            if (relay.signingToken == null || !names(config)) return
            upload(relay, RelayConfig.from(config, members() ?: return) ?: return)
        }
    }
}
