package org.foggyden.pluralware.wear.complication

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.foggyden.pluralware.shared.api.PluralKitClientFactory
import org.foggyden.pluralware.shared.api.PluralKitToken
import org.foggyden.pluralware.shared.fronter.FronterDisplay
import org.foggyden.pluralware.shared.fronter.FronterFormatter
import org.foggyden.pluralware.shared.fronter.FronterSource
import org.foggyden.pluralware.shared.fronter.FronterWording
import org.foggyden.pluralware.shared.handoff.MemberSync
import org.foggyden.pluralware.shared.handoff.SwitchSync
import org.foggyden.pluralware.shared.model.Switch
import org.foggyden.pluralware.shared.repository.EncryptedTokenStore
import org.foggyden.pluralware.shared.repository.PluralKitRepository
import org.foggyden.pluralware.shared.settings.LastFronterStore
import org.foggyden.pluralware.wear.BuildConfig
import org.foggyden.pluralware.wear.tile.requestFronterTileUpdate

/**
 * The Android side of the glanceable surfaces: wires [FronterSource] to the
 * real token store, client and cache, and is the one place the rest of the
 * app tells them something changed.
 *
 * Every way the fronter can change on this watch reaches [publish]: the app
 * observes its repository's current fronters, which the picker and every
 * refresh go through. Switches the phone makes or sees arrive through
 * [onRemoteSwitch]. Others (Discord, the dashboard, another client) are
 * caught by the complication's update period and the tile's freshness interval.
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
            wording = WATCH_WORDING,
        )
    }

    /** The app has a new current switch in hand, fetched with [token]: cache it and refresh the surfaces. */
    suspend fun publish(context: Context, switch: Switch?, token: PluralKitToken) {
        source(context).remember(switch, token)
        requestUpdates(context)
    }

    /**
     * The phone reports [notice] as its current switch: if it's news, refresh
     * the surfaces from PluralKit, even with the app closed.
     */
    suspend fun onRemoteSwitch(context: Context, notice: SwitchSync.Notice) {
        if (source(context).expire(notice)) requestUpdates(context)
    }

    /** The phone edited a member: the surfaces may name them as they were, so refresh them. */
    suspend fun onRemoteMemberEdit(context: Context, notice: MemberSync.Notice) {
        if (source(context).expireMembers(notice)) requestUpdates(context)
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

private val WATCH_WORDING = FronterWording(
    setup = FronterDisplay(
        title = FronterFormatter.TITLE,
        text = "Tap to set up",
        description = "PluralWare isn't paired with your phone yet. Tap to set up.",
    ),
    signInAgain = FronterDisplay(
        title = FronterFormatter.TITLE,
        text = "Sign in again",
        description = "PluralKit no longer accepts this token. Tap to open PluralWare.",
    ),
)
