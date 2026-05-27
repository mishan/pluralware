package me.pluralware.mobile.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.pluralware.mobile.BuildConfig
import me.pluralware.shared.api.PluralKitClientFactory
import me.pluralware.shared.api.PluralKitToken
import me.pluralware.shared.handoff.TokenHandoff
import me.pluralware.shared.model.SystemInfo
import me.pluralware.shared.repository.TokenStore

/**
 * Coordinates the three steps of pairing:
 *  1. Validate the token by calling `GET /systems/@me` — proves it isn't
 *     expired, revoked, or a typo.
 *  2. Persist it locally so the phone remembers the connection.
 *  3. Push it to the watch via [TokenHandoff].
 *
 * Step 3 is independent of step 2's success on screen — the Wearable API
 * commonly isn't available on emulators without a paired watch, so we
 * surface the push outcome separately and let the user retry it on its own
 * via [resendToWatch] without re-validating.
 */
class TokenEntryViewModel(
    private val tokenStore: TokenStore,
    private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(TokenEntryState())
    val state: StateFlow<TokenEntryState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            tokenStore.tokenFlow.collect { stored ->
                _state.update { current ->
                    val hasToken = stored != null
                    // Token loaded from a previous session — surface as
                    // "saved, ready to resend" rather than blank Idle.
                    val nextStatus = when {
                        !hasToken -> ConnectionStatus.Idle
                        current.status is ConnectionStatus.Idle ->
                            ConnectionStatus.Connected(system = null, push = PushState.Idle)
                        else -> current.status
                    }
                    current.copy(hasStoredToken = hasToken, status = nextStatus)
                }
            }
        }
    }

    fun onInputChange(value: String) {
        _state.update { current ->
            // Don't wipe a Connected state mid-typing — the user might be
            // pasting a replacement, but until they tap Connect the existing
            // session is still valid.
            val cleared = if (current.status is ConnectionStatus.Error) {
                ConnectionStatus.Idle
            } else current.status
            current.copy(input = value, status = cleared)
        }
    }

    fun connect() {
        val raw = _state.value.input.trim()
        if (raw.isEmpty()) return
        if (_state.value.status is ConnectionStatus.Validating) return

        _state.update { it.copy(status = ConnectionStatus.Validating) }
        viewModelScope.launch {
            val token = try {
                PluralKitToken(raw)
            } catch (e: IllegalArgumentException) {
                _state.update { it.copy(status = ConnectionStatus.Error("Paste your token before tapping Connect.")) }
                return@launch
            }

            val system = runCatching {
                val client = PluralKitClientFactory.create(
                    token = token,
                    enableLogging = BuildConfig.DEBUG,
                )
                client.getOwnSystem()
            }.getOrElse { e ->
                _state.update { it.copy(status = ConnectionStatus.Error(e.toFriendlyValidationMessage())) }
                return@launch
            }

            tokenStore.setToken(token)
            _state.update {
                it.copy(
                    input = "",
                    status = ConnectionStatus.Connected(system = system, push = PushState.Pushing),
                )
            }
            pushAndUpdate(token)
        }
    }

    fun resendToWatch() {
        viewModelScope.launch {
            val token = tokenStore.getToken() ?: return@launch
            _state.update { it.withPushState(PushState.Pushing) }
            pushAndUpdate(token)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            tokenStore.clear()
            _state.update { it.copy(status = ConnectionStatus.Idle, input = "") }
        }
    }

    private suspend fun pushAndUpdate(token: PluralKitToken) {
        val newPushState = runCatching { TokenHandoff.push(appContext, token) }.fold(
            onSuccess = { PushState.Pushed },
            onFailure = { e -> PushState.Failed(e.toFriendlyPushMessage()) },
        )
        _state.update { it.withPushState(newPushState) }
    }

    private fun TokenEntryState.withPushState(push: PushState): TokenEntryState {
        val current = this.status as? ConnectionStatus.Connected ?: return this
        return copy(status = current.copy(push = push))
    }

    private fun Throwable.toFriendlyValidationMessage(): String {
        val msg = message.orEmpty()
        return when {
            "401" in msg -> "That token wasn't accepted by PluralKit. Double-check you copied the whole `pk;token` output."
            else -> msg.ifBlank { "Couldn't reach PluralKit. Check your connection and try again." }
        }
    }

    private fun Throwable.toFriendlyPushMessage(): String {
        // Pixel emulator images without a Wear OS companion (and devices that
        // simply don't have Google Play Services Wearable) throw API_NOT_CONNECTED
        // with the message "Wearable.API is not available on this device".
        val isApiUnavailable = this is ApiException &&
            (statusCode == CommonStatusCodes.API_NOT_CONNECTED ||
                message.orEmpty().contains("not available", ignoreCase = true))
        if (isApiUnavailable) {
            return "No paired watch detected. Pair a watch in the Wear OS app, then tap Resend to watch."
        }
        return message?.takeIf { it.isNotBlank() }
            ?: "Couldn't reach your watch. Make sure it's nearby and try Resend."
    }

    class Factory(
        private val tokenStore: TokenStore,
        private val appContext: Context,
    ) : ViewModelProvider.Factory by viewModelFactory({
        initializer { TokenEntryViewModel(tokenStore, appContext) }
    })
}

data class TokenEntryState(
    val input: String = "",
    val hasStoredToken: Boolean = false,
    val status: ConnectionStatus = ConnectionStatus.Idle,
)

sealed interface ConnectionStatus {
    data object Idle : ConnectionStatus
    data object Validating : ConnectionStatus
    /**
     * Token is saved locally. [system] is null when the token was loaded from
     * a previous session and never re-validated this run; non-null only after
     * a successful [TokenEntryViewModel.connect].
     */
    data class Connected(val system: SystemInfo?, val push: PushState) : ConnectionStatus
    data class Error(val message: String) : ConnectionStatus
}

sealed interface PushState {
    /** Saved locally but never attempted a push this session. */
    data object Idle : PushState
    data object Pushing : PushState
    data object Pushed : PushState
    data class Failed(val message: String) : PushState
}
