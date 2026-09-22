package com.thesystem.app.ui.assistant

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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
 * AI BENCHMARK (spec §22) — ADMIN/DEBUG ONLY (entry gated in Profile, not
 * reachable from normal navigation). Technical metrics; zero personal content.
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
                Text("AI BENCHMARK", color = PaperWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp, modifier = Modifier.weight(1f))
                FloatingIconButton(Icons.Default.Refresh, "refresh") { haptics.select(); vm.refresh() }
            }

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
                }
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

            SectionTitle("MODEL CATALOG")
            GlowCard(Modifier.fillMaxWidth()) {
                if (s.modelStates.isEmpty()) {
                    Text("CATALOG EMPTY — deterministic brains only", color = FaintGray, fontSize = 11.sp, fontFamily = SystemMono)
                }
                s.modelStates.forEach { (m, st) ->
                    BenchRow(
                        m.id,
                        "${m.paramsM}M · ${m.quant} · ${m.sizeMb}MB · ${m.tier} · $st" + if (m.url == null) " · NO-ARTIFACT" else "",
                    )
                }
            }

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

@Composable
private fun BenchRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = LabelGray, fontSize = 10.sp, fontFamily = SystemMono, modifier = Modifier.weight(1.2f))
        Text(value, color = PaperWhite, fontSize = 10.sp, fontFamily = SystemMono, modifier = Modifier.weight(1.6f))
    }
}
