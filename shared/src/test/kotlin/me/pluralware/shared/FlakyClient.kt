package me.pluralware.shared

import java.io.IOException
import java.time.Instant
import kotlinx.coroutines.CompletableDeferred
import me.pluralware.shared.api.PluralKitClient
import me.pluralware.shared.api.PluralKitHttpException
import me.pluralware.shared.mock.MockPluralKitClient
import me.pluralware.shared.model.Member
import me.pluralware.shared.model.MemberField
import me.pluralware.shared.model.MemberPrivacy
import me.pluralware.shared.model.ProxyTag
import me.pluralware.shared.model.Switch

/** The mock PluralKit, plus the failures a phone on a real network meets. */
internal class FlakyClient(val pk: MockPluralKitClient = MockPluralKitClient(artificialLatencyMillis = 0)) :
    PluralKitClient by pk {
    /** The next write is applied, then its response is lost. */
    var loseNextResponse = false

    /** What a lost response looks like: a dropped connection, or a proxy's error. */
    var lostResponse: Exception = IOException("Connection reset")

    /** After each write, this many member list fetches fail, with [memberFetchError]. */
    var failMemberFetchesAfterWrite = 0
    var memberFetchFailures = 0
    var memberFetchError: Exception = IOException("Network unreachable")

    val creates = mutableListOf<String>()

    /** The next write is refused with this, and not applied. */
    var refuseNextWrite: Exception? = null

    /** The next write waits for this before reaching PluralKit. */
    var holdNextWrite: CompletableDeferred<Unit>? = null

    /** After each write, this many fronters fetches fail. */
    var failFetchesAfterWrite = 0

    /** Run during each fronters fetch, as if something happened meanwhile. */
    var duringFronterFetch: suspend () -> Unit = {}

    /** Every fronters fetch fails with this while it's set. */
    var fronterFetchError: Exception? = null
    var recentSwitchesFail = false

    /** PluralKit's refusals of the writes it was sent, by code. */
    val refusals = mutableListOf<Int?>()
    var fronterFetchFailures = 0
    var fronterFetches = 0
    var now: () -> Long = { 0 }
    val fetchTimes = mutableListOf<Long>()

    /** When set, a fronters fetch reads PluralKit's state, then waits for this before answering. */
    var holdFronterFetch: CompletableDeferred<Unit>? = null
    var holdMemberFetch: CompletableDeferred<Unit>? = null

    private suspend fun <T> write(apply: suspend () -> T): T {
        holdNextWrite?.also { holdNextWrite = null }?.await()
        refuseNextWrite?.let {
            refuseNextWrite = null
            throw it
        }
        val result = try {
            apply()
        } catch (e: PluralKitHttpException) {
            refusals += e.code
            throw e
        }
        fronterFetchFailures += failFetchesAfterWrite
        memberFetchFailures += failMemberFetchesAfterWrite
        return lost(result)
    }

    private fun <T> lost(result: T): T {
        if (loseNextResponse) {
            loseNextResponse = false
            throw lostResponse
        }
        return result
    }

    override suspend fun updateMember(
        uuid: String,
        changes: Map<MemberField, String?>,
        proxyTags: List<ProxyTag>?,
        privacy: Map<MemberPrivacy, Boolean>,
    ) =
        write { pk.updateMember(uuid, changes, proxyTags, privacy) }
    override suspend fun moveSwitch(uuid: String, to: Instant) = write { pk.moveSwitch(uuid, to) }
    override suspend fun setSwitchMembers(uuid: String, memberUuids: List<String>) =
        write { pk.setSwitchMembers(uuid, memberUuids) }
    override suspend fun deleteSwitch(uuid: String) = write { pk.deleteSwitch(uuid) }
    override suspend fun createMember(name: String) = write {
        creates += name
        pk.createMember(name)
    }
    override suspend fun deleteMember(uuid: String) = write { pk.deleteMember(uuid) }

    override suspend fun getCurrentFronters(): Switch? {
        fronterFetches++
        fetchTimes += now()
        fronterFetchError?.let { throw it }
        if (fronterFetchFailures > 0) {
            fronterFetchFailures--
            throw IOException("Network unreachable")
        }
        val answer = pk.getCurrentFronters()
        duringFronterFetch()
        holdFronterFetch?.also { holdFronterFetch = null }?.await()
        return answer
    }

    override suspend fun getRecentSwitches(limit: Int): List<Switch> {
        if (recentSwitchesFail) throw IOException("Network unreachable")
        return pk.getRecentSwitches(limit)
    }

    override suspend fun getOwnMembers(): List<Member> {
        if (memberFetchFailures > 0) {
            memberFetchFailures--
            throw memberFetchError
        }
        val answer = pk.getOwnMembers()
        holdMemberFetch?.also { holdMemberFetch = null }?.await()
        return answer
    }
}
