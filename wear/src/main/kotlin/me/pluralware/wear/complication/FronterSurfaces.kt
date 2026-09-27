package me.pluralware.wear.complication

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.pluralware.shared.api.PluralKitClientFactory
import me.pluralware.shared.api.PluralKitToken
import me.pluralware.shared.model.Switch
import me.pluralware.shared.repository.EncryptedTokenStore
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.settings.LastFronterStore
import me.pluralware.wear.BuildConfig
import me.pluralware.wear.tile.requestFronterTileUpdate

/**
 * The Android side of the glanceable surfaces: wires [FronterSource] to the
 * real token store, client and cache, and is the one place the rest of the
 * app tells them something changed.
 *
 * Every way the fronter can change on this watch reaches [publish]: the app
 * observes its repository's current fronters, which the picker, History's
 * switch-back and every refresh all go through. Switches made elsewhere
 * (Discord, the dashboard, another client) are caught by the complication's
 * update period and the tile's freshness interval.
 */
object FronterSurfaces {

    // One client per process, rebuilt when the token changes, so repeated
    // requests reuse its connection pool instead of a fresh TLS handshake each.
    private var client: Pair<String, PluralKitRepository>? = null

    internal fun source(context: Context): FronterSource {
        val appContext = context.applicationContext
        return FronterSource(
            // The first open of the encrypted store does Keystore work; keep
            // it off the main thread the services are called on.
            token = { withContext(Dispatchers.IO) { EncryptedTokenStore.get(appContext) }.getToken() },
            repositoryFor = ::repositoryFor,
            cache = LastFronterStore.get(appContext),
        )
    }

    /** The app has a new current switch in hand: cache it and refresh the surfaces. */
    suspend fun publish(context: Context, switch: Switch?) {
        source(context).remember(switch)
        requestUpdates(context)
    }

    /** The token was replaced or cleared: forget the old system's line and refresh. */
    suspend fun onTokenChanged(context: Context) {
        LastFronterStore.get(context).clear()
        requestUpdates(context)
    }

    private fun requestUpdates(context: Context) {
        requestFronterComplicationUpdate(context)
        requestFronterTileUpdate(context)
    }

    @Synchronized
    private fun repositoryFor(token: PluralKitToken): PluralKitRepository {
        client?.let { (raw, repository) -> if (raw == token.raw) return repository }
        return PluralKitRepository(
            PluralKitClientFactory.create(
                token = token,
                appVersion = BuildConfig.VERSION_NAME,
                enableLogging = BuildConfig.DEBUG,
            ),
        ).also { client = token.raw to it }
    }
}
