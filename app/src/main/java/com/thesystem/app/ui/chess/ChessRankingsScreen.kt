package com.thesystem.app.ui.chess

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.thesystem.app.core.theme.Grid
import com.thesystem.app.core.theme.LabelGray
import com.thesystem.app.core.theme.LineSoft
import com.thesystem.app.core.theme.MonoLabel
import com.thesystem.app.core.theme.PaperWhite
import com.thesystem.app.core.theme.SkyBlue
import com.thesystem.app.core.theme.SystemMono
import com.thesystem.app.core.ui.FloatingIconButton
import com.thesystem.app.core.ui.SectionTitle
import com.thesystem.app.core.ui.SystemBackground
import com.thesystem.app.core.ui.SystemProcessing
import com.thesystem.app.data.model.ChessSessionDto
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private fun String.modeTitle(): String =
    lowercase(Locale.ROOT).split('_').joinToString(" ") { w ->
        w.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
    }

private fun sessionDate(iso: String): String = runCatching {
    OffsetDateTime.parse(iso).format(DateTimeFormatter.ofPattern("dd MMM · HH:mm", Locale.ROOT))
}.getOrDefault(iso.take(10))

/**
 * RANKINGS — the actual chess leaderboard data that exists today: the
 * hunter's server-owned numbers (rating, rank, W/D/L, puzzles) and recent
 * session proofs. The PUBLIC ladder is honestly sealed until the first war
 * season — no invented names, ever. Data + RPCs untouched; this screen only
 * reads what mig 018/019 already store.
 */
@Composable
fun ChessRankingsScreen(nav: NavHostController, vm: ChessViewModel = hiltViewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    var sessions by remember { mutableStateOf<List<ChessSessionDto>>(emptyList()) }
    var sessionsLoaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        sessions = runCatching { vm.repoRecentSessions(15) }.getOrDefault(emptyList())
        sessionsLoaded = true
    }
    val p = s.profile

    SystemBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = Grid.Margin)) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = Grid.S12),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FloatingIconButton(Icons.Default.ArrowBack, "back") { nav.popBackStack() }
                Spacer(Modifier.width(10.dp))
                Text("Rankings", color = PaperWhite, fontSize = 17.sp, fontWeight = FontWeight.Bold)
            }

            LazyColumn(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(Grid.S12),
                contentPadding = PaddingValues(bottom = Grid.S16),
            ) {
                // ── YOUR STANDING — server numbers only ──────────────────
                item {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(ChessCardShape)
                            .background(ChessCardSurface)
                            .border(1.dp, ChessCardLine, ChessCardShape)
                            .padding(16.dp),
                    ) {
                        Row(verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) {
                                Text("Competitive rating", style = MonoLabel, color = SkyBlue)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "${p?.rating ?: 400}",
                                    color = PaperWhite, fontSize = 34.sp,
                                    fontWeight = FontWeight.Bold, fontFamily = SystemMono,
                                )
                                Text(
                                    "Rank ${p?.mentalRank ?: "F"} · Mental LV ${p?.mentalLevel ?: 1}",
                                    color = LabelGray, fontSize = 12.sp,
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text(
                                    "${p?.wins ?: 0}W / ${p?.draws ?: 0}D / ${p?.losses ?: 0}L",
                                    color = PaperWhite, fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold, fontFamily = SystemMono,
                                )
                                Text(
                                    "Practice Elo ${p?.practiceElo ?: 400}",
                                    color = LabelGray, fontSize = 12.sp,
                                )
                                Text(
                                    "${p?.puzzlesSolved ?: 0} puzzles solved",
                                    color = SkyBlue, fontSize = 12.sp,
                                )
                            }
                        }
                    }
                }

                // ── honest ladder note — why no public names are listed ──
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .border(1.dp, LineSoft, ChessCardShape)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(
                            "Public ladder sealed until the first war season — your number is real, the field arrives with the neural link.",
                            color = LabelGray, fontSize = 12.sp, lineHeight = 17.sp,
                        )
                    }
                }

                item { SectionTitle("RECENT SESSIONS", SkyBlue) }

                if (!sessionsLoaded) {
                    item { SystemProcessing("LOADING SESSIONS", compact = true) }
                } else if (sessions.isEmpty()) {
                    item {
                        Text(
                            "No sessions found. Play an engine game or solve puzzles — proofs appear here after your first session.",
                            color = LabelGray, fontSize = 12.sp, lineHeight = 17.sp,
                        )
                    }
                } else {
                    items(sessions, key = { it.id }) { session ->
                        SessionRow(session)
                    }
                }
            }
        }
    }
}

@Composable
private fun SessionRow(session: ChessSessionDto) {
    val resultColor = when (session.result.uppercase(Locale.ROOT)) {
        "WIN", "SOLVED" -> SkyBlue
        "DRAW" -> LabelGray
        else -> PaperWhite
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(ChessCardShape)
            .background(ChessCardSurface)
            .border(1.dp, ChessCardLine, ChessCardShape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                session.mode.modeTitle(),
                color = PaperWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            )
            Text(sessionDate(session.createdAt), color = LabelGray, fontSize = 11.sp)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                session.result.uppercase(Locale.ROOT),
                color = resultColor, fontSize = 12.sp,
                fontWeight = FontWeight.Bold, fontFamily = SystemMono,
            )
            val detail = buildString {
                session.accuracy?.let { append("${it.toInt()}% acc") }
                session.ratingAfter?.let { if (isNotEmpty()) append(" · "); append("→ $it") }
                if (session.xpGained > 0) { if (isNotEmpty()) append(" · "); append("+${session.xpGained} XP") }
            }
            if (detail.isNotEmpty()) Text(detail, color = LabelGray, fontSize = 11.sp)
        }
    }
}
