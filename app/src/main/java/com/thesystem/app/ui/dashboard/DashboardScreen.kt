package com.thesystem.app.ui.dashboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import androidx.compose.ui.layout.ContentScale
import com.thesystem.app.Routes
import com.thesystem.app.core.SystemMath
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import com.thesystem.app.data.model.CourseDto
import com.thesystem.app.data.model.QuestDto
import com.thesystem.app.data.model.UserCourseDto
import com.thesystem.app.service.RecoveryTracker
import com.thesystem.app.ui.training.CourseCard
import com.thesystem.app.ui.training.CourseInfoPanel
import com.thesystem.app.ui.training.TrainingViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * TAB 1 — STATUS.
 *
 * IA per spec:  SYSTEM / STATUS header → compact TODAY QUEST module (tap opens
 * the cinematic QUEST INFO HUD with live 0/N goals, XP, duration, difficulty,
 * time remaining and the penalty warning) → TRAINING / COURSE area with the
 * muscle rail, the performance-development area and the Black Room door.
 * Every number on this screen is live.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    nav: NavHostController,
    vm: DashboardViewModel = hiltViewModel(),
    trainingVm: TrainingViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val t by trainingVm.state.collectAsStateWithLifecycle()
    val snack = remember { androidx.compose.material3.SnackbarHostState() }
    val haptics = rememberSystemHaptics()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var questSheetOpen by remember { mutableStateOf(false) }

    // returning from a verified proof session re-pulls quest progress
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var first = true
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                if (first) first = false else { vm.refresh(); trainingVm.refresh() }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val (burstSignal, fireBurst) = rememberEffectSignal()
    val (floaterSignal, fireFloater) = rememberEffectSignal()
    val (levelUpSignal, fireLevelUp) = rememberEffectSignal()
    var floaterText by remember { mutableStateOf("") }
    var previousLevel by remember { mutableIntStateOf(-1) }

    LaunchedEffect(s.profile?.level) {
        val lv = s.profile?.level ?: return@LaunchedEffect
        if (previousLevel in 1 until lv) { fireLevelUp(); fireBurst(); haptics.slam() }
        previousLevel = lv
    }

    LaunchedEffect(s.notice, s.error) {
        s.error?.let { haptics.error(); snack.showSnackbar(it); vm.clearNotice(); return@LaunchedEffect }
        s.notice?.let { msg ->
            floaterText = msg.substringBefore(" —")
            fireFloater()
            snack.showSnackbar(msg)
            vm.clearNotice()
        }
    }

    SystemBackground(wallpaperUrl = s.wallpaper, wallpaperAlpha = s.wallpaperAlpha) {
        Box(Modifier.fillMaxSize()) {
            if (s.loading && s.profile == null) {
                Column(
                    Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = Grid.Margin, vertical = Grid.S16),
                    verticalArrangement = Arrangement.spacedBy(Grid.CardSpace),
                ) { SkeletonCards(4) }
            } else {
                PullToRefreshBox(
                    isRefreshing = s.loading,
                    onRefresh = { haptics.tick(); vm.refresh(); trainingVm.refresh() },
                    modifier = Modifier.fillMaxSize().statusBarsPadding(),
                ) {
                    LazyColumn(
                        Modifier.fillMaxSize().padding(horizontal = Grid.Margin),
                        verticalArrangement = Arrangement.spacedBy(Grid.CardSpace),
                        contentPadding = PaddingValues(vertical = Grid.S16),
                    ) {
                        // 1 ── SYSTEM / STATUS header
                        item {
                            Box(Modifier.enterAnim(0)) {
                                SystemStatusHeader(
                                    s = s,
                                    burstSignal = burstSignal,
                                    floaterSignal = floaterSignal,
                                    floaterText = floaterText,
                                )
                            }
                        }
                        s.profile?.let { p ->
                            if (p.missedDays > 0) item { Box(Modifier.enterAnim(1)) { PenaltyCard(s) } }
                            item {
                                Box(Modifier.enterAnim(2)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(Grid.S12)) {
                                        StreakTile(p.streakDays, Modifier.weight(1f))
                                        AnimatedStatTile(
                                            "Next level",
                                            SystemMath.xpRequiredForLevel(p.level + 1) - p.xp,
                                            ElectricBlue, Modifier.weight(1.15f),
                                        )
                                        StatTile(
                                            "Missed", "${p.missedDays}",
                                            if (p.missedDays > 0) LabelGray else TextMuted, Modifier.weight(0.85f),
                                        )
                                    }
                                }
                            }
                        }

                        // 2 ── compact TODAY QUEST module → tap opens the QUEST INFO sheet
                        item {
                            Box(Modifier.enterAnim(3)) {
                                TodayQuestModule(
                                    s = s,
                                    recoverySec = RecoveryTracker.remainingSec(context),
                                    onOpen = { haptics.select(); questSheetOpen = true },
                                )
                            }
                        }

                        // 3 ── TRAINING — ONE primary course commands this surface;
                        //    every alternative lives in the catalog, never here.
                        item {
                            Box(Modifier.enterAnim(4)) {
                                Column {
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        SectionTitle("TRAINING")
                                        GhostButton("CATALOG", {
                                            haptics.select(); nav.navigate(Routes.TRAINING)
                                        })
                                    }
                                    Spacer(Modifier.height(Grid.S8))
                                    val primary = s.primaryMuscle
                                    if (primary == null) {
                                        GlowCard(modifier = Modifier.fillMaxWidth()) {
                                            Text("NO PRIMARY COURSE", color = PaperWhite, style = MaterialTheme.typography.titleMedium)
                                            Text(
                                                "One muscle track · verified sessions · the catalog holds every option.",
                                                style = MaterialTheme.typography.bodySmall, color = LabelGray,
                                            )
                                            Spacer(Modifier.height(Grid.S8))
                                            NeonButton(
                                                "CHOOSE PRIMARY COURSE",
                                                { haptics.select(); nav.navigate(Routes.TRAINING) },
                                                Modifier.fillMaxWidth(), color = SkyBlue,
                                            )
                                        }
                                    } else {
                                        PrimaryCoursePanel(
                                            course = primary,
                                            enrollment = s.enrollmentOf(primary),
                                            onOpen = { haptics.select(); trainingVm.open(primary) },
                                        )
                                    }
                                    Spacer(Modifier.height(Grid.S12))
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column {
                                            SectionTitle("PERFORMANCE DEVELOPMENT", SkyBlue)
                                            Text(
                                                "ACTIVE ${s.specials.count { s.enrollmentOf(it)?.status == "ACTIVE" }}/5",
                                                style = MonoLabel, color = LabelGray,
                                            )
                                        }
                                        GhostButton("ALL", {
                                            haptics.select(); nav.navigate(Routes.TRAINING)
                                        })
                                    }
                                    Spacer(Modifier.height(Grid.S8))
                                    // SPECIAL TRAINING law: the FULL catalog always renders.
                                    // Muscle hides alternatives after one primary is chosen —
                                    // specials never do; up to five stay ACTIVE at once.
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Grid.S12)) {
                                        items(s.specials, key = { it.id }) { c ->
                                            CourseCard(
                                                course = c, enrollment = s.enrollmentOf(c),
                                                recommended = false,
                                                onClick = { haptics.tick(); trainingVm.open(c) },
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(Grid.S12))
                                    BlackRoomDoor(
                                        unlocked = s.profile?.level ?: 1 >= 50,
                                        onClick = { haptics.select(); nav.navigate(Routes.BLACK_ROOM) },
                                    )
                                }
                            }
                        }

                        item { Box(Modifier.enterAnim(5)) { FormsStrip(s) } }
                        item { Box(Modifier.enterAnim(6)) { BuffsCard(s) } }
                        item { Spacer(Modifier.height(96.dp)) }
                    }
                }
            }

            XpBurst(burstSignal, color = SkyBlue)
            LevelUpShockwave(levelUpSignal, color = SkyBlue)
            levelUpSignal.takeIf { it > 0 }?.let { LevelUpBanner(levelUpSignal, level = previousLevel) }
            snack.let { androidx.compose.material3.SnackbarHost(it, Modifier.align(Alignment.BottomCenter)) }

            // QUEST INFO — deliberate detail sheet, the page behind stays frozen
            if (questSheetOpen) {
                QuestDetailSheet(
                    s = s,
                    recoverySec = RecoveryTracker.remainingSec(context),
                    onLog = { q ->
                        haptics.tick()
                        questSheetOpen = false
                        nav.navigate(Routes.questProof(q))
                    },
                    onOpenTraining = {
                        questSheetOpen = false
                        nav.navigate(Routes.TRAINING)
                    },
                    onDismiss = { questSheetOpen = false },
                )
            }

            // course info panel opened from the training rail
            t.openCourse?.let { c ->
                CourseInfoPanel(
                    course = c,
                    plan = t.openPlan,
                    enrollment = t.enrollmentOf(c),
                    onSelect = { haptics.select(); trainingVm.enroll(c) },
                    onContinue = { haptics.select(); trainingVm.closePanel() },
                    onDismiss = { trainingVm.closePanel() },
                )
            }
        }
    }
}

// ── 1. SYSTEM / STATUS HEADER ───────────────────────────────────────────────

@Composable
private fun SystemStatusHeader(
    s: DashboardState,
    burstSignal: Int,
    floaterSignal: Int,
    floaterText: String,
) {
    val p = s.profile
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("SYSTEM", style = MonoLabel, color = SkyBlue)
                Text("STATUS", color = PaperWhite, fontWeight = FontWeight.Bold, fontSize = 22.sp, letterSpacing = 1.sp)
            }
            // penalty ranks keep the inverted badge; earned progress shows the tier ladder
            if (s.rank.isPenaltyRank) RankBadge(s.rank)
            else TierBadge(SystemMath.tierFor(p?.level ?: 1))
        }
        Spacer(Modifier.height(Grid.S12))
        GlowCard(modifier = Modifier.fillMaxWidth()) {
            Box {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    XpRing(
                        xp = p?.xp ?: 0, level = p?.level ?: 1,
                        modifier = Modifier.size(104.dp), color = rankColor(s.rank),
                    )
                    Spacer(Modifier.width(Grid.S16))
                    Column(Modifier.weight(1f)) {
                        Text("HUNTER @${p?.username ?: "…"}", style = MaterialTheme.typography.labelSmall)
                        Text(p?.displayName ?: "Unknown Hunter", style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.height(Grid.S4))
                        Text(
                            "LV ${p?.level ?: 1} · ${if (s.rank.isPenaltyRank) s.rank.title else SystemMath.tierFor(p?.level ?: 1).title} · ${SystemMath.formatXp(p?.xp ?: 0)} XP",
                            style = MonoData, color = PaperWhite,
                        )
                        Spacer(Modifier.height(Grid.S8))
                        XpProgressBar(p?.xp ?: 0, color = rankColor(s.rank))
                    }
                }
                XpGainFloater(floaterSignal, floaterText, Modifier.align(Alignment.TopEnd))
            }
        }
    }
}

// ── 2. TODAY QUEST MODULE (compact) → QUEST INFO detail sheet ───────────────

@Composable
private fun TodayQuestModule(
    s: DashboardState,
    recoverySec: Int,
    onOpen: () -> Unit,
) {
    val done = s.clearedCount
    val total = s.quests.size.coerceAtLeast(1)
    val frac = (done.toFloat() / total).coerceIn(0f, 1f)
    val charge by animateFloatAsState(frac, tween(600), label = "questCharge")
    val open = s.openQuest

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SkyBlue.copy(alpha = 0.06f))
            .border(1.dp, SkyBlue.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onOpen() }
            .padding(Grid.S16),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("TODAY QUEST", color = SkyBlue, fontFamily = SystemMono, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, fontSize = 12.sp)
            Spacer(Modifier.width(10.dp))
            Text("$done/$total", style = MonoData, color = PaperWhite)
            Spacer(Modifier.weight(1f))
            Text("TAP FOR INFO", style = MonoLabel, color = LabelGray)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            open?.title ?: if (s.allCleared) "ALL BLOCKS CLEARED" else "PROTOCOL COMPILING",
            color = PaperWhite, style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        // progress charge bar — the module breathes without animating everything
        Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(TrackGray)) {
            Box(
                Modifier.fillMaxHeight().fillMaxWidth(charge).clip(RoundedCornerShape(2.dp))
                    .background(if (s.allCleared) PaperWhite else SkyBlue)
            )
        }
        if (recoverySec > 0) {
            Spacer(Modifier.height(8.dp))
            Text(
                "RECOVERY WINDOW — ${RecoveryTracker.format(recoverySec)} until the next block unlocks",
                style = MonoLabel, color = SkyBlue,
            )
        }
    }
}

/**
 * QUEST INFO — a dedicated detail sheet (never an inline expansion). The
 * Status page behind stays frozen; the sheet carries every block with its
 * live 0/N goal, XP, duration, difficulty, verification, the reset clock and
 * the penalty warning, plus the START / RESUME actions.
 */
@Composable
private fun QuestDetailSheet(
    s: DashboardState,
    recoverySec: Int,
    onLog: (QuestDto) -> Unit,
    onOpenTraining: () -> Unit,
    onDismiss: () -> Unit,
) {
    SystemBottomSheet(title = "QUEST INFO", onDismiss = onDismiss, accent = SkyBlue, maxHeight = 620.dp) {
        val nowMs = remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) { delay(1000); nowMs.longValue = System.currentTimeMillis() }
        }
        val midnight = remember {
            java.util.Calendar.getInstance().apply {
                set(java.util.Calendar.HOUR_OF_DAY, 23); set(java.util.Calendar.MINUTE, 59)
                set(java.util.Calendar.SECOND, 59); set(java.util.Calendar.MILLISECOND, 999)
            }.timeInMillis
        }
        val remain = (midnight - nowMs.longValue).coerceAtLeast(0L)
        val resetText = "%02d:%02d:%02d".format(remain / 3_600_000, (remain % 3_600_000) / 60_000, (remain % 60_000) / 1000)
        val context = LocalContext.current

        LazyColumn(
            Modifier.weight(1f, fill = false),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(s.quests.sortedBy { it.seq }, key = { it.id }) { q ->
                QuestBlockRow(
                    q = q,
                    blocked = q.isLocked || (recoverySec > 0 && !q.isDone && q.seq > RecoveryTracker.afterSeq(context)),
                    onLog = { onLog(q) },
                )
            }
            if (s.quests.isEmpty()) {
                item { EmptyState("The System is compiling today's protocol… close this sheet and pull to refresh.") }
            }
            item {
                NeonDivider(SkyBlue.copy(alpha = 0.25f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "⚠ Failure before reset incurs the protocol penalty.",
                        style = MaterialTheme.typography.labelSmall, color = LabelGray, modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("RESET $resetText", color = SkyBlue, fontFamily = SystemMono, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                GhostButton("TRAINING CATALOG", onOpenTraining, Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text(
                    "Career sessions live in the catalog — they never block today's protocol.",
                    style = MaterialTheme.typography.bodySmall, color = FaintGray,
                )
            }
        }
    }
}

/** One protocol block: status chip, live 0/N goal, XP, duration, difficulty. */
@Composable
private fun QuestBlockRow(q: QuestDto, blocked: Boolean, onLog: () -> Unit) {
    val statusColor = when {
        q.isDone -> PaperWhite
        q.isDead -> LabelGray
        blocked -> FaintGray
        else -> SkyBlue
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(q.blockLabel, style = MonoData, color = statusColor, modifier = Modifier.width(28.dp))
        Column(Modifier.weight(1f)) {
            Text(q.title.uppercase(), color = if (blocked || q.isDead) FaintGray else PaperWhite,
                style = MaterialTheme.typography.titleMedium)
            Text(
                "${q.progress}/${q.targetValue} ${q.targetUnit.lowercase()} · +${q.xpReward} XP · " +
                    "${q.estDurationSec / 60}m · ${q.difficulty} · ${q.verification}",
                style = MaterialTheme.typography.bodySmall, color = LabelGray,
            )
            Spacer(Modifier.height(5.dp))
            Box(Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(TrackGray)) {
                val f = (q.progress.toFloat() / q.targetValue.coerceAtLeast(1)).coerceIn(0f, 1f)
                Box(Modifier.fillMaxHeight().fillMaxWidth(f).background(if (q.isDone) PaperWhite else SkyBlue))
            }
        }
        Spacer(Modifier.width(Grid.S12))
        when {
            q.isDone -> Text("COMPLETE", style = MonoLabel, color = PaperWhite)
            q.isDead -> Text(q.status, style = MonoLabel, color = LabelGray)
            blocked -> Text(if (q.isLocked) "LOCKED" else "RECOVER", style = MonoLabel, color = FaintGray)
            else -> NeonButton(if (q.progress > 0) "RESUME" else "START", onLog, color = SkyBlue)
        }
    }
}

// ── other STATUS cards ──────────────────────────────────────────────────────

@Composable
private fun PenaltyCard(s: DashboardState) {
    val p = s.profile ?: return
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        Text("! PENALTY ENGINE ARMED", color = PaperWhite, style = MaterialTheme.typography.titleMedium)
        Text(
            "Missed days: ${p.missedDays}. Next decay tick: −${SystemMath.formatXp(s.projectedDecay)}. " +
                "Clear today's protocol before midnight or degrade.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun BuffsCard(s: DashboardState) {
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        Text("ACTIVE BUFFS / PENALTIES", style = MaterialTheme.typography.labelLarge, color = TextMuted)
        Spacer(Modifier.height(6.dp))
        if (s.buffs.isEmpty()) Text("No active modifiers. Train to ignite some.", style = MaterialTheme.typography.bodyMedium)
        s.buffs.forEach { (label, good) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                Text(if (good) "▲" else "▼", color = if (good) VenomGreen else CrimsonRed, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Text(label, style = MaterialTheme.typography.bodyMedium,
                    color = if (good) TextPrimary else CrimsonRed, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun FormsStrip(s: DashboardState) {
    GlowCard(modifier = Modifier.fillMaxWidth()) {
        Text("FORM EVOLUTION", style = MaterialTheme.typography.labelLarge, color = NeonPurple)
        Spacer(Modifier.height(6.dp))
        if (s.forms.isEmpty()) {
            Text(
                "??? — the mechanics of your Mystery Power reveal themselves at level ${SystemMath.FORM_UNLOCK_LEVELS[0]} (Form 1).",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            s.forms.take(5).forEach { f ->
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("F${f.formIndex}", color = HunterGold, fontWeight = FontWeight.Black, fontSize = 16.sp)
                    AnimatedCounter(
                        target = f.computedPower.toLong(), color = NeonPurple,
                        fontSize = 12.sp, fontWeight = FontWeight.Medium, format = { "$it" },
                    )
                }
            }
        }
    }
}

/**
 * PRIMARY COURSE — the one muscle track the hunter chose. Art owns the top,
 * data owns the panel: name · difficulty · duration · progress · CONTINUE.
 * Alternatives never render here; they live in the catalog.
 */
@Composable
private fun PrimaryCoursePanel(
    course: CourseDto,
    enrollment: UserCourseDto?,
    onOpen: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(PanelGray)
            .border(1.dp, LineSoft, RoundedCornerShape(12.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onOpen() },
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 10f).background(InkBlack)) {
            AsyncImage(
                model = course.cover, contentDescription = course.title,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit,
            )
            Box(
                Modifier
                    .align(Alignment.TopStart).padding(10.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(InkBlack.copy(alpha = 0.72f))
                    .border(1.dp, SkyBlue, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) { Text("PRIMARY COURSE", style = MonoLabel, color = SkyBlue, fontSize = 8.sp) }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(LineSoft))
        Column(Modifier.fillMaxWidth().padding(Grid.S12)) {
            Text(
                course.title.uppercase(), color = PaperWhite,
                style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text("${course.difficulty} · ${course.durationMonths} MONTHS", style = MonoLabel, color = LabelGray)
            Spacer(Modifier.height(Grid.S8))
            val pct = enrollment?.progressPercent ?: 0.0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(TrackGray)) {
                    Box(
                        Modifier.fillMaxHeight()
                            .fillMaxWidth((pct / 100.0).toFloat().coerceIn(0f, 1f))
                            .background(SkyBlue),
                    )
                }
                Spacer(Modifier.width(Grid.S8))
                Text("%.0f%%".format(pct), style = MonoData, color = SkyBlue)
            }
            Spacer(Modifier.height(Grid.S8))
            NeonButton("CONTINUE", onOpen, Modifier.fillMaxWidth(), color = SkyBlue)
        }
    }
}

@Composable
private fun BlackRoomDoor(unlocked: Boolean, onClick: () -> Unit) {
    GlowCard(modifier = Modifier.fillMaxWidth().clickable { onClick() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("FORBIDDEN COURSE / BLACK ROOM", color = PaperWhite, style = MaterialTheme.typography.titleMedium)
                Text(
                    if (unlocked) "Gate reports ready — apply for the bespoke 2-year protocol."
                    else "2-year personalized protocol · level 50 gate · admin-built",
                    style = MaterialTheme.typography.bodySmall, color = LabelGray,
                )
            }
            Text(if (unlocked) "OPEN" else "LOCKED", style = MonoLabel, color = if (unlocked) SkyBlue else FaintGray)
        }
    }
}
