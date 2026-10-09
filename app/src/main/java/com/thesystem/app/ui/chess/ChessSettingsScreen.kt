package com.thesystem.app.ui.chess

import android.content.Context
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.thesystem.app.core.theme.Grid
import com.thesystem.app.core.theme.LabelGray
import com.thesystem.app.core.theme.LineSoft
import com.thesystem.app.core.theme.MonoLabel
import com.thesystem.app.core.theme.PaperWhite
import com.thesystem.app.core.theme.SkyBlue
import com.thesystem.app.core.ui.FloatingIconButton
import com.thesystem.app.core.ui.GhostButton
import com.thesystem.app.core.ui.SectionTitle
import com.thesystem.app.core.ui.SystemBackground
import com.thesystem.app.core.ui.rememberSystemHaptics

private const val PREFS = "chess_prefs"
private const val PUZZLE_PREFS = "chess_puzzle_prefs"

/**
 * CHESS SETTINGS — only REAL controls live here: the persisted default engine
 * level (consumed by PLAY vs AI pre-match), honest engine/coach/pieces
 * identity, and a local puzzle-adaptation reset (client memory only — server
 * records are never touched).
 */
@Composable
fun ChessSettingsScreen(nav: NavHostController) {
    val haptics = rememberSystemHaptics()
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val puzzlePrefs = remember { context.getSharedPreferences(PUZZLE_PREFS, Context.MODE_PRIVATE) }
    var defaultDiff by remember { mutableIntStateOf(prefs.getInt("default_diff", 1).coerceIn(0, 3)) }
    var resetConfirm by remember { mutableStateOf(false) }
    var resetDone by remember { mutableStateOf(false) }

    if (resetConfirm) {
        AlertDialog(
            onDismissRequest = { resetConfirm = false },
            title = { Text("Reset puzzle adaptation?", color = PaperWhite, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Clears the local puzzle memory (seen/solved tracking) on this device. Server-side ratings and puzzle records stay untouched.",
                    color = LabelGray, fontSize = 13.sp, lineHeight = 18.sp,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    puzzlePrefs.edit().remove("director_records").apply()
                    resetConfirm = false
                    resetDone = true
                    haptics.success()
                }) { Text("RESET", color = SkyBlue, fontWeight = FontWeight.Bold) }
            },
            dismissButton = {
                TextButton(onClick = { resetConfirm = false }) { Text("CANCEL", color = LabelGray) }
            },
        )
    }

    SystemBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = Grid.Margin)) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = Grid.S12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FloatingIconButton(Icons.Default.ArrowBack, "back") { nav.popBackStack() }
                Spacer(Modifier.width(10.dp))
                Text("Settings", color = PaperWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Grid.S12),
            ) {
                // DEFAULT ENGINE LEVEL — a real, persisted preference
                SectionTitle("DEFAULT ENGINE LEVEL", SkyBlue)
                Column(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, ChessCardLine, ChessCardShape)
                        .padding(14.dp),
                ) {
                    Text(
                        "Used when you start Play vs AI.",
                        color = LabelGray, fontSize = 12.sp,
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        com.thesystem.app.chess.ChessAI.Difficulty.entries.forEachIndexed { i, d ->
                            Text(
                                d.label,
                                color = if (i == defaultDiff) SkyBlue else LabelGray,
                                fontSize = 10.sp,
                                fontWeight = if (i == defaultDiff) FontWeight.Bold else FontWeight.Normal,
                                letterSpacing = 1.2.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .weight(1f)
                                    .border(
                                        1.dp,
                                        if (i == defaultDiff) SkyBlue else LineSoft,
                                        RoundedCornerShape(6.dp),
                                    )
                                    .clickable {
                                        defaultDiff = i
                                        prefs.edit().putInt("default_diff", i).apply()
                                        haptics.select()
                                    }
                                    .padding(vertical = 9.dp),
                            )
                        }
                    }
                }

                // ENGINE IDENTITY — honest attribution, never invented models
                SectionTitle("ENGINE & PIECES", SkyBlue)
                ChessInfoBlock(
                    "Engine",
                    "System Engine α-β v1 — on-device negamax search with piece-square-table eval. No cloud model.",
                )
                ChessInfoBlock(
                    "Coach",
                    "System Core rules — deterministic post-game analysis. No LLM connected.",
                )
                ChessInfoBlock(
                    "Board pieces",
                    "cburnett / Wikimedia Commons · CC BY-SA 3.0 (NOTICE.md).",
                )

                // LOCAL DATA — puzzle adaptation memory reset (honest scope)
                SectionTitle("LOCAL DATA", SkyBlue)
                GhostButton(
                    if (resetDone) "PUZZLE MEMORY CLEARED" else "RESET PUZZLE ADAPTATION",
                    { if (!resetDone) { haptics.select(); resetConfirm = true } },
                    Modifier.fillMaxWidth(),
                )
                Text(
                    "Clears on-device puzzle memory only. Ratings and records on the server are never touched.",
                    style = MonoLabel, color = LabelGray,
                )

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
