package me.pluralware.shared.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Where app settings live. Implementations:
 *  - [LocalSettingsStore] (Android SharedPreferences) — production.
 *  - [InMemorySettingsStore] — tests and previews.
 *
 * Settings aren't sensitive, so unlike [me.pluralware.shared.repository.TokenStore]
 * this uses plain SharedPreferences. Exposed as a [StateFlow] so the watch UI
 * recomposes when the phone pushes a new value.
 */
interface SettingsStore {
    val settingsFlow: StateFlow<AppSettings>
    suspend fun getSettings(): AppSettings
    suspend fun setRefreshInterval(interval: RefreshInterval)
}

class InMemorySettingsStore(initial: AppSettings = AppSettings()) : SettingsStore {
    private val _flow = MutableStateFlow(initial)
    override val settingsFlow: StateFlow<AppSettings> = _flow.asStateFlow()
    override suspend fun getSettings(): AppSettings = _flow.value
    override suspend fun setRefreshInterval(interval: RefreshInterval) {
        _flow.value = _flow.value.copy(refreshInterval = interval)
    }
}

/**
 * Production [SettingsStore] backed by plain [SharedPreferences].
 *
 * Process-wide singleton via [get] so the watch listener service and the
 * activity share one in-memory [settingsFlow] — a push received by the service
 * is observed live by the UI without a manual reload.
 */
class LocalSettingsStore private constructor(appContext: Context) : SettingsStore {

    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    private val _flow = MutableStateFlow(prefs.read())
    override val settingsFlow: StateFlow<AppSettings> = _flow.asStateFlow()

    override suspend fun getSettings(): AppSettings = _flow.value

    override suspend fun setRefreshInterval(interval: RefreshInterval) = withContext(Dispatchers.IO) {
        prefs.edit().putInt(KEY_POLL_SECONDS, interval.seconds).apply()
        _flow.value = _flow.value.copy(refreshInterval = interval)
    }

    private fun SharedPreferences.read(): AppSettings = AppSettings(
        refreshInterval = RefreshInterval.fromSeconds(
            getInt(KEY_POLL_SECONDS, RefreshInterval.DEFAULT.seconds),
        ),
    )

    companion object {
        private const val PREFS_FILE = "pluralware_settings"
        private const val KEY_POLL_SECONDS = "poll_interval_seconds"

        @Volatile private var instance: LocalSettingsStore? = null

        fun get(context: Context): LocalSettingsStore =
            instance ?: synchronized(this) {
                instance ?: LocalSettingsStore(context.applicationContext).also { instance = it }
            }
    }
}
