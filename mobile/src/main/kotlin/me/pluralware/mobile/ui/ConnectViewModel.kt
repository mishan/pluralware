package me.pluralware.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.pluralware.mobile.AppGraph
import me.pluralware.mobile.BuildConfig
import me.pluralware.shared.api.PluralKitClientFactory
import me.pluralware.shared.api.PluralKitHttpException
import me.pluralware.shared.api.PluralKitToken
import me.pluralware.shared.model.SystemInfo
import me.pluralware.shared.repository.TokenStore

/**
 * Connecting a PluralKit account:
 *  1. Validate the token by calling `GET /systems/@me`, which proves it isn't
 *     expired, revoked, or a typo, and says which system it's for.
 *  2. Tell [onConnected] which system that is, so sharing stays with its system.
 *  3. Hand the token to [WatchLink], which sends it on if this phone has a
 *     watch. That runs process-wide, because step 4 replaces this screen.
 *  4. Save it. The app sees the stored token and opens the signed-in screens.
 */
class ConnectViewModel(
    private val tokenStore: TokenStore,
    private val sendToWatch: (PluralKitToken) -> Unit,
    private val onConnected: suspend (SystemInfo) -> Unit,
    // Seam for tests; the default asks PluralKit.
    private val validate: suspend (PluralKitToken) -> SystemInfo = { token ->
        PluralKitClientFactory.create(
            token = token,
            appVersion = BuildConfig.VERSION_NAME,
            enableLogging = BuildConfig.DEBUG,
        ).getOwnSystem()
    },
) : ViewModel() {

    private val _state = MutableStateFlow(ConnectState())
    val state: StateFlow<ConnectState> = _state.asStateFlow()

    fun onInputChange(value: String) {
        _state.update { it.copy(input = value, error = null) }
    }

    fun connect() {
        val raw = _state.value.input.trim()
        if (raw.isEmpty() || _state.value.validating) return

        _state.update { it.copy(validating = true, error = null) }
        viewModelScope.launch {
            val token = PluralKitToken(raw)
            try {
                onConnected(validate(token))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(validating = false, error = e.toFriendlyValidationMessage()) }
                return@launch
            }
            sendToWatch(token)
            tokenStore.setToken(token)
        }
    }

    private fun Throwable.toFriendlyValidationMessage(): String = when {
        (this as? PluralKitHttpException)?.isUnauthorized == true ->
            "That token wasn't accepted by PluralKit. Double-check you copied the whole `pk;token` output."
        else -> message?.takeIf { it.isNotBlank() }
            ?: "Couldn't reach PluralKit. Check your connection and try again."
    }

    class Factory(private val graph: AppGraph) : ViewModelProvider.Factory by viewModelFactory({
        initializer {
            ConnectViewModel(graph.tokenStore, graph.watchLink::send, graph.account::connected)
        }
    })
}

data class ConnectState(
    val input: String = "",
    val validating: Boolean = false,
    val error: String? = null,
)
