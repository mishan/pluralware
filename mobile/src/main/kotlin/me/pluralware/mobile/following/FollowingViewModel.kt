package me.pluralware.mobile.following

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import me.pluralware.shared.notify.Follow
import me.pluralware.shared.notify.FollowingStore
import me.pluralware.shared.notify.Invite
import org.unifiedpush.android.connector.UnifiedPush

/**
 * Following other systems: accept an invite, register for pushes through the
 * person's UnifiedPush distributor, and hand back a follow code
 * (docs/notifications-design.md §4.1, §4.4).
 */
class FollowingViewModel(
    private val store: FollowingStore,
    private val appContext: Context,
) : ViewModel() {

    val follows: StateFlow<List<Follow>> = store.follows

    private val _inviteError = MutableStateFlow<String?>(null)
    val inviteError: StateFlow<String?> = _inviteError.asStateFlow()

    /**
     * Follows the system in [inviteText]. Needs an [Activity] because picking a
     * distributor may show a chooser. Returns whether the invite was valid.
     */
    fun follow(activity: Activity, inviteText: String, myName: String): Boolean {
        val invite = Invite.parse(inviteText)
        if (invite == null) {
            _inviteError.value = "That isn't a PluralWare invite."
            return false
        }
        _inviteError.value = null
        val follow = Follow(
            instance = UUID.randomUUID().toString(),
            system = invite.system,
            vapid = invite.vapid,
            myName = myName.trim().ifEmpty { "A friend" },
        )
        viewModelScope.launch {
            store.upsert(follow)
            register(activity, follow)
        }
        return true
    }

    /** Tries registering again, e.g. after installing a distributor. */
    fun retry(activity: Activity, follow: Follow) {
        viewModelScope.launch {
            store.update(follow.instance) { it.copy(problem = null) }
            register(activity, follow)
        }
    }

    fun unfollow(follow: Follow) {
        UnifiedPush.unregister(appContext, follow.instance)
        viewModelScope.launch {
            store.remove(follow.instance)
            FollowNotifications.cancel(appContext, follow.instance)
        }
    }

    /** The endpoint and keys arrive later, in [FollowPushService.onNewEndpoint]. */
    private fun register(activity: Activity, follow: Follow) {
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok ->
            if (ok) {
                UnifiedPush.register(appContext, follow.instance, vapid = follow.vapid)
            } else {
                viewModelScope.launch { store.update(follow.instance) { it.copy(problem = NO_DISTRIBUTOR) } }
            }
        }
    }

    class Factory(
        private val store: FollowingStore,
        private val appContext: Context,
    ) : androidx.lifecycle.ViewModelProvider.Factory by viewModelFactory({
        initializer { FollowingViewModel(store, appContext) }
    })

    private companion object {
        const val NO_DISTRIBUTOR =
            "No push distributor found. Install one, such as the ntfy app, then tap Try again."
    }
}
