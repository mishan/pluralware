package me.pluralware.mobile.widget

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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.pluralware.mobile.BuildConfig
import me.pluralware.shared.api.PluralKitClientFactory
import me.pluralware.shared.api.PluralKitToken
import me.pluralware.shared.fronter.FronterDisplay
import me.pluralware.shared.fronter.FronterFormatter
import me.pluralware.shared.fronter.FronterSource
import me.pluralware.shared.fronter.FronterWording
import me.pluralware.shared.handoff.MemberSync
import me.pluralware.shared.handoff.SwitchSync
import me.pluralware.shared.model.Switch
import me.pluralware.shared.repository.EncryptedTokenStore
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.settings.LastFronterCache
import me.pluralware.shared.settings.LastFronterStore

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
    private var flight: Pair<Long, Deferred<FronterDisplay>>? = null

    /** The app has a new current switch in hand, fetched with [token]. */
    suspend fun publish(switch: Switch?, token: PluralKitToken) {
        source.remember(switch, token)
        requestUpdates()
    }

    /** The watch reports [notice] as its current switch. */
    suspend fun onRemoteSwitch(notice: SwitchSync.Notice) {
        if (source.expire(notice)) requestUpdates()
    }

    /** A member was edited, here or on the watch: the line may name them as they were. */
    suspend fun onMemberEdit(notice: MemberSync.Notice) {
        if (source.expireMembers(notice)) requestUpdates()
    }

    /**
     * Until canceled, forgets the old system's line whenever [tokens] changes
     * to another system or none: pairing, signing out, a new token. The
     * token it starts with, read at process start, isn't a change.
     */
    suspend fun followToken(tokens: StateFlow<PluralKitToken?>) {
        tokens.drop(1).collect {
            // One failed update mustn't stop it following later changes.
            try {
                cache.clear()
                requestUpdates()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("WidgetSurfaces", "Couldn't update the widgets", e)
            }
        }
    }

    /** Android's periodic update: the next widget to draw asks again. */
    fun invalidate() {
        generation.incrementAndGet()
    }

    /** Called by each widget before it draws: refreshes [display] unless it's already current. */
    suspend fun prepare() {
        if (lock.withLock { shownGeneration != generation.get() }) refresh()
    }

    private suspend fun requestUpdates() {
        invalidate()
        if (!hasWidgets()) return
        refresh()
        updateWidgets()
    }

    private suspend fun refresh() {
        while (true) {
            val (started, fetch) = lock.withLock {
                val current = generation.get()
                // A newer fetch already finished while this one's went stale.
                if (shownGeneration == current) return
                flight?.takeIf { it.first == current && it.second.isActive }
                    ?: (current to scope.async { fetchWithToken() }).also { flight = it }
            }
            val shown = fetch.await()
            lock.withLock {
                if (started == generation.get()) {
                    _display.value = shown
                    shownGeneration = started
                    return
                }
            }
        }
    }

    private suspend fun fetchWithToken(): FronterDisplay {
        val before = token()?.fingerprint
        val shown = source.current()
        // Fetched for a system that's since signed out or been replaced.
        if (token()?.fingerprint != before) invalidate()
        return shown
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

private val PHONE_WORDING = FronterWording(
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
