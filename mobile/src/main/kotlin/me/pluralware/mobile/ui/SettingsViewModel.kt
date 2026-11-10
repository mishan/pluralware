package me.pluralware.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.pluralware.mobile.AppGraph
import me.pluralware.mobile.watch.WatchLink
import me.pluralware.mobile.watch.WatchState
import me.pluralware.shared.model.SystemInfo
import me.pluralware.shared.repository.PkResult
import me.pluralware.shared.repository.PluralKitRepository
import me.pluralware.shared.repository.TokenStore
import me.pluralware.shared.settings.AppSettings
import me.pluralware.shared.settings.RefreshInterval
import me.pluralware.shared.settings.SettingsStore

/** The signed-in account, the watch if there is one, and app settings. */
class SettingsViewModel(
    private val repository: PluralKitRepository,
    private val settingsStore: SettingsStore,
    private val tokenStore: TokenStore,
    private val watchLink: WatchLink,
    private val signOut: () -> Unit,
    val signingOut: StateFlow<Boolean>,
) : ViewModel() {

    private val _system = MutableStateFlow<SystemInfo?>(null)
    /** Who's signed in, once PluralKit says; null until then or if it can't. */
    val system: StateFlow<SystemInfo?> = _system.asStateFlow()

    val settings: StateFlow<AppSettings> = settingsStore.settingsFlow
    val watch: StateFlow<WatchState> = watchLink.state

    init {
        watchLink.check()
        viewModelScope.launch {
            (repository.ownSystem() as? PkResult.Success)?.let { _system.value = it.value }
        }
    }

    fun setRefreshInterval(interval: RefreshInterval) {
        viewModelScope.launch {
            settingsStore.setRefreshInterval(interval)
            watchLink.settingsChanged()
        }
    }

    fun sendToWatch() {
        viewModelScope.launch { tokenStore.getToken()?.let(watchLink::send) }
    }

    fun disconnect() = signOut()

    class Factory(
        private val repository: PluralKitRepository,
        private val graph: AppGraph,
    ) : ViewModelProvider.Factory by viewModelFactory({
        initializer {
            SettingsViewModel(
                repository = repository,
                settingsStore = graph.settingsStore,
                tokenStore = graph.tokenStore,
                watchLink = graph.watchLink,
                signOut = graph.account::disconnect,
                signingOut = graph.account.signingOut,
            )
        }
    })
}
