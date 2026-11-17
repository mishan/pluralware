package org.foggyden.pluralware.mobile.widget

import android.content.Context
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.foggyden.pluralware.mobile.BuildConfig
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
import org.foggyden.pluralware.shared.settings.LastFronterCache
import org.foggyden.pluralware.shared.settings.LastFronterStore

/**
 * What the home-screen widgets show, and the one place the app tells them
 * something changed, as [FronterSource] is for the watch's complication and tile.
 *
 * Every widget draws [display]. It's computed once for all of them: a widget
 * about to draw joins a fetch already out, unless the line has expired since
 * that fetch began. A display fetched with a token that's no longer current
 * is dropped and fetched again.
 */
class WidgetSurfaces(
    private val source: FronterSource,
    private val token: suspend () -> PluralKitToken?,
    private val cache: LastFronterCache,
    private val hasWidgets: suspend () -> Boolean,
    private val updateWidgets: suspend () -> Unit,
    private val scope: CoroutineScope,
) {
    private val _display = MutableStateFlow<FronterDisplay?>(null)
    val display: StateFlow<FronterDisplay?> = _display.asStateFlow()

    // Bumped by anything that makes the shown line old; a fetch begun before a bump isn't joined or shown.
    private val generation = AtomicLong()
    private var shownGeneration = -1L
    private val lock = Mutex()
    private var flight: Pair<Long, Deferred<Unit>>? = null

    /** The app has a new current switch in hand, fetched with [token]. */
    suspend fun publish(switch: Switch?, token: PluralKitToken) = guarded {
        source.remember(switch, token)
        requestUpdates()
    }

    /** The watch reports [notice] as its current switch. */
    suspend fun onRemoteSwitch(notice: SwitchSync.Notice) = guarded {
        if (source.expire(notice)) requestUpdates()
    }

    /** A member was edited, here or on the watch: the line may name them as they were. */
    suspend fun onMemberEdit(notice: MemberSync.Notice) = guarded {
        if (source.expireMembers(notice)) requestUpdates()
    }

    /**
     * Until canceled, forgets the old system's line whenever [tokens] changes
     * to another system or none: pairing, signing out, a new token. The
     * token it starts with, read at process start, isn't a change.
     */
    suspend fun followToken(tokens: StateFlow<PluralKitToken?>) {
        tokens.drop(1).collect {
            guarded {
                cache.clear()
                requestUpdates()
            }
        }
    }

    /** Android's periodic update: ask again, including for widgets already showing. */
    fun poll() = requestUpdates()

    /** Called by each widget before it draws: refreshes [display] unless it's already current. */
    suspend fun prepare() = guarded { refresh() }

    // In the background: callers are the listener service and the app, which mustn't wait on PluralKit.
    private fun requestUpdates() {
        generation.incrementAndGet()
        scope.launch { guarded { if (hasWidgets()) refresh() } }
    }

    private suspend fun refresh() {
        while (true) {
            val fetch = lock.withLock {
                val current = generation.get()
                if (shownGeneration == current) return
                flight?.takeIf { it.first == current && it.second.isActive }
                    ?: (current to scope.async { fetchAndShow(current) }).also { flight = it }
            }.second
            fetch.await()
        }
    }

    // Shows what it fetched itself, so a widget that stopped waiting doesn't leave it unshown.
    private suspend fun fetchAndShow(started: Long) {
        val before = token()?.fingerprint
        val shown = source.current()
        // Not if it's for a system that's since signed out or been replaced, or the line expired meanwhile.
        if (token()?.fingerprint != before) generation.incrementAndGet()
        val current = lock.withLock {
            (started == generation.get()).also {
                if (it) {
                    _display.value = shown
                    shownGeneration = started
                }
            }
        }
        if (current) updateWidgets()
    }

    // The widgets are a best-effort display: their failing mustn't stop the app's or the listener's work.
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { Log.w("WidgetSurfaces", "Couldn't update the widgets", e) }
        }
    }
}

/** The process's [WidgetSurfaces], wired to the real token store, client and cache. */
object PhoneWidgetSurfaces {
    @Volatile private var instance: WidgetSurfaces? = null

    // One client per process, rebuilt when the token changes, so widget
    // updates reuse its connection pool.
    private var client: Pair<String, PluralKitRepository>? = null

    fun get(context: Context): WidgetSurfaces = instance ?: synchronized(this) {
        instance ?: create(context.applicationContext).also { instance = it }
    }

    private fun create(appContext: Context): WidgetSurfaces {
        // The first open of the encrypted store does Keystore work; keep it off the main thread.
        val token: suspend () -> PluralKitToken? = { withContext(Dispatchers.IO) { EncryptedTokenStore.get(appContext) }.getToken() }
        val cache = LastFronterStore.get(appContext)
        return WidgetSurfaces(
            source = FronterSource(token, ::repositoryFor, cache, PHONE_WORDING),
            token = token,
            cache = cache,
            hasWidgets = { GlanceAppWidgetManager(appContext).getGlanceIds(FronterWidget::class.java).isNotEmpty() },
            updateWidgets = { FronterWidget().updateAll(appContext) },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        )
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

internal val PHONE_WORDING = FronterWording(
    setup = FronterDisplay(
        title = FronterFormatter.TITLE,
        text = "Tap to sign in",
        description = "PluralWare isn't connected to PluralKit yet. Tap to sign in.",
    ),
    signInAgain = FronterDisplay(
        title = FronterFormatter.TITLE,
        text = "Sign in again",
        description = "PluralKit no longer accepts this token. Tap to open PluralWare.",
    ),
)
