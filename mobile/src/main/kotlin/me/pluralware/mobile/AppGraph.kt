package me.pluralware.mobile

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import me.pluralware.mobile.watch.WatchLink
import me.pluralware.shared.api.PluralKitClientFactory
import me.pluralware.shared.api.PluralKitToken
import me.pluralware.shared.handoff.SharingHandoff
import me.pluralware.shared.notify.EncryptedFollowingStore
import me.pluralware.shared.notify.EncryptedSharingStore
import me.pluralware.shared.notify.RelayClient
import me.pluralware.shared.notify.RelayNameSync
import me.pluralware.shared.notify.SwitchAnnouncer
import me.pluralware.shared.repository.EncryptedTokenStore
import me.pluralware.shared.repository.PkResult
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.settings.LocalSettingsStore

/**
 * The phone's process-wide pieces, wired by hand (no DI framework). Work
 * that must outlive the screen that started it, such as sending the token to a
 * watch or signing out, runs in [scope]: either one swaps the whole UI.
 */
class AppGraph private constructor(context: Context) {
    private val appContext = context.applicationContext

    // The handler is a last line of defense: nothing run here may crash the app.
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Main.immediate +
            CoroutineExceptionHandler { _, e -> Log.w("AppGraph", "Background work failed", e) },
    )
    val tokenStore = EncryptedTokenStore.get(appContext)
    val settingsStore = LocalSettingsStore.get(appContext)
    val sharingStore = EncryptedSharingStore.get(appContext)
    val followingStore = EncryptedFollowingStore.get(appContext)
    val watchLink = WatchLink.create(appContext, settingsStore, scope)

    // Friends hear about switches made on this phone; the watch announces its
    // own. Friends this phone finds gone are reported to the watch, which
    // skips them too; without Wear OS the report fails quietly.
    private val announcer = SwitchAnnouncer(BuildConfig.VERSION_NAME) { context, gone ->
        SharingHandoff.pushStatus(context, gone)
    }

    // A rename of a shared member reaches the relay, which announces with the names it was given.
    val relayWrites = Mutex()
    private val relayClient = RelayClient.create(BuildConfig.VERSION_NAME)
    private val relayNames = RelayNameSync(sharingStore, relayWrites) { relay, config ->
        runCatching { relayClient.upload(relay, config) }
    }

    fun newRepository(token: PluralKitToken): PluralKitRepository {
        val client = PluralKitClientFactory.create(
            token = token,
            appVersion = BuildConfig.VERSION_NAME,
            enableLogging = BuildConfig.DEBUG,
        )
        lateinit var repository: PluralKitRepository
        repository = PluralKitRepository(
            client,
            onMemberEdited = { member, fields ->
                scope.launch { relayNames.memberEdited(member, fields) { (repository.refreshMembers() as? PkResult.Success)?.value } }
            },
        ) { switch -> announcer.onSwitchRegistered(appContext, switch) }
        return repository
    }

    val account = Account(
        scope = scope,
        tokenStore = tokenStore,
        sharingStore = sharingStore,
        owner = PrefsSharingOwner(appContext),
        clearRelay = { relay ->
            runCatching { relayClient.clear(relay) }.isSuccess
        },
        signOutWatch = watchLink::signOut,
        pushSharingToWatch = { config -> runCatching { SharingHandoff.push(appContext, config) } },
        relayWrites = relayWrites,
    )

    init {
        scope.launch {
            account.claimUnowned { token ->
                PluralKitClientFactory.create(
                    token = token,
                    appVersion = BuildConfig.VERSION_NAME,
                    enableLogging = BuildConfig.DEBUG,
                ).getOwnSystem()
            }
        }
    }

    companion object {
        @Volatile private var instance: AppGraph? = null

        fun get(context: Context): AppGraph =
            instance ?: synchronized(this) { instance ?: AppGraph(context).also { instance = it } }
    }
}
