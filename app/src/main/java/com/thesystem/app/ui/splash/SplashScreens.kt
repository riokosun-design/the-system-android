package com.thesystem.app.ui.splash

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thesystem.app.core.theme.CrimsonRed
import com.thesystem.app.core.theme.ElectricBlue
import com.thesystem.app.core.theme.HunterGold
import com.thesystem.app.core.theme.NeonPurple
import com.thesystem.app.core.theme.SurfaceDark
import com.thesystem.app.core.theme.TextMuted
import com.thesystem.app.core.theme.TextPrimary
import com.thesystem.app.core.ui.rememberSystemHaptics
import com.thesystem.app.data.model.LegalDocDto
import com.thesystem.app.ui.onboarding.ArchetypeCard
import com.thesystem.app.ui.onboarding.FingerprintHold
import com.thesystem.app.ui.onboarding.FlashRipple
import com.thesystem.app.ui.onboarding.GateKeyButton
import com.thesystem.app.ui.onboarding.GlitchRevealText
import com.thesystem.app.ui.onboarding.HoloStatusPanel
import com.thesystem.app.ui.onboarding.LegalFooter
import com.thesystem.app.ui.onboarding.OnboardingChip
import com.thesystem.app.ui.onboarding.OnboardingUiState
import com.thesystem.app.ui.onboarding.OnboardingViewModel
import com.thesystem.app.ui.onboarding.SystemBackdrop
import com.thesystem.app.ui.onboarding.TerminalLine
import com.thesystem.app.ui.onboarding.WheelRow
import com.thesystem.app.ui.onboarding.neonGlow
import kotlinx.coroutines.delay

// ═══════════════════════════════════════════════════════════════════════════
// SPLASH SCREENS — the cinematic launch flow, end to end.
//
//   VESSEL INTAKE (pre-splash info gather — name, age≥13 next stage, experience,
//                  biological flags, note)  →
//   STAGE 1 AWAKENING (glitch text + stat projection)  →
//   STAGE 2 CONTRACT GATE (fingerprint hold → flash ripple)  →
//   STAGE 3 STAT EVALUATION (wheel pickers + target archetype)  →
//   STAGE 4 HOLOGRAPHIC AUTH (S-Rank gate key → Google → finish)
//
// All UI state flows through the SplashUiState sealed interface; persistence,
// legal docs and Credential-Manager auth are delegated to OnboardingViewModel
// (already live-wired to Supabase — zero duplicated auth logic).
// ═══════════════════════════════════════════════════════════════════════════

// ── VESSEL DATA MODEL ────────────────────────────────────────────────────────

enum class Experience(val label: String, val hint: String) {
    NEW_RECRUIT("Fresh Vessel", "0–6 months of training"),
    IRREGULAR("Irregular", "trained on and off before"),
    SEASONED("Seasoned", "1+ year structured work"),
}

enum class BioFlag(val label: String) {
    LOW_EYESIGHT("Low eyesight"),
    ASTHMA("Asthma / breathing"),
    KNEE("Knee issue"),
    BACK("Back pain"),
    HEART("Heart condition"),
    NONE("All clear"),
}

enum class Archetype(val title: String, val tagline: String, val stat: String, val goal: String, val accent: Color) {
    SHADOW_MONARCH("Shadow Monarch", "Strength path — raw power ceiling", "STR +40% · TECH +20%", "STRENGTH", NeonPurple),
    WIND_WALKER("Wind Walker", "Agility path — lean, fast, ruthless", "SPD +40% · FAT -20%", "SHRED", ElectricBlue),
    TITAN("Titan", "Mass path — hypertrophy engine", "MASS +40% · STR +25%", "BULK", HunterGold),
}

data class VesselForm(
    val name: String = "",
    val experience: Experience = Experience.NEW_RECRUIT,
    val bioFlags: Set<BioFlag> = emptySet(),
    val note: String = "",
    val age: Int = 16,
    val heightCm: Int = 170,
    val weightKg: Int = 65,
    val archetype: Archetype = Archetype.SHADOW_MONARCH,
)

// ── UI STATE — the sealed machine ────────────────────────────────────────────

sealed interface SplashUiState {
    val form: VesselForm
    data class VesselIntake(override val form: VesselForm) : SplashUiState
    data class Awakening(override val form: VesselForm) : SplashUiState
    data class ContractGate(override val form: VesselForm) : SplashUiState
    data class Evaluation(override val form: VesselForm) : SplashUiState
    data class AuthGate(override val form: VesselForm) : SplashUiState
}

@Stable
class LaunchFlowState(initial: VesselForm = VesselForm()) {
    var ui by mutableStateOf<SplashUiState>(SplashUiState.VesselIntake(initial))
        private set

    fun updateForm(t: (VesselForm) -> VesselForm) {
        ui = when (val s = ui) {
            is SplashUiState.VesselIntake -> s.copy(form = t(s.form))
            is SplashUiState.Awakening -> s.copy(form = t(s.form))
            is SplashUiState.ContractGate -> s.copy(form = t(s.form))
            is SplashUiState.Evaluation -> s.copy(form = t(s.form))
            is SplashUiState.AuthGate -> s.copy(form = t(s.form))
        }
    }

    fun advance() {
        ui = when (val s = ui) {
            is SplashUiState.VesselIntake -> SplashUiState.Awakening(s.form)
            is SplashUiState.Awakening -> SplashUiState.ContractGate(s.form)
            is SplashUiState.ContractGate -> SplashUiState.Evaluation(s.form)
            is SplashUiState.Evaluation -> SplashUiState.AuthGate(s.form)
            is SplashUiState.AuthGate -> s
        }
    }
}

// ── ROOT ─────────────────────────────────────────────────────────────────────

@Composable
fun LaunchFlowScreen(
    onDone: () -> Unit,
    vm: OnboardingViewModel = hiltViewModel(),
) {
    val ob by vm.ui.collectAsStateWithLifecycle()
    val flow = remember { LaunchFlowState() }
    val haptics = rememberSystemHaptics()
    val ctx = LocalContext.current
    var flash by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) { vm.loadLegal() }
    // Google signature lands → claim username, persist metrics, finish onboarding
    LaunchedEffect(ob.signedInProfile) { if (ob.signedInProfile != null) vm.finish(onDone) }

    SystemBackdrop {
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = flow.ui,
                transitionSpec = { fadeIn(tween(650)) togetherWith fadeOut(tween(420)) },
                label = "launchFlow",
            ) { stage ->
                when (stage) {
                    is SplashUiState.VesselIntake -> VesselIntakeStage(
                        form = stage.form, ob = ob,
                        onName = { n -> flow.updateForm { it.copy(name = n) }; vm.onUsernameChanged(n) },
                        onForm = { t -> flow.updateForm(t) },
                        onContinue = { haptics.select(); flow.advance() },
                    )
                    is SplashUiState.Awakening -> AwakeningStage(onFinished = { flow.advance() })
                    is SplashUiState.ContractGate -> ContractGateStage(
                        haptics = haptics,
                        onUnlocked = { flash++; flow.advance() },
                    )
                    is SplashUiState.Evaluation -> EvaluationStage(
                        form = stage.form,
                        onForm = { t -> flow.updateForm(t) },
                        onContinue = { f ->
                            // mirror into the persistence VM — single source of truth before Google
                            vm.setAge(f.age); vm.setHeight(f.heightCm.toFloat()); vm.setWeight(f.weightKg.toFloat())
                            vm.setGoal(f.archetype.goal)
                            haptics.select(); flow.advance()
                        },
                    )
                    is SplashUiState.AuthGate -> AuthGateStage(
                        ob = ob,
                        onGoogle = { vm.signInWithGoogle(ctx) },
                    )
                }
            }
            FlashRipple(trigger = flash)
        }
    }
}

// ── STAGE 0: VESSEL INTAKE (BEFORE the splash — the registration) ════════════

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VesselIntakeStage(
    form: VesselForm,
    ob: OnboardingUiState,
    onName: (String) -> Unit,
    onForm: ((VesselForm) -> VesselForm) -> Unit,
    onContinue: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp),
    ) {
        Spacer(Modifier.height(34.dp))
        TerminalLine("REGISTERING VESSEL — INPUT REQUIRED", fontSize = 11)
        Spacer(Modifier.height(16.dp))
        Text("Ident 없음.", color = TextMuted, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
        Text("Who are you, hunter?", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(26.dp))

        // ── NAME: feeds live username availability check in OnboardingViewModel ──
        Text("HUNTER NAME", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = form.name,
            onValueChange = { if (it.length <= 24) onName(it) },
            singleLine = true,
            textStyle = TextStyle(color = TextPrimary, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(ElectricBlue),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth().neonGlow(ElectricBlue, alpha = 0.07f),
            decorationBox = { inner ->
                Column {
                    Box(Modifier.padding(vertical = 10.dp)) {
                        if (form.name.isBlank()) Text("e.g. shadow_monarch", color = TextMuted.copy(alpha = 0.45f), fontSize = 17.sp, fontFamily = FontFamily.Monospace)
                        inner()
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(ElectricBlue.copy(alpha = 0.55f)))
                }
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(30.dp)) {
            when {
                ob.usernameChecking -> CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = ElectricBlue)
                ob.usernameAvailable == true -> Text("✓ designation available", color = com.thesystem.app.core.theme.VenomGreen, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                ob.usernameAvailable == false -> Text("✗ ${ob.usernameError ?: "designation taken"}", color = CrimsonRed, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                else -> Text("The System checks availability live.", color = TextMuted, fontSize = 11.sp)
            }
        }

        // ── EXPERIENCE ──
        Spacer(Modifier.height(16.dp))
        Text("TRAINING EXPERIENCE", style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Experience.entries.forEach { ex ->
                OnboardingChip(
                    label = "${ex.label} — ${ex.hint}",
                    selected = form.experience == ex,
                    onClick = { onForm { it.copy(experience = ex) } },
                )
            }
        }

        // ── BIOLOGICAL CONSTRAINTS (multi) ──
        Spacer(Modifier.height(22.dp))
        Text("BIOLOGICAL CONSTRAINTS", style = MaterialTheme.typography.labelSmall)
        Text("The System adapts the protocol. Honesty is survival.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BioFlag.entries.forEach { flag ->
                val on = form.bioFlags.contains(flag)
                OnboardingChip(
                    label = flag.label,
                    selected = on,
                    accent = if (flag == BioFlag.NONE) com.thesystem.app.core.theme.VenomGreen else CrimsonRed,
                    onClick = {
                        onForm { f ->
                            val next = when {
                                flag == BioFlag.NONE -> setOf(BioFlag.NONE)
                                on -> f.bioFlags - flag
                                else -> (f.bioFlags - BioFlag.NONE) + flag
                            }
                            f.copy(bioFlags = next)
                        }
                    },
                )
            }
        }

        // ── NOTE ──
        Spacer(Modifier.height(22.dp))
        Text("ANYTHING THE SYSTEM SHOULD KNOW?", style = MaterialTheme.typography.labelSmall)
        Text("Injuries, medication, past surgery — optional.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(8.dp))
        BasicTextField(
            value = form.note,
            onValueChange = { if (it.length <= 140) onForm { f -> f.copy(note = it) } },
            textStyle = TextStyle(color = TextPrimary, fontSize = 14.sp),
            cursorBrush = SolidColor(ElectricBlue),
            maxLines = 3,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceDark.copy(alpha = 0.6f))
                .padding(14.dp),
            decorationBox = { inner ->
                Box { if (form.note.isBlank()) Text("Type here…", color = TextMuted.copy(alpha = 0.4f), fontSize = 14.sp); inner() }
            },
        )

        Spacer(Modifier.height(26.dp))
        GateKeyButton(
            text = "BEGIN AWAKENING",
            subtext = "STEP 1 OF 5 — VESSEL REGISTERED",
            enabled = ob.usernameAvailable == true,
            onClick = onContinue,
        )
        Spacer(Modifier.height(8.dp))
        Text("Age, height & weight calibrate in the evaluation chamber.", style = MaterialTheme.typography.bodyMedium, fontSize = 11.sp)
        Spacer(Modifier.height(30.dp))
    }
}

// ── STAGE 1: THE AWAKENING — glitch narrative + stat projection ══════════════

@Composable
private fun AwakeningStage(onFinished: () -> Unit) {
    var beat by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        delay(1900); beat = 1
        delay(2100); beat = 2
        delay(2100); beat = 3        // stat projection panel
        delay(4600); beat = 4        // flash "after" inside panel
        delay(2600); onFinished()
    }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 28.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        when (beat) {
            0 -> TerminalLine("DETECTING UNREGISTERED VESSEL…", fontSize = 14)
            1 -> GlitchRevealText("Is there a version of yourself\nyou dream of becoming?", fontSize = 22)
            2 -> GlitchRevealText("The System\nhas chosen you.", fontSize = 26, color = ElectricBlue)
            else -> StatProjectionPanel(after = beat >= 4)
        }
    }
}

@Composable
private fun StatProjectionPanel(after: Boolean) {
    Column(Modifier.fillMaxWidth()) {
        Text("STAT PROJECTION — 3 MONTH HORIZON", style = MaterialTheme.typography.labelSmall, color = TextMuted)
        Spacer(Modifier.height(14.dp))
        PanelRow("STRENGTH", if (after) 68 else 12, after)
        PanelRow("TECHNIQUE", if (after) 30 else 9, after)
        PanelRow("SPEED", if (after) 70 else 17, after)
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TAG: ", style = MaterialTheme.typography.labelSmall, color = TextMuted)
            if (after) {
                Text(
                    "A MONSTER WHO SURPASSES THE WORLD",
                    color = HunterGold, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.neonGlow(HunterGold, alpha = 0.18f),
                )
            } else {
                Text("USELESS", color = CrimsonRed, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun PanelRow(label: String, value: Int, hot: Boolean) {
    val accent = if (hot) ElectricBlue else TextMuted
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 7.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = TextMuted, modifier = Modifier.weight(1f))
        Box(Modifier.weight(1.6f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(SurfaceDark)) {
            Box(
                Modifier
                    .fillMaxWidth((value / 100f).coerceIn(0.04f, 1f))
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (hot) ElectricBlue else TextMuted.copy(alpha = 0.4f)),
            )
        }
        Text(
            value.toString().padStart(3, ' '), color = accent, fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(start = 14.dp),
        )
    }
}

// ── STAGE 2: BIOMETRIC CONTRACT GATE ═════════════════════════════════════════

@Composable
private fun ContractGateStage(haptics: com.thesystem.app.core.ui.SystemHaptics, onUnlocked: () -> Unit) {
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(70.dp))
        TerminalLine("BIOMETRIC SIGNATURE REQUIRED", fontSize = 11)
        Spacer(Modifier.height(18.dp))
        GlitchRevealText("Sign the contract.", fontSize = 26, color = ElectricBlue)
        Spacer(Modifier.height(10.dp))
        Text(
            "Hold your mark. 1.5 seconds binds you\nto The System. Release early — void.",
            style = MaterialTheme.typography.bodyMedium, fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        FingerprintHold(haptics = haptics, onHoldComplete = onUnlocked)
        Spacer(Modifier.weight(1.2f))
    }
}

// ── STAGE 3: STAT EVALUATION WIZARD ══════════════════════════════════════════

@Composable
private fun EvaluationStage(
    form: VesselForm,
    onForm: ((VesselForm) -> VesselForm) -> Unit,
    onContinue: (VesselForm) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 26.dp),
    ) {
        Spacer(Modifier.height(30.dp))
        TerminalLine("STAT EVALUATION CHAMBER", fontSize = 11)
        Spacer(Modifier.height(14.dp))
        Text("Calibrate the vessel.", style = MaterialTheme.typography.displayMedium)
        Text("The System punishes lies. Pick true numbers.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(26.dp))

        WheelRow("AGE", "MIN 13 — HARD FLOOR", 13..80, form.age, { v -> onForm { it.copy(age = v) } })
        Spacer(Modifier.height(18.dp))
        WheelRow("HEIGHT", "CM", 140..210, form.heightCm, { v -> onForm { it.copy(heightCm = v) } })
        Spacer(Modifier.height(18.dp))
        WheelRow("WEIGHT", "KG", 40..150, form.weightKg, { v -> onForm { it.copy(weightKg = v) } })

        Spacer(Modifier.height(30.dp))
        Text("TARGET ARCHETYPE", style = MaterialTheme.typography.labelSmall)
        Text("Your training engine reshapes around this choice.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(12.dp))
        Archetype.entries.forEach { a ->
            ArchetypeCard(
                title = a.title, tagline = a.tagline, stat = a.stat, accent = a.accent,
                selected = form.archetype == a,
                onClick = { onForm { it.copy(archetype = a) } },
            )
            Spacer(Modifier.height(10.dp))
        }

        Spacer(Modifier.height(16.dp))
        GateKeyButton(text = "FORGE MY PATH", subtext = "STEP 4 OF 5 — ARCHETYPE ${form.archetype.title.uppercase()}", onClick = { onContinue(form) })
        Spacer(Modifier.height(30.dp))
    }
}

// ── STAGE 4: HOLOGRAPHIC AUTH & GATE ═════════════════════════════════════════

@Composable
private fun AuthGateStage(ob: OnboardingUiState, onGoogle: () -> Unit) {
    var doc by remember { mutableStateOf<LegalDocDto?>(null) }
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 26.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(44.dp))
        MonarchMark()
        Spacer(Modifier.height(30.dp))
        HoloStatusPanel(status = if (ob.signingIn) "SAVING DATA…" else "AWAITING SIGNATURE")
        if (ob.authError != null) {
            Spacer(Modifier.height(12.dp))
            Text(ob.authError, color = CrimsonRed, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
        }
        Spacer(Modifier.weight(1f))
        GateKeyButton(
            text = "CONTINUE WITH GOOGLE",
            subtext = "S-RANK GATE KEY",
            loading = ob.signingIn,
            enabled = !ob.signingIn,
            onClick = onGoogle,
        )
        Spacer(Modifier.height(18.dp))
        LegalFooter(
            onTerms = { doc = ob.termsDoc },
            onPrivacy = { doc = ob.privacyDoc },
        )
        Spacer(Modifier.height(22.dp))
    }
    // Protocol sheet — docs arrive live from the backend (legal_documents table)
    doc?.let { d ->
        AlertDialog(
            onDismissRequest = { doc = null },
            title = { Text(d.title, color = ElectricBlue, fontSize = 16.sp) },
            text = {
                Text(
                    d.contentMarkdown.take(4000),
                    color = TextPrimary, fontSize = 12.sp, lineHeight = 18.sp,
                    modifier = Modifier.verticalScroll(rememberScrollState()).heightIn(max = 420.dp),
                )
            },
            confirmButton = { TextButton(onClick = { doc = null }) { Text("ACKNOWLEDGED", color = ElectricBlue) } },
            containerColor = SurfaceDark,
        )
    }
}

/** Compact monarch mark — crown spikes + the stare. */
@Composable
private fun MonarchMark() {
    androidx.compose.foundation.Canvas(Modifier.size(150.dp, 120.dp)) {
        val w = size.width; val h = size.height
        val p = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.50f, h * 0.10f); lineTo(w * 0.44f, h * 0.30f); lineTo(w * 0.30f, h * 0.22f)
            lineTo(w * 0.33f, h * 0.52f); lineTo(w * 0.40f, h * 0.50f); lineTo(w * 0.38f, h * 0.72f)
            lineTo(w * 0.62f, h * 0.72f); lineTo(w * 0.60f, h * 0.50f); lineTo(w * 0.67f, h * 0.52f)
            lineTo(w * 0.70f, h * 0.22f); lineTo(w * 0.56f, h * 0.30f); close()
        }
        drawPath(p, Color(0xFF10141C))
        drawCircle(NeonPurple, radius = w * 0.30f, center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.42f), alpha = 0.08f)
        drawCircle(ElectricBlue, radius = 4f, center = androidx.compose.ui.geometry.Offset(w * 0.40f, h * 0.52f), alpha = 0.9f)
        drawCircle(ElectricBlue, radius = 4f, center = androidx.compose.ui.geometry.Offset(w * 0.60f, h * 0.52f), alpha = 0.9f)
    }
}
