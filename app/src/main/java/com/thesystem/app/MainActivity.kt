package com.thesystem.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
import com.thesystem.app.ui.training.QuestProofScreen
import com.thesystem.app.ui.splash.DynamicSplash
import com.thesystem.app.ui.splash.SplashVariant
import com.thesystem.app.ui.tabs.MainTabs
import com.thesystem.app.core.theme.SystemTheme
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.thesystem.app.core.theme.FaintGray
import com.thesystem.app.core.theme.MonoLabel
import com.thesystem.app.core.theme.PaperWhite
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jan.supabase.auth.status.SessionStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import javax.inject.Inject

/** App entry gate: splash → onboarding/auth → 5-tab shell. */
sealed interface RootState {
    data object Booting : RootState
    data object NeedsOnboarding : RootState
    /** §8: [gated] = an uncleared penalty trial stands — the app opens in the crimson zone. */
    data class Ready(val profile: UserDto, val gated: Boolean = false) : RootState
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

    /** Re-pull the profile and decide which gate applies. §8: the server-owned
     *  penalty audit runs here too — an uncleared trial gates the entire app. */
    fun resolve() = viewModelScope.launch {
        _state.value = RootState.Booting
        val profile = auth.myProfile()
        _state.value = when {
            profile == null -> RootState.NeedsOnboarding
            !profile.onboardingCompleted || profile.username.startsWith("hunter_") -> RootState.NeedsOnboarding
            else -> {
                lastPenaltySyncAt = System.currentTimeMillis()
                val gated = system.penaltyStatus()
                    ?.get("trial_active")?.jsonPrimitive?.booleanOrNull == true
                RootState.Ready(profile, gated)
            }
        }
    }

    /** §8.1 — foreground-resume audit (throttled): if the penalty gate flipped
     *  since the last sync, re-resolve so the hunter lands in the right world. */
    private var lastPenaltySyncAt = 0L
    fun resyncPenaltyIfStale() = viewModelScope.launch {
        val now = System.currentTimeMillis()
        if (now - lastPenaltySyncAt < 120_000) return@launch
        lastPenaltySyncAt = now
        val cur = _state.value
        if (cur is RootState.Ready) {
            val gated = system.penaltyStatus()
                ?.get("trial_active")?.jsonPrimitive?.booleanOrNull == true
            if (gated != cur.gated) resolve()
        }
    }

    fun signOut() = viewModelScope.launch { runCatching { auth.signOut() }; _state.value = RootState.NeedsOnboarding }
}

object Routes {
    const val MAIN = "main"
    const val CHAT = "chat"
    const val DM = "dm/{otherId}/{otherName}"
    const val BATTLE = "battle/{battleId}"
    const val TRAINING = "training"
    const val BLACK_ROOM = "blackroom"
    const val ADMIN = "admin"
    const val PROTOCOL = "protocol"
    const val ARENA_ADD = "arenaAdd"
    const val ROUTINE = "routine"
    const val AI_BENCHMARK = "aiBenchmark"
    const val SQUAD_COMMAND = "squadCommand"
    /** STEP 8: supply is a pushed route from the Vault, no longer a tab. */
    const val MARKET = "market"
    /** 0.22.0→0.24.0: VC vault (balance + immutable ledger), support inbox, tournament brackets. */
    const val WALLET = "wallet"
    const val SUPPORT = "support"
    const val TOURNAMENTS = "tournaments"
    const val CHESS_GAME = "chessGame/{mode}?lesson={lesson}"
    const val CHESS_PUZZLES = "chessPuzzles/{daily}"
    const val CHESS_LESSONS = "chessLessons"
    const val CHESS_RANKINGS = "chessRankings"
    const val CHESS_SETTINGS = "chessSettings"
    /** 0.24.0: discipline engine control room (quest lock, porn shield, guards). */
    const val DISCIPLINE = "discipline"
    /** Verified proof session: every field is server content, not a client guess. */
    const val QUEST_PROOF =
        "quest/{questId}/{mode}/{exercise}/{target}/{seq}/{unit}/{rest}/{xp}/{title}"

    fun dm(otherId: String, otherName: String) = "dm/$otherId/$otherName"
    fun battle(id: String) = "battle/$id"
    fun chessGame(mode: String, lesson: Int = -1) =
        if (lesson >= 0) "chessGame/$mode?lesson=$lesson" else "chessGame/$mode"
    fun chessPuzzles(daily: Boolean) = "chessPuzzles/$daily"
    fun questProof(q: com.thesystem.app.data.model.QuestDto): String {
        val title = java.net.URLEncoder.encode(q.title, "UTF-8")
        val mode = q.verification.uppercase()
        val exercise = q.exerciseKind ?: when {
            q.title.contains("SQUAT", true) -> "SQUAT"
            q.title.contains("RUN", true) -> "RUN"
            else -> "PUSHUP"
        }
        return "quest/${q.id}/$mode/$exercise/${q.targetValue}/${q.seq}/${q.targetUnit}/${q.restSec}/${q.xpReward}/$title"
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    companion object {
        /** Discipline overlays deep-link here: "quests" → daily quest dashboard. */
        const val EXTRA_OPEN = "open"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Paints the white system mark on black at process start — first frame
        // is no longer a multi-second dead black screen on cold launch.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // §1.1 (2026-10-20 spec): the Android system nav bar can never render a
        // solid white slab again — fully transparent, contrast enforcement off,
        // icons stay light over our pitch-black bottom bar. App content is laid
        // out edge-to-edge; the outermost bottom rail pads the nav inset.
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        @Suppress("DEPRECATION")
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
            .isAppearanceLightNavigationBars = false
        // §1.1: NO immersive auto-hide — the Android system nav bar stays
        // permanently visible; screens pad their own insets. (2026-10-16 spec)
        setContent {
            SystemTheme {
                val vm: RootViewModel = hiltViewModel()
                val state by vm.state.collectAsStateWithLifecycle()
                // §2 (v0.25.0): first-ever launch opens with the glowing camera-tracked
                // master cinematic (biometric → discipline lock → F→SS matrix) — the
                // old "SYSTEM DETECTED" radar is permanently retired (spec §1).
                val appContext = androidx.compose.ui.platform.LocalContext.current
                var masterIntroPending by remember {
                    mutableStateOf(com.thesystem.app.core.LaunchPrefs.masterIntroPending(appContext))
                }
                if (masterIntroPending) {
                    com.thesystem.app.ui.motion.SystemMasterIntroCinematics(
                        onEnterSystem = {
                            com.thesystem.app.core.LaunchPrefs.markMasterIntroSeen(appContext)
                            masterIntroPending = false
                        },
                    )
                } else {
                // ROUND 7: cinematic launch flow — unregistered vessels walk the full
                // intake → awakening → contract → evaluation → auth gate ritual.
                // Signed-in hunters get the shuffled brand splash and go straight in.
                val splashVariants = remember { com.thesystem.app.ui.splash.HONEST_SPLASH_ROTATION.shuffled() }
                when (val s = state) {
                    RootState.Booting -> DynamicSplash(variants = splashVariants)
                    RootState.NeedsOnboarding -> AwakeningFlowScreen(onDone = { vm.resolve() })
                    is RootState.Ready -> {
                        // §8.1: every foreground resume re-audits the penalty ledger (throttled)
                        androidx.lifecycle.compose.LifecycleResumeEffect(Unit) {
                            vm.resyncPenaltyIfStale()
                            onPauseOrDispose { }
                        }
                        if (s.gated) {
                            // §8.4 — while a trial stands, this crimson gate IS the app
                            PenaltyGateHost(onLifted = { vm.resolve() })
                        } else {
                            AppNavHost(profile = s, onSignOut = { vm.signOut() })
                        }
                    }
                    is RootState.Error -> {
                        // offline tolerance: brand screen stays alive; the tap re-resolves
                        val err = s
                        Box(Modifier.fillMaxSize().clickable { vm.resolve() }) {
                            DynamicSplash(variants = splashVariants)
                            Column(
                                Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Text(err.message.take(120), color = FaintGray, style = MonoLabel, textAlign = TextAlign.Center)
                                Spacer(Modifier.height(6.dp))
                                Text("SIGNAL LOST — TAP TO RE-SYNC", color = PaperWhite, style = MonoLabel)
                            }
                        }
                    }
                }
                } // §2 master-intro else-gate
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
        composable(Routes.TRAINING) {
            com.thesystem.app.ui.training.TrainingCatalogScreen(
                onBack = { nav.popBackStack() },
                onOpenBlackRoom = { nav.navigate(Routes.BLACK_ROOM) },
            )
        }
        composable(Routes.BLACK_ROOM) {
            com.thesystem.app.ui.training.BlackRoomScreen(onBack = { nav.popBackStack() })
        }
        composable(Routes.QUEST_PROOF) { QuestProofScreen(onExit = { nav.popBackStack() }) }
        composable(Routes.ROUTINE) { com.thesystem.app.ui.assistant.RoutineScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.AI_BENCHMARK) { com.thesystem.app.ui.assistant.AiBenchmarkScreen(onBack = { nav.popBackStack() }) }
        composable(Routes.SQUAD_COMMAND) { com.thesystem.app.ui.squad.SquadScreen(nav = nav) }
        composable(Routes.MARKET) { com.thesystem.app.ui.market.MarketScreen(nav = nav) }
        composable(Routes.WALLET) { com.thesystem.app.ui.wallet.WalletScreen(nav = nav) }
        composable(Routes.SUPPORT) { com.thesystem.app.ui.support.SupportScreen(nav = nav) }
        composable(Routes.TOURNAMENTS) { com.thesystem.app.ui.tournaments.TournamentsScreen(nav = nav) }
        composable(
            route = Routes.CHESS_GAME,
            arguments = listOf(
                androidx.navigation.navArgument("lesson") {
                    type = androidx.navigation.NavType.IntType
                    defaultValue = -1
                },
            ),
        ) { back ->
            com.thesystem.app.ui.chess.ChessGameScreen(
                modeName = back.arguments?.getString("mode"),
                lesson = back.arguments?.getInt("lesson") ?: -1,
                onBack = { nav.popBackStack() },
                onMode = { m -> nav.navigate(Routes.chessGame(m)) },
            )
        }
        composable(Routes.CHESS_PUZZLES) { back ->
            com.thesystem.app.ui.chess.ChessPuzzleScreen(
                daily = back.arguments?.getString("daily") == "true",
                onBack = { nav.popBackStack() },
                onToggleDaily = { d -> nav.navigate(Routes.chessPuzzles(d)) },
            )
        }
        composable(Routes.CHESS_LESSONS) {
            com.thesystem.app.ui.chess.ChessLessonsScreen(nav = nav)
        }
        composable(Routes.CHESS_RANKINGS) {
            com.thesystem.app.ui.chess.ChessRankingsScreen(nav = nav)
        }
        composable(Routes.CHESS_SETTINGS) {
            com.thesystem.app.ui.chess.ChessSettingsScreen(nav = nav)
        }
        composable(Routes.DISCIPLINE) {
            com.thesystem.app.ui.discipline.DisciplineScreen(nav = nav)
        }
    }
}

/** §8 — while a penalty trial stands, this tiny two-destination world replaces
 *  the whole app: the crimson gate + the camera-verified execution chamber. */
@Composable
private fun PenaltyGateHost(onLifted: () -> Unit) {
    val nav: NavHostController = rememberNavController()
    NavHost(navController = nav, startDestination = "penaltyZone") {
        composable("penaltyZone") {
            com.thesystem.app.ui.penalty.PenaltyGateScreen(nav = nav, onLifted = onLifted)
        }
        composable(Routes.QUEST_PROOF) { QuestProofScreen(onExit = { nav.popBackStack() }) }
    }
}
