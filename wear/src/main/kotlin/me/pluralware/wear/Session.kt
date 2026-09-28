package me.pluralware.wear

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import me.pluralware.shared.api.PluralKitToken
import me.pluralware.shared.repository.PluralKitRepository

/**
 * The signed-in session: one [PluralKitRepository] per token, and the
 * [ViewModelStore] the screens' ViewModels live in.
 *
 * It lives in the activity's own ViewModelStore, so the repository and the
 * screens survive configuration changes (a font-scale or locale change on the
 * watch) *together*. Anything the activity hangs off the repository keeps
 * observing the one the screens use. A new token, or signing out, clears the
 * old store, so the old screens' ViewModels are released with it rather than
 * lingering until the activity finishes.
 */
class SessionHolder : ViewModel() {
    private var current: Session? = null

    fun sessionFor(token: PluralKitToken, newRepository: () -> PluralKitRepository): Session {
        current?.takeIf { it.tokenRaw == token.raw }?.let { return it }
        end()
        return Session(token.raw, newRepository()).also { current = it }
    }

    fun end() {
        current?.viewModelStore?.clear()
        current = null
    }

    override fun onCleared() = end()
}

class Session internal constructor(
    internal val tokenRaw: String,
    val repository: PluralKitRepository,
) : ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()
}
