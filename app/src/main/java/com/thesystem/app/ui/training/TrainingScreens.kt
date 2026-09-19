package com.thesystem.app.ui.training

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import com.thesystem.app.data.model.CourseDto
import com.thesystem.app.data.model.CourseQuestDto
import com.thesystem.app.data.model.UserCourseDto
import com.thesystem.app.data.repo.TrainingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

// ═══════════════════════════════════════════════════════════════════════════
// TRAINING — the course area that sits under TODAY QUEST on the Status screen.
//   · MUSCLE TRAINING  — pick ONE primary course (server: one per lifetime)
//   · PERFORMANCE DEVELOPMENT AREA — specials, max 5 active, rest-day slots
//   · FORBIDDEN COURSE / BLACK ROOM — at the very end of the list
// Cards never auto-start a course: they open the COURSE INFO panel first.
// ═══════════════════════════════════════════════════════════════════════════

data class TrainingState(
    val loading: Boolean = true,
    val muscle: List<CourseDto> = emptyList(),
    val specials: List<CourseDto> = emptyList(),
    val forbidden: List<CourseDto> = emptyList(),
    val myCourses: List<UserCourseDto> = emptyList(),
    val openCourse: CourseDto? = null,
    val openPlan: List<CourseQuestDto> = emptyList(),
    val notice: String? = null,
    val error: String? = null,
) {
    fun enrollmentOf(course: CourseDto): UserCourseDto? = myCourses.firstOrNull { it.courseId == course.id }
    val primaryMuscle: CourseDto? get() = muscle.firstOrNull { enrollmentOf(it) != null }
    val activeSpecials: List<CourseDto> get() = specials.filter { enrollmentOf(it)?.status == "ACTIVE" }
}

@HiltViewModel
class TrainingViewModel @Inject constructor(
    private val training: TrainingRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(TrainingState())
    val state: StateFlow<TrainingState> = _state

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        _state.value = _state.value.copy(loading = true)
        val muscle = training.courses("MUSCLE")
        val specials = training.courses("SPECIAL")
        val forbidden = training.courses("FORBIDDEN")
        val mine = training.myCourses()
        _state.value = _state.value.copy(
            loading = false, muscle = muscle, specials = specials,
            forbidden = forbidden, myCourses = mine,
        )
    }

    /** Course card tap → COURSE INFO panel (the plan is fetched, never auto-started). */
    fun open(course: CourseDto) = viewModelScope.launch {
        _state.value = _state.value.copy(openCourse = course, openPlan = emptyList())
        val plan = training.courseQuests(course.id)
        _state.value = _state.value.copy(openPlan = plan)
    }

    fun closePanel() { _state.value = _state.value.copy(openCourse = null, openPlan = emptyList()) }

    fun enroll(course: CourseDto) = viewModelScope.launch {
        training.enroll(course.id)
            .onSuccess {
                _state.value = _state.value.copy(notice = "${course.title} selected — the plan is now yours.")
                refresh()
            }
            .onFailure { _state.value = _state.value.copy(error = enrollMessage(it.message)) }
    }

    fun claimNotice() { _state.value = _state.value.copy(notice = null, error = null) }

    private fun enrollMessage(raw: String?): String = when {
        raw == null -> "Selection failed. Try again."
        raw.contains("one_muscle_path_lifetime") -> "One primary muscle course per lifetime — finish or stay on your current track."
        raw.contains("max_five_specials") -> "Performance area is capped at 5 active tracks. Complete one first."
        raw.contains("weight_reverify_required") -> "Log your current weight first — muscle progression recalibrates every 31 days."
        raw.contains("already_enrolled") -> "You are already on this track."
        raw.contains("forbidden_course") -> "That track needs the Black Room gate."
        else -> "Selection failed: $raw"
    }
}

/** COURSE → WEEK → DAY → QUEST rows — rendered inside the shared detail sheet. */
@Composable
fun CourseInfoPanel(
    course: CourseDto,
    plan: List<CourseQuestDto>,
    enrollment: UserCourseDto?,
    onSelect: () -> Unit,
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    val (sessionMin, sessionMax) = course.sessionRange
    SystemBottomSheet(title = "COURSE INFO", onDismiss = onDismiss, accent = SkyBlue, maxHeight = 600.dp) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = course.cover, contentDescription = course.title,
                    modifier = Modifier.size(58.dp).clip(RoundedCornerShape(8.dp)),
                    contentScale = ContentScale.Crop,
                )
                Spacer(Modifier.width(Grid.S12))
                Column(Modifier.weight(1f)) {
                    Text(
                        course.title, style = MaterialTheme.typography.titleMedium, color = PaperWhite,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "${course.type} · ${course.difficulty}",
                        style = MonoLabel, color = SkyBlue,
                    )
                }
            }
            Spacer(Modifier.height(Grid.S12))

            LazyColumn(Modifier.weight(1f, fill = false), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { InfoRow("TARGET", course.target ?: "FULL BODY") }
                item { InfoRow("DURATION", "${course.durationMonths} MONTHS") }
                item { InfoRow("SCHEDULE", course.weeklyDays.joinToString(" · ").ifBlank { "SEE WEEK PLAN" }) }
                item { InfoRow("SESSION", "$sessionMin–$sessionMax MIN") }
                item { InfoRow("EQUIPMENT", course.equipment ?: "BODYWEIGHT") }
                item {
                    InfoRow(
                        "PROGRESS",
                        enrollment?.let { "%.0f%%".format(it.progressPercent) } ?: "NOT STARTED",
                    )
                }
                if (course.restDayOnly) {
                    item { InfoRow("SLOT", "MUSCLE RECOVERY / REST DAYS ONLY") }
                }
                course.description?.let { desc ->
                    item {
                        Spacer(Modifier.height(4.dp))
                        Text(desc, style = MaterialTheme.typography.bodyMedium, color = LabelGray)
                    }
                }
                if (plan.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(6.dp))
                        Text("COURSE QUESTS · WEEK / DAY", style = MonoLabel, color = SkyBlue)
                    }
                    items(plan.take(24)) { q -> PlanRow(q) }
                } else {
                    item {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "The System is still compiling this plan. New quests appear the moment the admin publishes them — no app update needed.",
                            style = MaterialTheme.typography.bodySmall, color = FaintGray,
                        )
                    }
                }
            }

            Spacer(Modifier.height(Grid.S12))
            if (enrollment == null) {
                NeonButton("SELECT COURSE", onSelect, Modifier.fillMaxWidth(), color = SkyBlue)
            } else {
                NeonButton("CONTINUE", onContinue, Modifier.fillMaxWidth(), color = SkyBlue)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Selecting a track never starts a session. Every session is opened by you.",
                style = MaterialTheme.typography.bodySmall, color = FaintGray, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PlanRow(q: CourseQuestDto) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Text("W${q.week + 1}·D${q.day + 1}", style = MonoLabel, color = LabelGray, modifier = Modifier.width(52.dp))
        Column(Modifier.weight(1f)) {
            Text(q.title, style = MaterialTheme.typography.titleMedium, color = PaperWhite, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${q.sets}×${q.reps} · ${q.exercise} · +${q.xp} XP · ${q.verification}",
                style = MaterialTheme.typography.bodySmall, color = LabelGray,
            )
        }
        Text(q.difficulty.take(1), style = MonoLabel, color = SkyBlue)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
        Text(label, style = MonoLabel, color = LabelGray, modifier = Modifier.width(96.dp))
        Text(value.uppercase(), style = MaterialTheme.typography.titleMedium, color = PaperWhite,
            maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// ── COURSE CARD ─────────────────────────────────────────────────────────────
// SPLIT LAYOUT: artwork owns the top block, text owns a solid panel below.
// The two never share a pixel, so no description can ever drown in the art,
// nothing clips into the image, and long titles wrap on plain black.

@Composable
fun CourseCard(
    course: CourseDto,
    enrollment: UserCourseDto?,
    recommended: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }
    val alpha by animateFloatAsState(if (visible) 1f else 0f, spring(), label = "cardIn")

    Column(
        modifier
            .width(178.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(PanelGray)
            .border(
                if (recommended) 1.5.dp else 1.dp,
                if (recommended) SkyBlue.copy(alpha = 0.7f) else LineSoft,
                RoundedCornerShape(12.dp),
            )
            .clickable { onClick() },
    ) {
        // artwork block — pure image, zero text
        Box(Modifier.fillMaxWidth().height(104.dp)) {
            AsyncImage(
                model = course.cover, contentDescription = course.title,
                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop, alpha = alpha,
            )
            if (recommended) {
                Box(
                    Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(InkBlack.copy(alpha = 0.72f))
                        .border(1.dp, SkyBlue, RoundedCornerShape(4.dp))
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) { Text("RECOMMENDED", style = MonoLabel, color = SkyBlue, fontSize = 8.sp) }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(LineSoft))

        // information block — solid panel; every field readable, always
        Column(Modifier.fillMaxWidth().padding(Grid.S12)) {
            Text(
                course.title.uppercase(), color = PaperWhite,
                style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (!course.description.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    course.description.orEmpty(), style = MaterialTheme.typography.bodySmall,
                    color = LabelGray, maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(Grid.S8))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SystemChip(course.difficulty, SkyBlue)
                SystemChip("${course.durationMonths}M")
            }
            Spacer(Modifier.height(Grid.S8))
            val done = enrollment != null
            Text(
                if (done) "IN PROGRESS · %.0f%%".format(enrollment!!.progressPercent) else "TAP FOR INFO",
                style = MonoLabel, color = if (done) SkyBlue else LabelGray,
            )
            if (done) {
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(2.dp).clip(RoundedCornerShape(1.dp)).background(TrackGray)) {
                    Box(
                        Modifier.fillMaxHeight()
                            .fillMaxWidth((enrollment.progressPercent / 100.0).toFloat().coerceIn(0f, 1f))
                            .background(SkyBlue)
                    )
                }
            }
        }
    }
}

// ── SECTIONS ────────────────────────────────────────────────────────────────

@Composable
fun MuscleTrainingSection(
    s: TrainingState,
    onOpen: (CourseDto) -> Unit,
    onEnroll: (CourseDto) -> Unit,
) {
    Column {
        SectionTitle("MUSCLE TRAINING")
        Spacer(Modifier.height(4.dp))
        val primary = s.primaryMuscle
        Text(
            if (primary == null) "Choose ONE primary course. The plan adapts to your verified performance."
            else "PRIMARY: ${primary.title.uppercase()} · one track per hunter.",
            style = MaterialTheme.typography.bodySmall, color = LabelGray,
        )
        Spacer(Modifier.height(Grid.S8))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Grid.S12)) {
            items(s.muscle, key = { it.id }) { c ->
                CourseCard(
                    course = c,
                    enrollment = s.enrollmentOf(c),
                    recommended = s.primaryMuscle == null && c.difficulty == "EASY",
                    onClick = { onOpen(c) },
                )
            }
        }
    }
}

/** PERFORMANCE DEVELOPMENT AREA — not a medical service, no diagnosis. */
@Composable
fun PerformanceAreaSection(
    s: TrainingState,
    onOpen: (CourseDto) -> Unit,
) {
    Column {
        SectionTitle("PERFORMANCE DEVELOPMENT AREA", SkyBlue)
        Spacer(Modifier.height(4.dp))
        Text(
            "Speed, agility, reflex, mobility, flexibility, endurance, stamina, grip, leg power and striking athletics. " +
                "Max ${5} active tracks — sessions are scheduled onto muscle recovery days. Results vary with individual effort.",
            style = MaterialTheme.typography.bodySmall, color = LabelGray,
        )
        Spacer(Modifier.height(Grid.S8))
        Text(
            "ACTIVE ${s.activeSpecials.size}/5", style = MonoLabel,
            color = if (s.activeSpecials.size >= 5) LabelGray else SkyBlue,
        )
        Spacer(Modifier.height(Grid.S8))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(Grid.S12)) {
            items(s.specials, key = { it.id }) { c ->
                CourseCard(course = c, enrollment = s.enrollmentOf(c), recommended = false, onClick = { onOpen(c) })
            }
        }
    }
}

/** The training catalog as a full screen (reached from Status and the Market). */
@Composable
fun TrainingCatalogScreen(
    onBack: () -> Unit,
    onOpenBlackRoom: () -> Unit,
    vm: TrainingViewModel = hiltViewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val haptics = rememberSystemHaptics()
    val snack = remember { SnackbarHostState() }

    LaunchedEffect(s.notice, s.error) {
        s.error?.let { snack.showSnackbar(it); vm.claimNotice() }
        s.notice?.let { snack.showSnackbar(it); vm.claimNotice() }
    }

    SystemBackground {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = Grid.Margin),
                verticalArrangement = Arrangement.spacedBy(Grid.CardSpace),
                contentPadding = PaddingValues(vertical = Grid.S16),
            ) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        GhostButton("BACK", onBack)
                        Spacer(Modifier.width(Grid.S12))
                        SectionTitle("TRAINING CATALOG")
                    }
                }
                item {
                    MuscleTrainingSection(
                        s,
                        onOpen = { haptics.tick(); vm.open(it) },
                        onEnroll = { vm.enroll(it) },
                    )
                }
                item { PerformanceAreaSection(s, onOpen = { haptics.tick(); vm.open(it) }) }
                item {
                    Column {
                        SectionTitle("FORBIDDEN COURSE")
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "BLACK ROOM — two-year personalized protocol, six-month renewal, hand-built by the Super Admin.",
                            style = MaterialTheme.typography.bodySmall, color = LabelGray,
                        )
                        Spacer(Modifier.height(Grid.S8))
                        BlackRoomTeaser { haptics.select(); onOpenBlackRoom() }
                    }
                }
                item { Spacer(Modifier.height(60.dp)) }
            }
            SnackbarHost(snack, Modifier.align(Alignment.BottomCenter))
            s.openCourse?.let { c ->
                CourseInfoPanel(
                    course = c, plan = s.openPlan, enrollment = s.enrollmentOf(c),
                    onSelect = { haptics.select(); vm.enroll(c) },
                    onContinue = { haptics.select(); vm.closePanel() },
                    onDismiss = { vm.closePanel() },
                )
            }
        }
    }
}

@Composable
private fun BlackRoomTeaser(onOpen: () -> Unit) {
    GlowCard(modifier = Modifier.fillMaxWidth().clickable { onOpen() }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "FORBIDDEN COURSE / BLACK ROOM", color = PaperWhite,
                style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
            )
            Text("2-YEAR", style = MonoLabel, color = LabelGray)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Level 50 · ≥1 unlocked form · ≥5 historical missed/penalty events · primary muscle course ≥50% · " +
                "3 performance tracks completed. APPLY → the Super Admin builds your personal program.",
            style = MaterialTheme.typography.bodySmall, color = LabelGray,
        )
    }
}
