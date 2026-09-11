package com.thesystem.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.thesystem.app.data.model.UserDto
import com.thesystem.app.data.repo.AuthRepository
import com.thesystem.app.data.repo.SystemRepository
import com.thesystem.app.ui.admin.AdminScreen
import com.thesystem.app.ui.arena.BattleRoomScreen
import com.thesystem.app.ui.chat.ChatHomeScreen
import com.thesystem.app.ui.chat.ConversationScreen
import com.thesystem.app.ui.onboarding.AwakeningFlowScreen
import com.thesystem.app.ui.protocol.ProtocolScreen
import com.thesystem.app.ui.splash.DynamicSplash
import com.thesystem.app.ui.splash.SplashVariant
import com.thesystem.app.ui.tabs.MainTabs
import com.thesystem.app.core.theme.SystemTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** App entry gate: splash → onboarding/auth → 5-tab shell. */
sealed interface RootState {
    data object Booting : RootState
    data object NeedsOnboarding : RootState
    data class Ready(val profile: UserDto) : RootState
    data class Error(val message: String) : RootState
}

@HiltViewModel
class RootViewModel @Inject constructor(
    private val auth: AuthRepository,
    private val system: SystemRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<RootState>(RootState.Booting)
    val state: StateFlow<RootState> = _state

    init { observeSession() }

    fun observeSession() = viewModelScope.launch {
        auth.sessionStatus.collect { status ->
            when (status) {
                is SessionStatus.Authenticated -> resolve()
                is SessionStatus.NotAuthenticated -> _state.value = RootState.NeedsOnboarding
                else -> Unit // Initializing — keep booting splash
            }
        }
    }

    /** Re-pull the profile and decide which gate applies. */
    fun resolve() = viewModelScope.launch {
        _state.value = RootState.Booting
        delay(350) // let the splash engine breathe; zero jank on nav swap
        val profile = auth.myProfile()
        _state.value = when {
            profile == null -> RootState.NeedsOnboarding
            !profile.onboardingCompleted || profile.username.startsWith("hunter_") -> RootState.NeedsOnboarding
            else -> RootState.Ready(profile)
        }
    }

    fun signOut() = viewModelScope.launch { runCatching { auth.signOut() }; _state.value = RootState.NeedsOnboarding }
}

object Routes {
    const val MAIN = "main"
    const val CHAT = "chat"
    const val DM = "dm/{otherId}/{otherName}"
    const val BATTLE = "battle/{battleId}"
    const val ADMIN = "admin"
    const val PROTOCOL = "protocol" // Black/White rooms + arcs — off the tab rail since 2.7
    fun dm(otherId: String, otherName: String) = "dm/$otherId/$otherName"
    fun battle(id: String) = "battle/$id"
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // PROFESSIONAL IMMERSIVE MODE (hunter request): system nav bar swipes away
        // on launch like pro apps; an edge-swipe pulls it up; it holds 10s, then
        // auto-swipes back down.
        val barsController = WindowInsetsControllerCompat(window, window.decorView)
        barsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        barsController.hide(WindowInsetsCompat.Type.navigationBars())
        val hideNavAgain = Runnable { barsController.hide(WindowInsetsCompat.Type.navigationBars()) }
        ViewCompat.setOnApplyWindowInsetsListener(window.decorView) { v, insets ->
            v.removeCallbacks(hideNavAgain)
            if (insets.isVisible(WindowInsetsCompat.Type.navigationBars())) {
                v.postDelayed(hideNavAgain, 10_000L)
            }
            insets
        }
        setContent {
            SystemTheme {
                val vm: RootViewModel = hiltViewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                // ROUND 7: cinematic launch flow — unregistered vessels walk the full
                // intake → awakening → contract → evaluation → auth gate ritual.
                // Signed-in hunters get the shuffled brand splash and go straight in.
                val splashVariants = remember { SplashVariant.entries.shuffled() }
                when (val s = state) {
                    RootState.Booting -> DynamicSplash(variants = splashVariants)
                    RootState.NeedsOnboarding -> AwakeningFlowScreen(onDone = { vm.resolve() })
                    is RootState.Ready -> AppNavHost(profile = s, onSignOut = { vm.signOut() })
                    is RootState.Error -> DynamicSplash(variants = splashVariants) // offline tolerance: keep brand screen; retry taps re-resolve
                }
            }
        }
    }
}

@Composable
fun AppNavHost(profile: RootState.Ready, onSignOut: () -> Unit) {
    val nav: NavHostController = rememberNavController()
    NavHost(navController = nav, startDestination = Routes.MAIN) {
        composable(Routes.MAIN) { MainTabs(profile = profile.profile, nav = nav, onSignOut = onSignOut) }
        composable(Routes.CHAT) { ChatHomeScreen(nav = nav) }
        composable(Routes.DM) { backStack ->
            val otherId = backStack.arguments?.getString("otherId") ?: return@composable
            val otherName = backStack.arguments?.getString("otherName") ?: "hunter"
            ConversationScreen(otherId = otherId, otherName = otherName, onBack = { nav.popBackStack() })
        }
        composable(Routes.BATTLE) { backStack ->
            val battleId = backStack.arguments?.getString("battleId") ?: return@composable
            BattleRoomScreen(battleId = battleId, onExit = { nav.popBackStack() })
        }
        composable(Routes.ADMIN) { AdminScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.PROTOCOL) { ProtocolScreen(nav = nav) }
    }
}
