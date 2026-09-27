package me.pluralware.wear.complication

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
        val cached = cache.get()
        if (cached != null && clock() - cached.fetchedAtEpochMillis in 0 until FRESH_FOR_MILLIS) {
            return cached.toDisplay(lastKnown = false)
        }
        return when (val result = repositoryFor(token).refreshFronters()) {
            is PkResult.Success -> remember(result.value).toDisplay(lastKnown = false)
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

    /** Cache [switch] as the current fronters, fetched now. */
    suspend fun remember(switch: Switch?): LastFronter =
        LastFronter(
            text = FronterComplicationFormatter.body(switch),
            description = FronterComplicationFormatter.contentDescription(switch),
            fetchedAtEpochMillis = clock(),
        ).also { cache.set(it) }

    private fun LastFronter.toDisplay(lastKnown: Boolean) = if (lastKnown) {
        FronterDisplay(title = "Last known", text = text, description = "Last known. $description")
    } else {
        FronterDisplay(title = FronterComplicationFormatter.TITLE, text = text, description = description)
    }

    companion object {
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
