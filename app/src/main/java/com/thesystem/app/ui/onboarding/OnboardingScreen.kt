package com.thesystem.app.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import com.thesystem.app.ui.splash.DynamicSplash
import com.thesystem.app.ui.splash.SplashVariant

private const val PAGE_INTRO = 0
private const val PAGE_LEGAL = 1
private const val PAGE_AGE = 2
private const val PAGE_HEIGHT = 3
private const val PAGE_WEIGHT = 4
private const val PAGE_GOAL = 5
private const val PAGE_VALUE = 6     // what THE SYSTEM does for you
private const val PAGE_AUTH = 7      // Google gate
private const val PAGE_USERNAME = 8  // unique @handle
private const val PAGE_REFERRAL = 9

/**
 * Onboarding Wizard (Section 2):
 * dynamic splash → server-fetched legal docs (no hardcoded text) → metrics (pages 4–7 equivalent)
 * → "Continue with Google" → auto display name → unique @username gate → optional referral.
 */
@Composable
fun OnboardingScreen(onDone: () -> Unit, vm: OnboardingViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current

    SystemBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(20.dp)) {
            if (ui.page > PAGE_INTRO) {
                // progress rail
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    repeat(10) { i ->
                        val seg by animateColorAsState(if (i <= ui.page) ElectricBlue else SurfaceHigh, label = "rail$i")
                        Box(
                            Modifier.weight(1f).height(3.dp)
                                .background(seg, RoundedCornerShape(2.dp))
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            AnimatedContent(
                targetState = ui.page,
                transitionSpec = {
                    (slideInHorizontally(androidx.compose.animation.core.tween(450)) { it / 3 } + fadeIn(SystemMotion.snap))
                        .togetherWith(slideOutHorizontally(androidx.compose.animation.core.tween(450)) { -it / 3 } + fadeOut(SystemMotion.snap))
                },
                label = "wizard",
            ) { page ->
            when (page) {
                PAGE_INTRO -> IntroPage { vm.setPage(PAGE_LEGAL) }
                PAGE_LEGAL -> Column(Modifier.fillMaxSize()) { LegalPage(ui, vm) }
                PAGE_AGE -> MetricPage(
                    title = "AGE VERIFICATION", subtitle = "THE SYSTEM calibrates difficulty to your biology.",
                    value = "${ui.age}", onNext = { vm.setPage(PAGE_HEIGHT) },
                ) {
                    Slider(value = ui.age.toFloat(), onValueChange = { vm.setAge(it.toInt()) }, valueRange = 13f..80f)
                }
                PAGE_HEIGHT -> MetricPage(
                    title = "HEIGHT", subtitle = "Used to normalize training arcs and form scaling.",
                    value = "${ui.heightCm.toInt()} cm", onNext = { vm.setPage(PAGE_WEIGHT) },
                ) {
                    Slider(value = ui.heightCm, onValueChange = { vm.setHeight(it) }, valueRange = 120f..220f)
                }
                PAGE_WEIGHT -> MetricPage(
                    title = "WEIGHT", subtitle = "Baseline for the bio-mechanics engine.",
                    value = "%.1f kg".format(ui.weightKg), onNext = { vm.setPage(PAGE_GOAL) },
                ) {
                    Slider(value = ui.weightKg, onValueChange = { vm.setWeight(it) }, valueRange = 35f..180f)
                }
                PAGE_GOAL -> GoalPage(ui.goal, vm::setGoal) { vm.setPage(PAGE_VALUE) }
                PAGE_VALUE -> ValuePage { vm.setPage(PAGE_AUTH) }
                PAGE_AUTH -> AuthPage(ui, onGoogle = { vm.signInWithGoogle(context) })
                PAGE_USERNAME -> UsernamePage(ui, vm)
                PAGE_REFERRAL -> ReferralPage(ui, vm, onDone)
            }
            }
        }
    }
}

@Composable
private fun IntroPage(onBegin: () -> Unit) {
    val haptics = rememberSystemHaptics()
    Box(Modifier.fillMaxSize()) {
        DynamicSplash(
            variants = listOf(SplashVariant.RANK_BARS, SplashVariant.BLACK_ROOM_SCAN, SplashVariant.TERRITORY_GRID),
            showBranding = true,
        )
        NeonButton("AWAKEN", { haptics.slam(); onBegin() }, Modifier.align(Alignment.BottomCenter).padding(bottom = 120.dp).fillMaxWidth(0.7f))
    }
}

@Composable
private fun ColumnScope.LegalPage(ui: OnboardingUiState, vm: OnboardingViewModel) {
    val haptics = rememberSystemHaptics()
    Text("LEGAL PROTOCOL", style = MaterialTheme.typography.headlineMedium, color = ElectricBlue)
    Text("Fetched live from THE SYSTEM. Super Admins can republish these at any time — you will be asked to re-accept on version change.",
        style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(12.dp))
    when {
        ui.legalLoading -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = ElectricBlue) }
        ui.legalError != null -> Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
            RestrictionBanner(ui.legalError)
            Spacer(Modifier.height(12.dp))
            NeonButton("RETRY", vm::loadLegal, Modifier.fillMaxWidth(), color = CrimsonRed)
        }
        else -> {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                LegalDocCard(ui.privacyDoc?.title ?: "Privacy Policy", ui.privacyDoc?.contentMarkdown.orEmpty(), ui.privacyAccepted, vm::acceptPrivacy)
                Spacer(Modifier.height(12.dp))
                LegalDocCard(ui.termsDoc?.title ?: "Terms of Service", ui.termsDoc?.contentMarkdown.orEmpty(), ui.termsAccepted, vm::acceptTerms)
            }
            NeonButton(
                "ACCEPT & CONTINUE",
                { haptics.tick(); vm.setPage(PAGE_AGE) },
                Modifier.fillMaxWidth().padding(top = 10.dp),
                enabled = ui.privacyAccepted && ui.termsAccepted,
            )
        }
    }
}

@Composable
private fun LegalDocCard(title: String, markdown: String, accepted: Boolean, onAccept: (Boolean) -> Unit) {
    GlowCard(glow = if (accepted) VenomGreen else ElectricBlue) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = TextPrimary)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.heightIn(max = 180.dp).verticalScroll(rememberScrollState())) {
            MiniMarkdown(markdown)
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = accepted, onCheckedChange = onAccept, colors = CheckboxDefaults.colors(checkedColor = VenomGreen))
            Text("I have read and accept.", style = MaterialTheme.typography.bodyMedium, color = TextPrimary)
        }
    }
}

/** Minimal markdown renderer: #, ##, -, plain paragraphs. Zero network, zero deps. */
@Composable
fun MiniMarkdown(md: String) {
    Column {
        md.lines().forEach { line ->
            when {
                line.startsWith("## ") -> Text(line.removePrefix("## "), style = MaterialTheme.typography.titleMedium, color = ElectricBlue)
                line.startsWith("# ") -> Text(line.removePrefix("# "), style = MaterialTheme.typography.titleLarge, color = ElectricBlue)
                line.startsWith("- ") -> Text("  •  " + line.removePrefix("- "), style = MaterialTheme.typography.bodyMedium)
                line.isBlank() -> Spacer(Modifier.height(6.dp))
                else -> Text(line, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun MetricPage(title: String, subtitle: String, value: String, onNext: () -> Unit, slider: @Composable () -> Unit) {
    val haptics = rememberSystemHaptics()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text(title, style = MaterialTheme.typography.headlineMedium, color = ElectricBlue)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(40.dp))
        Text(value, fontSize = 52.sp, fontWeight = FontWeight.Black, color = TextPrimary)
        slider()
        Spacer(Modifier.height(40.dp))
        NeonButton("CONTINUE", { haptics.tick(); onNext() }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun GoalPage(goal: String, onGoal: (String) -> Unit, onNext: () -> Unit) {
    val haptics = rememberSystemHaptics()
    val goals = listOf(
        "SHRED" to "Cut. Fast. Ruthless. (Jin Woo arc)",
        "BULK" to "Massive hypertrophy. (Goku arc)",
        "STRENGTH" to "Raw power ceiling. (Jiren arc)",
        "RECOMP" to "Balanced rebuild. (Saitama arc)",
        "ANIME_ARC" to "Full 4-month anime training arc.",
    )
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text("DECLARE YOUR GOAL", style = MaterialTheme.typography.headlineMedium, color = ElectricBlue)
        Text("Exact. No vague answers. THE SYSTEM punishes indecision.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))
        goals.forEach { (id, label) ->
            Row(
                Modifier.fillMaxWidth().selectable(selected = goal == id, onClick = { haptics.select(); onGoal(id) }).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = goal == id, onClick = { haptics.select(); onGoal(id) }, colors = RadioButtonDefaults.colors(selectedColor = ElectricBlue))
                Text(label, color = TextPrimary, style = MaterialTheme.typography.bodyLarge)
            }
        }
        Spacer(Modifier.height(24.dp))
        NeonButton("LOCK IT IN", { haptics.success(); onNext() }, Modifier.fillMaxWidth())
    }
}

@Composable
private fun ValuePage(onNext: () -> Unit) {
    val haptics = rememberSystemHaptics()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text("WHAT YOU ARE JOINING", style = MaterialTheme.typography.headlineMedium, color = NeonPurple)
        Spacer(Modifier.height(16.dp))
        listOf(
            "Hardcore leveling: XP = 100 × N¹·⁸. Skip days and you decay to GARBAGE.",
            "Capture real-world 1KM territory zones with your Shadow Guild.",
            "Realtime push-up wars. Predict battles. Win VC.",
            "Level-gated merch. S-Rank hoodies are earned, never just bought.",
        ).forEach {
            Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(vertical = 6.dp))
        }
        Spacer(Modifier.height(28.dp))
        NeonButton("I UNDERSTAND", { haptics.tick(); onNext() }, Modifier.fillMaxWidth(), color = NeonPurple)
    }
}

@Composable
private fun AuthPage(ui: OnboardingUiState, onGoogle: () -> Unit) {
    val haptics = rememberSystemHaptics()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text("IDENTITY GATE", style = MaterialTheme.typography.headlineMedium, color = ElectricBlue)
        Text("One account per hunter. Your Google display name is pulled automatically; your @handle must be unique across THE SYSTEM.",
            style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(32.dp))
        NeonButton(
            if (ui.signingIn) "CONTACTING THE SYSTEM…" else "CONTINUE WITH GOOGLE",
            { haptics.select(); onGoogle() },
            Modifier.fillMaxWidth().height(52.dp),
            enabled = !ui.signingIn,
        )
        ui.authError?.let {
            Spacer(Modifier.height(10.dp))
            RestrictionBanner(it)
        }
    }
}

@Composable
private fun UsernamePage(ui: OnboardingUiState, vm: OnboardingViewModel) {
    val haptics = rememberSystemHaptics()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text("CLAIM YOUR @HANDLE", style = MaterialTheme.typography.headlineMedium, color = ElectricBlue)
        Text("Signed in as ${ui.signedInProfile?.displayName ?: "Hunter"}", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(
            value = ui.username,
            onValueChange = vm::onUsernameChanged,
            prefix = { Text("@", color = ElectricBlue) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Unique username (3–20, a–z 0–9 _)") },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = if (ui.usernameAvailable == true) VenomGreen else ElectricBlue,
                unfocusedBorderColor = SurfaceHigh,
                focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary,
            ),
        )
        Spacer(Modifier.height(6.dp))
        when {
            ui.usernameChecking -> Text("Checking the registry…", color = TextMuted, style = MaterialTheme.typography.bodyMedium)
            ui.usernameAvailable == true -> Text("✓ @${ui.username} is yours for the taking.", color = VenomGreen) // claimable = green
            ui.usernameError != null -> Text(ui.usernameError, color = CrimsonRed)
        }
        Spacer(Modifier.height(28.dp))
        NeonButton("CLAIM HANDLE", { haptics.success(); vm.setPage(PAGE_REFERRAL) }, Modifier.fillMaxWidth(), enabled = ui.usernameAvailable == true)
    }
}

@Composable
private fun ReferralPage(ui: OnboardingUiState, vm: OnboardingViewModel, onDone: () -> Unit) {
    val haptics = rememberSystemHaptics()
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center) {
        Text("WERE YOU SUMMONED?", style = MaterialTheme.typography.headlineMedium, color = HunterGold)
        Text("Enter a referral code to bless your summoner with lifetime passive cuts — and start with a VC bonus.", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(20.dp))
        OutlinedTextField(
            value = ui.referralCode, onValueChange = vm::setReferral,
            label = { Text("Referral code (optional)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = HunterGold, focusedTextColor = TextPrimary, unfocusedTextColor = TextPrimary),
        )
        ui.error?.let { Spacer(Modifier.height(8.dp)); RestrictionBanner(it) }
        Spacer(Modifier.height(28.dp))
        NeonButton(if (ui.busy) "INSCRIBING…" else "ENTER THE SYSTEM", { haptics.slam(); vm.finish(onDone) }, Modifier.fillMaxWidth(), color = HunterGold, enabled = !ui.busy)
        TextButton(onClick = { vm.finish(onDone) }, modifier = Modifier.fillMaxWidth()) { Text("Skip referral", color = TextMuted) }
    }
}
