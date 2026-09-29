package com.thesystem.app.ui.assistant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*

/**
 * AI BENCHMARK + MODEL BAY (spec §22, master prompt §13–16) — the AI status
 * page. Capability probes, remote flags, explicit model bundles (GET with an
 * honest progress bar, DELETE), counters. Honesty law: engine names shown
 * here are EXACTLY the bundles on disk — never decorative text.
 */
@Composable
fun AiBenchmarkScreen(
    onBack: () -> Unit,
    vm: AiBenchmarkViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val haptics = rememberSystemHaptics()

    SystemBackground {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = Grid.Margin)
                .verticalScroll(rememberScrollState()),
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = Grid.S16), verticalAlignment = Alignment.CenterVertically) {
                FloatingIconButton(Icons.Default.ArrowBack, "back", onClick = onBack)
                Spacer(Modifier.width(12.dp))
                Text("AI STATUS", color = PaperWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
                FloatingIconButton(Icons.Default.Refresh, "refresh") { haptics.select(); vm.refresh() }
            }

            // ── active brain — the honest answer to "who is thinking" ──
            GlowCard(Modifier.fillMaxWidth()) {
                SectionTitle("ACTIVE BRAIN", SkyBlue)
                Spacer(Modifier.height(4.dp))
                Text(s.brainLine.ifBlank { "RULES ENGINE (deterministic)" }, color = PaperWhite, fontSize = 12.sp, fontFamily = SystemMono)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Local LLM ladder: tier probe → catalog artifact → verified bundle → MediaPipe runtime → VALIDATED answer. Every rung can only fall DOWN to the rules engine. The app never breaks if the model does.",
                    color = LabelGray, fontSize = 10.sp, fontFamily = SystemMono,
                )
            }
            Spacer(Modifier.height(10.dp))

            s.report?.let { r ->
                SectionTitle("DEVICE CAPABILITY")
                GlowCard(Modifier.fillMaxWidth()) {
                    BenchRow("TIER DECISION", r.tier.name)
                    BenchRow("RAM TOTAL / FREE", "${r.totalRamMb} MB / ${r.availRamMb} MB")
                    BenchRow("PRESSURE THRESHOLD", "${r.thresholdMb} MB")
                    BenchRow("LOW-RAM DEVICE", if (r.lowRamDevice) "YES" else "NO")
                    BenchRow("UNDER PRESSURE NOW", if (r.underPressure) "YES" else "NO")
                    BenchRow("SDK / ABI", "${r.sdk} / ${r.abis}")
                    BenchRow("ARM64", if (r.arm64) "YES" else "NO")
                    BenchRow("FREE STORAGE", "${r.freeStorageMb} MB")
                    BenchRow("THERMAL STATUS", "${r.thermalStatus}")
                    BenchRow("METERED NETWORK", if (s.onUnmeteredNet) "NO (unmetered)" else "YES — downloads blocked")
                }
                Spacer(Modifier.height(10.dp))
            }

            SectionTitle("REMOTE FLAGS (engine_config.ai)")
            GlowCard(Modifier.fillMaxWidth()) {
                BenchRow("local_ai_enabled", s.flags.localAiEnabled.toString())
                BenchRow("quest_ai", s.flags.questAiEnabled.toString())
                BenchRow("nutrition_ai", s.flags.nutritionAiEnabled.toString())
                BenchRow("assistant", s.flags.assistantEnabled.toString())
                BenchRow("routine_ai", s.flags.routineAiEnabled.toString())
                BenchRow("ctx / out tokens", "${s.flags.maxContextTokens} / ${s.flags.maxOutputTokens}")
                BenchRow("infer timeout", "${s.flags.inferenceTimeoutMs} ms")
                BenchRow("unload after", "${s.flags.unloadAfterMs} ms")
                BenchRow("calorie floor", "${s.flags.calorieFloor}")
            }
            Spacer(Modifier.height(10.dp))

            // ── MODEL BAY — explicit bundles, explicit consent ───────────────
            SectionTitle("MODEL BAY", SkyBlue)
            Text(
                "Bundles download ONLY when you tap GET — never in the background, never on metered data. sha256 pins verified before load. Rules engine always stays armed beneath.",
                color = LabelGray, fontSize = 10.sp, fontFamily = SystemMono,
            )
            Spacer(Modifier.height(6.dp))
            GlowCard(Modifier.fillMaxWidth()) {
                if (s.rows.isEmpty()) {
                    Text("CATALOG EMPTY — deterministic brains only", color = FaintGray, fontSize = 11.sp, fontFamily = SystemMono)
                }
                s.rows.forEach { row ->
                    ModelBayRow(
                        row = row,
                        onGet = { haptics.select(); vm.download(row.meta.id) },
                        onDelete = { haptics.error(); vm.delete(row.meta.id) },
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (s.modelsBytes > 0) {
                    BenchRow("DISK FOOTPRINT", "${s.modelsBytes / (1024 * 1024)} MB in aimodels/")
                }
            }
            Spacer(Modifier.height(10.dp))

            SectionTitle("COUNTERS (technical only — no content)")
            GlowCard(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
                if (s.metrics.isEmpty()) {
                    Text("NO AI EVENTS RECORDED YET", color = FaintGray, fontSize = 11.sp, fontFamily = SystemMono)
                }
                s.metrics.toSortedMap().forEach { (k, v) -> BenchRow(k, v) }
                if (s.lastEvent.isNotBlank()) BenchRow("last_event", s.lastEvent)
            }
            Spacer(Modifier.height(28.dp))
        }
    }
}

/** One catalog row: meta + source honesty + state + GET/DELETE actions. */
@Composable
private fun ModelBayRow(
    row: AiBenchmarkViewModel.ModelRow,
    onGet: () -> Unit,
    onDelete: () -> Unit,
) {
    val m = row.meta
    Column(
        Modifier
            .fillMaxWidth()
            .border(1.dp, if (row.state.name == "READY") SkyBlue.copy(alpha = 0.55f) else LineSoft, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(m.id, color = PaperWhite, fontSize = 12.sp, fontFamily = SystemMono, fontWeight = FontWeight.Bold)
                Text(
                    "${m.paramsM}M · ${m.quant} · ${m.sizeMb}MB · tier ${m.tier} · ${m.backend}",
                    color = LabelGray, fontSize = 10.sp, fontFamily = SystemMono,
                )
                if (m.source.isNotBlank()) {
                    Text("src: ${m.source}", color = FaintGray, fontSize = 9.sp, fontFamily = SystemMono)
                }
            }
            Text(
                when (row.state.name) {
                    "READY" -> "READY"
                    "CORRUPT" -> "CORRUPT"
                    else -> if (m.url == null) "NO-ARTIFACT" else "ABSENT"
                },
                color = if (row.state.name == "READY") SkyBlue else LabelGray,
                fontSize = 10.sp, fontFamily = SystemMono, fontWeight = FontWeight.Bold,
            )
        }
        Spacer(Modifier.height(4.dp))
        row.note?.let {
            Text(it, color = if (it.contains("READY") || it.contains("VERIFIED")) SkyBlue else LabelGray, fontSize = 10.sp, fontFamily = SystemMono)
            Spacer(Modifier.height(4.dp))
        }
        if (row.downloading && row.progress >= 0f) {
            // honest progress: bytes flowing, not a spinner
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .weight(1f).height(3.dp)
                        .background(InkBlack, RoundedCornerShape(2.dp)),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(row.progress.coerceIn(0f, 1f))
                            .background(SkyBlue, RoundedCornerShape(2.dp)),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("${(row.progress * 100).toInt()}%", color = SkyBlue, fontSize = 10.sp, fontFamily = SystemMono, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(4.dp))
        }
        if (!row.eligible && m.url != null) {
            Text("DEVICE TIER TOO LOW — catalog preserved, ladder refuses below ${m.tier}", color = FaintGray, fontSize = 9.sp, fontFamily = SystemMono)
        } else if (m.url != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (row.state.name != "READY") {
                    NeonButton(
                        if (row.downloading) "FETCHING…" else if (m.wifiRequired) "GET (WI-FI)" else "GET",
                        onGet,
                        color = SkyBlue,
                        enabled = !row.downloading,
                    )
                }
                if (row.state.name == "READY") {
                    GhostButton("DELETE", onDelete)
                }
            }
        }
    }
}

@Composable
private fun BenchRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = LabelGray, fontSize = 10.sp, fontFamily = SystemMono, modifier = Modifier.weight(1.2f))
        Text(value, color = PaperWhite, fontSize = 10.sp, fontFamily = SystemMono, modifier = Modifier.weight(1.6f))
    }
}
