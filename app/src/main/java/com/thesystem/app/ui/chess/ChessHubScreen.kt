package com.thesystem.app.ui.chess

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.thesystem.app.Routes
import com.thesystem.app.core.theme.Grid
import com.thesystem.app.core.theme.PaperWhite
import com.thesystem.app.core.ui.SystemBackground
import com.thesystem.app.core.ui.enterAnim
import com.thesystem.app.core.ui.rememberSystemHaptics
import kotlinx.coroutines.launch

/** One of the seven doors (spec: exactly seven cards, nothing else on home). */
private data class ChessDoor(
    val title: String,
    val sub: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
    val onOpen: () -> Unit,
)

/**
 * Tab 4 — SYSTEM CHESS home. PREMIUM REDESIGN (2026 spec): exactly seven
 * feature cards — Play Online, Play vs AI, Play with Friends, Chess Puzzles,
 * Chess Lessons, Rankings, Settings. No mental panels, no charts, no HUD
 * subtext. PLAY ONLINE is an honest sealed door: matchmaking does not exist
 * yet, so the card explains itself instead of faking a lobby.
 */
@Composable
fun ChessHubScreen(nav: NavHostController, vm: ChessViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val haptics = rememberSystemHaptics()
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    LaunchedEffect(s.notice, s.error) {
        if (s.notice != null || s.error != null) {
            if (s.error != null) haptics.error() else haptics.success()
            (s.notice ?: s.error)?.let { snack.showSnackbar(it); vm.clearNotice() }
        }
    }

    fun go(route: String) { haptics.select(); nav.navigate(route) }
    val doors = listOf(
        ChessDoor(
            "Play Online",
            "Offline — ships with the squad network",
            Icons.Default.Public,
            enabled = false,
        ) {
            haptics.error()
            scope.launch { snack.showSnackbar("Play Online is offline — matchmaking ships with the squad network. No fake lobby, ever.") }
        },
        ChessDoor("Play vs AI", "On-device engine · 4 levels", Icons.Default.SmartToy) {
            go(Routes.chessGame(ChessMode.AI_TRAINING.name))
        },
        ChessDoor("Play with Friends", "One board · two hunters", Icons.Default.People) {
            go(Routes.chessGame(ChessMode.FRIEND_MATCH.name))
        },
        ChessDoor("Chess Puzzles", "Adaptive pool + daily challenge", Icons.Default.Extension) {
            go(Routes.chessPuzzles(false))
        },
        ChessDoor("Chess Lessons", "Openings · endgames · drills", Icons.Default.School) {
            go(Routes.CHESS_LESSONS)
        },
        ChessDoor("Rankings", "Your rating · match history", Icons.Default.EmojiEvents) {
            go(Routes.CHESS_RANKINGS)
        },
        ChessDoor("Settings", "Engine identity · defaults", Icons.Default.Settings) {
            go(Routes.CHESS_SETTINGS)
        },
    )

    SystemBackground(wallpaperAlpha = 0.15f) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = Grid.Margin),
                verticalArrangement = Arrangement.spacedBy(Grid.S12),
                contentPadding = PaddingValues(vertical = Grid.S16),
            ) {
                item {
                    Box(Modifier.enterAnim(0)) {
                        Text(
                            "SYSTEM CHESS",
                            color = PaperWhite, fontSize = 17.sp,
                            fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp,
                        )
                    }
                }
                item {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        // responsive law: two columns whenever the width allows
                        // ≥116dp cards; single column on very narrow devices.
                        val rows = if (maxWidth >= 300.dp) doors.chunked(2) else doors.map { listOf(it) }
                        Column(verticalArrangement = Arrangement.spacedBy(Grid.S12)) {
                            rows.forEachIndexed { i, row ->
                                Box(Modifier.enterAnim(i + 1)) {
                                    if (row.size == 1) {
                                        // the odd card out (Settings) spans the row —
                                        // a full-width list row, never a dangling half.
                                        ChessRowCard(
                                            title = row[0].title,
                                            sub = row[0].sub,
                                            icon = row[0].icon,
                                            enabled = row[0].enabled,
                                            onClick = row[0].onOpen,
                                        )
                                    } else {
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(Grid.S12),
                                        ) {
                                            row.forEach { door ->
                                                ChessGridCard(
                                                    title = door.title,
                                                    sub = door.sub,
                                                    icon = door.icon,
                                                    enabled = door.enabled,
                                                    modifier = Modifier.weight(1f),
                                                    onClick = door.onOpen,
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
            Box(Modifier.fillMaxSize().statusBarsPadding(), contentAlignment = Alignment.BottomCenter) {
                SnackbarHost(snack)
            }
        }
    }
}
