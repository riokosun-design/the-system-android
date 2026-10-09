package com.thesystem.app.ui.chess

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.thesystem.app.Routes
import com.thesystem.app.core.theme.Grid
import com.thesystem.app.core.theme.PaperWhite
import com.thesystem.app.core.theme.SkyBlue
import com.thesystem.app.core.ui.FloatingIconButton
import com.thesystem.app.core.ui.SectionTitle
import com.thesystem.app.core.ui.SystemBackground
import com.thesystem.app.core.ui.rememberSystemHaptics
import java.util.Locale

private fun String.titleWords(): String =
    lowercase(Locale.ROOT).split(' ').joinToString(" ") { w ->
        w.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }

/**
 * CHESS LESSONS — the REAL curriculum: the shipped opening lines and endgame
 * positions as playable lessons. Tapping a lesson opens the actual engine at
 * that exact position (lesson index pins the FEN — no epoch-day rotation).
 * Drills route into the real adaptive puzzle system. Nothing simulated.
 */
@Composable
fun ChessLessonsScreen(nav: NavHostController) {
    val haptics = rememberSystemHaptics()

    fun open(route: String) { haptics.select(); nav.navigate(route) }

    SystemBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = Grid.Margin)) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = Grid.S12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FloatingIconButton(Icons.Default.ArrowBack, "back") { nav.popBackStack() }
                Spacer(Modifier.width(10.dp))
                Text("Chess Lessons", color = PaperWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }

            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(Grid.S12),
                contentPadding = PaddingValues(bottom = Grid.S16),
            ) {
                item { SectionTitle("OPENINGS — PLAY THE KEY SIDE", SkyBlue) }
                itemsIndexed(OPENINGS) { i, o ->
                    ChessRowCard(
                        title = "Lesson ${i + 1} — ${o.name.titleWords()}",
                        sub = "You play ${if (o.userWhite) "White" else "Black"} · Engine MEDIUM",
                        icon = Icons.Default.MenuBook,
                    ) { open(Routes.chessGame(ChessMode.OPENING_TRAINING.name, lesson = i)) }
                }

                item { SectionTitle("ENDGAMES — CONVERT THE ADVANTAGE", SkyBlue) }
                itemsIndexed(ENDGAMES) { i, e ->
                    ChessRowCard(
                        title = "Lesson ${OPENINGS.size + i + 1} — ${e.name}",
                        sub = "You play White · Engine MEDIUM",
                        icon = Icons.Default.MenuBook,
                    ) { open(Routes.chessGame(ChessMode.ENDGAME_TRAINING.name, lesson = i)) }
                }

                item { SectionTitle("DRILLS", SkyBlue) }
                item {
                    ChessRowCard(
                        title = "Puzzle drills",
                        sub = "Adaptive pool · follows your practice Elo",
                        icon = Icons.Default.Extension,
                    ) { open(Routes.chessPuzzles(false)) }
                }
                item {
                    ChessRowCard(
                        title = "Daily challenge",
                        sub = "One seed per day · one shot",
                        icon = Icons.Default.DateRange,
                    ) { open(Routes.chessPuzzles(true)) }
                }
            }
        }
    }
}
