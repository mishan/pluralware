package me.pluralware.wear.complication

import java.security.MessageDigest
import me.pluralware.shared.api.PluralKitToken
import me.pluralware.shared.model.Switch
import me.pluralware.shared.repository.PkResult
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.settings.LastFronter
import me.pluralware.shared.settings.LastFronterCache

/** What a glanceable surface shows: a title, the fronter line, and its screen-reader text. */
internal data class FronterDisplay(
    val title: String,
    val text: String,
    val description: String,
)

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
 */
internal class FronterSource(
    private val token: suspend () -> PluralKitToken?,
    private val repositoryFor: (PluralKitToken) -> PluralKitRepository,
    private val cache: LastFronterCache,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun current(): FronterDisplay {
        val token = token() ?: return SETUP
        // A line fetched with another token belongs to another system.
        val cached = cache.get()?.takeIf { it.tokenId == idOf(token) }
        if (cached != null && clock() - cached.fetchedAtEpochMillis in 0 until FRESH_FOR_MILLIS) {
            return cached.toDisplay(lastKnown = false)
        }
        return when (val result = repositoryFor(token).refreshFronters()) {
            is PkResult.Success -> {
                val line = lineFor(result.value, token)
                // Cache only if the token didn't change while we were fetching.
                if (token() == token) cache.set(line)
                line.toDisplay(lastKnown = false)
            }
            is PkResult.Failure -> when {
                result.isUnauthorized -> {
                    cache.clear()
                    SIGN_IN_AGAIN
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
        cache.set(lineFor(switch, fetchedWith))
    }

    private fun lineFor(switch: Switch?, token: PluralKitToken) = LastFronter(
        text = FronterComplicationFormatter.body(switch),
        description = FronterComplicationFormatter.contentDescription(switch),
        fetchedAtEpochMillis = clock(),
        tokenId = idOf(token),
    )

    private fun LastFronter.toDisplay(lastKnown: Boolean) = if (lastKnown) {
        FronterDisplay(title = "Last known", text = text, description = "Last known. $description")
    } else {
        FronterDisplay(title = FronterComplicationFormatter.TITLE, text = text, description = description)
    }

    companion object {
        /** A token's cache key: a truncated SHA-256, so the token itself is never stored here. */
        fun idOf(token: PluralKitToken): String =
            MessageDigest.getInstance("SHA-256").digest(token.raw.toByteArray())
                .take(16).joinToString("") { "%02x".format(it) }

        /**
         * How long a cached line is answered without fetching. Shorter than the
         * complication's update period and the tile's freshness interval, so
         * those always fetch; long enough to cover the burst of requests that
         * follows a switch.
         */
        const val FRESH_FOR_MILLIS = 5L * 60L * 1000L

        val SETUP = FronterDisplay(
            title = FronterComplicationFormatter.TITLE,
            text = "Tap to set up",
            description = "PluralWare isn't paired with your phone yet. Tap to set up.",
        )
        val SIGN_IN_AGAIN = FronterDisplay(
            title = FronterComplicationFormatter.TITLE,
            text = "Sign in again",
            description = "PluralKit no longer accepts this token. Tap to open PluralWare.",
        )
        val UNAVAILABLE = FronterDisplay(
            title = FronterComplicationFormatter.TITLE,
            text = "Unavailable",
            description = "Couldn't reach PluralKit.",
        )
    }
}
