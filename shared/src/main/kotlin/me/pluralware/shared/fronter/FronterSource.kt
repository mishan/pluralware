package me.pluralware.shared.fronter

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.pluralware.shared.api.PluralKitToken
import me.pluralware.shared.handoff.MemberSync
import me.pluralware.shared.handoff.SwitchSync
import me.pluralware.shared.model.Switch
import me.pluralware.shared.repository.PkResult
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.settings.LastFronter
import me.pluralware.shared.settings.LastFronterCache

/**
 * What a glanceable surface shows: a title, the fronter line, its screen-reader
 * text, and when the switch it describes started, if there is one.
 */
data class FronterDisplay(
    val title: String,
    val text: String,
    val description: String,
    val since: Instant? = null,
)

/** What each surface says when there's no token, or PluralKit rejected it: its own words, as the way to fix it differs. */
data class FronterWording(val setup: FronterDisplay, val signInAgain: FronterDisplay)

/**
 * Decides what the complication and the tile show, so the two can't disagree.
 * Free of Android types; [FronterSurfaces] supplies the real token store,
 * client and cache.
 *
 * In order:
 *  - no token → a setup prompt;
 *  - a cached line fetched within [FRESH_FOR_MILLIS] → that, with no network;
 *  - otherwise fetch, cache and show the result. If the fetch fails, show the
 *    cached line titled "Last known" — except for a rejected token, which
 *    clears the cache and asks the user to sign in again.
 *
 * The app keeps the cache fresh by handing over every switch it sees
 * ([remember]), so after a switch the surfaces answer without a fetch of their own.
 * A switch made on the phone [expire]s it instead, so the next request fetches.
 */
class FronterSource(
    private val token: suspend () -> PluralKitToken?,
    private val repositoryFor: (PluralKitToken) -> PluralKitRepository,
    private val cache: LastFronterCache,
    private val wording: FronterWording,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun current(): FronterDisplay {
        val token = token() ?: return wording.setup
        // A line fetched with another token belongs to another system.
        val cached = cache.get()?.takeIf { it.tokenId == token.fingerprint }
        if (cached != null && clock() - cached.fetchedAtEpochMillis in 0 until FRESH_FOR_MILLIS) {
            return cached.toDisplay(lastKnown = false)
        }
        val expiriesBefore = expiries.get()
        return when (val result = repositoryFor(token).refreshFronters()) {
            is PkResult.Success -> {
                val line = lineFor(result.value, token)
                // Cache only if the token didn't change while we were fetching, nor the
                // line expire: the phone's news may postdate what this fetch read.
                if (token() == token) {
                    cacheLock.withLock { if (expiries.get() == expiriesBefore) cache.set(line) }
                }
                line.toDisplay(lastKnown = false)
            }
            is PkResult.Failure -> when {
                result.isUnauthorized -> {
                    cache.clear()
                    wording.signInAgain
                }
                cached != null -> cached.toDisplay(lastKnown = true)
                else -> UNAVAILABLE
            }
        }
    }

    /**
     * Cache [switch], which the app fetched with [fetchedWith], as the current
     * fronters. Ignored if the token has changed since: it describes a system
     * that's no longer paired.
     */
    suspend fun remember(switch: Switch?, fetchedWith: PluralKitToken) {
        if (token() != fetchedWith) return
        cacheLock.withLock { cache.set(lineFor(switch, fetchedWith)) }
    }

    /**
     * The phone reports [notice] as its current switch. If that's news to the
     * cache (a different switch for this system, or nothing known yet), stop
     * answering from it, so the next request fetches; the line stays as the
     * "Last known" fallback. A notice about the switch already cached, such as
     * the phone's echo of one this watch made, changes nothing. Returns whether
     * the surfaces should be asked to update.
     */
    suspend fun expire(notice: SwitchSync.Notice): Boolean {
        val token = token() ?: return false
        val fingerprint = token.fingerprint
        if (notice.tokenFingerprint != fingerprint) return false
        return cacheLock.withLock {
            val cached = cache.get()?.takeIf { it.tokenId == fingerprint }
            when {
                cached == null -> true
                !notice.differsFrom(cached.switchUuid, cached.switchAtEpochMillis, cached.switchMembersHash, fingerprint) -> false
                else -> {
                    expiries.incrementAndGet()
                    cache.set(cached.copy(fetchedAtEpochMillis = 0L))
                    true
                }
            }
        }
    }

    /**
     * The phone reports a member edited for [notice]'s system: the cached
     * line may name them as they were, so stop answering from it (it stays
     * as the "Last known" fallback). Returns whether the surfaces should be
     * asked to update.
     */
    suspend fun expireMembers(notice: MemberSync.Notice): Boolean {
        val fingerprint = token()?.fingerprint ?: return false
        if (notice.tokenFingerprint != fingerprint) return false
        cacheLock.withLock {
            expiries.incrementAndGet()
            cache.get()?.takeIf { it.tokenId == fingerprint }?.let { cache.set(it.copy(fetchedAtEpochMillis = 0L)) }
        }
        return true
    }

    private fun lineFor(switch: Switch?, token: PluralKitToken) = LastFronter(
        text = FronterFormatter.body(switch),
        description = FronterFormatter.contentDescription(switch),
        fetchedAtEpochMillis = clock(),
        tokenId = token.fingerprint,
        switchUuid = switch?.uuid,
        switchAtEpochMillis = switch?.timestamp?.toEpochMilli(),
        switchMembersHash = switch?.let(SwitchSync::membersHash),
    )

    private fun LastFronter.toDisplay(lastKnown: Boolean): FronterDisplay {
        val since = switchAtEpochMillis?.let(Instant::ofEpochMilli)
        return if (lastKnown) {
            FronterDisplay(title = "Last known", text = text, description = "Last known. $description", since = since)
        } else {
            FronterDisplay(title = FronterFormatter.TITLE, text = text, description = description, since = since)
        }
    }

    companion object {
        // Process-wide: each request makes its own FronterSource, and a write
        // must not land between another's read and write (expire's), or an
        // older line could be put back over a newer one.
        private val cacheLock = Mutex()

        // Counts expiries, process-wide like the lock: a fetch that was out
        // while the line expired mustn't cache what it read as fresh.
        private val expiries = AtomicInteger()

        /**
         * How long a cached line is answered without fetching. Shorter than the
         * complication's update period and the tile's freshness interval, so
         * those always fetch; long enough to cover the burst of requests that
         * follows a switch.
         */
        const val FRESH_FOR_MILLIS = 5L * 60L * 1000L

        val UNAVAILABLE = FronterDisplay(
            title = FronterFormatter.TITLE,
            text = "Unavailable",
            description = "Couldn't reach PluralKit.",
        )
    }
}
