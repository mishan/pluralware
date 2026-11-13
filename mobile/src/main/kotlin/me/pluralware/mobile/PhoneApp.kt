package me.pluralware.mobile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import kotlinx.coroutines.launch
import me.pluralware.mobile.following.FollowingScreen
import me.pluralware.mobile.following.FollowingViewModel
import me.pluralware.mobile.sharing.SharingScreen
import me.pluralware.mobile.sharing.SharingViewModel
import me.pluralware.mobile.ui.ConnectScreen
import me.pluralware.mobile.ui.ConnectViewModel
import me.pluralware.mobile.ui.SettingsScreen
import me.pluralware.mobile.ui.SettingsViewModel
import me.pluralware.mobile.ui.members.MemberEditorScreen
import me.pluralware.mobile.ui.members.MembersScreen
import me.pluralware.mobile.ui.screens.FrontersScreen
import me.pluralware.mobile.ui.screens.HistoryScreen
import me.pluralware.mobile.ui.screens.PickerScreen
import me.pluralware.shared.handoff.MemberSync
import me.pluralware.shared.handoff.SwitchSync
import me.pluralware.shared.session.Session
import me.pluralware.shared.session.SessionHolder

object Routes {
    const val CONNECT = "connect"
    const val HOME = "home"
    const val PICK = "pick"
    const val HISTORY = "history"
    const val MEMBERS = "members"
    const val EDIT_MEMBER = "members/{uuid}"

    fun editMember(uuid: String) = "members/$uuid"
    const val SETTINGS = "settings"
    const val SHARING = "sharing"
    const val FOLLOWING = "following"
}

/**
 * Signed out: connect, or follow others. Signed in: the same three screens as
 * the watch, plus settings, sharing and following.
 *
 * As on the watch, everything signed in hangs off a [Session] keyed on the
 * token: its repository, and the store the screens' ViewModels live in. A new
 * token or a sign-out starts from scratch, so no screen keeps talking to
 * PluralKit with a token that's gone.
 *
 * [requestedRoute] is a screen asked for from outside, such as Following from
 * a friend's notification; [onRequestHandled] runs once it's open.
 */
@Composable
fun PhoneApp(graph: AppGraph, requestedRoute: String?, onRequestHandled: () -> Unit) {
    val token by graph.tokenStore.tokenFlow.collectAsState()
    val sessions: SessionHolder = viewModel()
    val signedOut: SignedOutStore = viewModel()
    val current = token
    key(current?.raw) {
        val owner: ViewModelStoreOwner
        val session: Session?
        if (current == null) {
            SideEffect { sessions.end() }
            owner = signedOut
            session = null
        } else {
            SideEffect { signedOut.reset() }
            session = sessions.sessionFor(current) { graph.newRepository(current) }
            owner = session
        }
        if (session != null) {
            // While the app is showing, keep a watch on the same switch, both ways,
            // and tell it when a member is edited here.
            val lifecycleOwner = LocalLifecycleOwner.current
            val appContext = LocalContext.current.applicationContext
            LaunchedEffect(session, lifecycleOwner) {
                lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                    launch {
                        SwitchSync.keepInSync(session.repository, session.token, push = { switch ->
                            graph.watchLink.shareSwitch(switch, session.token)
                        })
                    }
                    MemberSync.keepInSync(session.repository, session.token, push = {
                        MemberSync.push(appContext, session.token)
                    }, progress = session.memberSync)
                }
            }
        }
        CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
            val nav = rememberNavController()
            NavHost(navController = nav, startDestination = if (session == null) Routes.CONNECT else Routes.HOME) {
                if (session == null) signedOutRoutes(graph, nav) else signedInRoutes(graph, session, nav)
                composable(Routes.FOLLOWING) {
                    val context = LocalContext.current.applicationContext
                    FollowingScreen(
                        viewModel = viewModel(factory = FollowingViewModel.Factory(graph.followingStore, context)),
                        onBack = { nav.popBackStack() },
                    )
                }
            }
            LaunchedEffect(requestedRoute) {
                if (requestedRoute == Routes.FOLLOWING) {
                    nav.navigate(Routes.FOLLOWING) { launchSingleTop = true }
                    onRequestHandled()
                }
            }
        }
    }
}

private fun NavGraphBuilder.signedOutRoutes(graph: AppGraph, nav: NavHostController) {
    composable(Routes.CONNECT) {
        ConnectScreen(
            viewModel = viewModel(factory = ConnectViewModel.Factory(graph)),
            onOpenFollowing = { nav.navigate(Routes.FOLLOWING) },
        )
    }
}

private fun NavGraphBuilder.signedInRoutes(graph: AppGraph, session: Session, nav: NavHostController) {
    val repository = session.repository
    // Offered when PluralKit rejects the token; keeps sharing for reconnecting.
    val signOut = graph.account::signOutRejected
    composable(Routes.HOME) {
        FrontersScreen(
            repository = repository,
            settingsStore = graph.settingsStore,
            onChangeFronters = { nav.navigate(Routes.PICK) },
            onOpenHistory = { nav.navigate(Routes.HISTORY) },
            onOpenMembers = { nav.navigate(Routes.MEMBERS) },
            onOpenSettings = { nav.navigate(Routes.SETTINGS) },
            onSignOut = signOut,
        )
    }
    composable(Routes.PICK) {
        PickerScreen(
            repository = repository,
            onBack = { nav.popBackStack() },
            onDone = { nav.popBackStack(Routes.HOME, inclusive = false) },
            onSignOut = signOut,
        )
    }
    composable(Routes.HISTORY) {
        HistoryScreen(repository = repository, onBack = { nav.popBackStack() }, onSignOut = signOut)
    }
    composable(Routes.MEMBERS) { entry ->
        MembersScreen(
            repository = repository,
            onBack = { nav.popBackStack() },
            // Only while it's the screen showing: a quick second tap would open a second editor.
            onOpenMember = {
                if (entry.lifecycle.currentState == Lifecycle.State.RESUMED) nav.navigate(Routes.editMember(it))
            },
            onSignOut = signOut,
        )
    }
    composable(Routes.EDIT_MEMBER) { entry ->
        // Only while it's the screen showing: a save that lands as the user leaves mustn't pop Members too.
        val back = { if (nav.currentBackStackEntry == entry) nav.popBackStack() }
        MemberEditorScreen(
            repository = repository,
            uuid = entry.arguments?.getString("uuid").orEmpty(),
            onBack = { back() },
            onSaved = { back() },
            onSignOut = signOut,
        )
    }
    composable(Routes.SETTINGS) {
        SettingsScreen(
            viewModel = viewModel(factory = SettingsViewModel.Factory(repository, graph)),
            onBack = { nav.popBackStack() },
            onOpenSharing = { nav.navigate(Routes.SHARING) },
            onOpenFollowing = { nav.navigate(Routes.FOLLOWING) },
        )
    }
    composable(Routes.SHARING) {
        val context = LocalContext.current.applicationContext
        SharingScreen(
            viewModel = viewModel(factory = SharingViewModel.Factory(graph.tokenStore, graph.sharingStore, context)),
            onBack = { nav.popBackStack() },
        )
    }
}

/**
 * The signed-out screens' ViewModel store, kept in the activity's so typed
 * input survives rotation, and emptied on sign-in.
 */
class SignedOutStore : ViewModel(), ViewModelStoreOwner {
    override val viewModelStore = ViewModelStore()

    fun reset() = viewModelStore.clear()

    override fun onCleared() = viewModelStore.clear()
}
