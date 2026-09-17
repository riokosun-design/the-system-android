package com.thesystem.app.ui.admin

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.thesystem.app.core.theme.*
import com.thesystem.app.core.ui.*
import com.thesystem.app.data.model.BlackRoomApplicationDto
import com.thesystem.app.data.model.CourseDto
import com.thesystem.app.data.model.CourseQuestDto
import com.thesystem.app.data.model.QuestTemplateDto
import com.thesystem.app.data.model.AssetDto
import com.thesystem.app.ui.arena.FilterChipPill
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * ADMIN · TRAINING CMS — courses, weekly schedules, course quests, daily quest
 * templates, difficulty, XP, rest windows, verification type, equipment,
 * progression, and the per-hunter Black Room program builder.
 *
 * Nothing here is hardcoded in the client: publish from this console and the
 * hunters receive the content on their next sync, without an app update.
 */
@Composable
fun AdminTrainingTabs(state: AdminState, vm: AdminViewModel) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("COURSES", "COURSE QUESTS", "DAILY QUESTS", "BLACK ROOM").forEachIndexed { i, label ->
                FilterChipPill(label, tab == i) { tab = i }
            }
        }
        Spacer(Modifier.height(Grid.S12))
        when (tab) {
            0 -> CoursesTab(state, vm)
            1 -> CourseQuestsTab(state, vm)
            2 -> DailyQuestsTab(state, vm)
            3 -> BlackRoomAdminTab(state, vm)
        }
    }
}

@Composable
private fun CoursesTab(s: AdminState, vm: AdminViewModel) {
    var editing by remember { mutableStateOf<CourseDto?>(null) }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(Grid.S8)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("CATALOG ${s.courses.size}", style = MonoLabel, color = LabelGray, modifier = Modifier.weight(1f))
                GhostButton("NEW COURSE", {
                    editing = CourseDto(id = "", slug = "", title = "", type = "MUSCLE", sort = 60)
                })
            }
        }
        items(s.courses, key = { it.id.ifBlank { it.slug + it.title } }) { c ->
            GlowCard(modifier = Modifier.fillMaxWidth().clickable { editing = c }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(c.title, color = PaperWhite, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${c.type} · ${c.difficulty} · ${c.durationMonths}M · sort ${c.sort}" +
                                if (c.active) "" else " · INACTIVE",
                            style = MaterialTheme.typography.bodySmall,
                            color = if (c.active) LabelGray else FaintGray,
                        )
                        Text(c.slug, style = MonoLabel, color = FaintGray)
                    }
                    Text("EDIT", style = MonoLabel, color = SkyBlue)
                }
            }
        }
    }
    editing?.let { c -> CourseEditor(c, vm) { editing = null } }
}

@Composable
private fun CourseEditor(initial: CourseDto, vm: AdminViewModel, onClose: () -> Unit) {
    var title by remember { mutableStateOf(initial.title) }
    var slug by remember { mutableStateOf(initial.slug) }
    var type by remember { mutableStateOf(initial.type) }
    var difficulty by remember { mutableStateOf(initial.difficulty) }
    var months by remember { mutableStateOf(initial.durationMonths.toString()) }
    var target by remember { mutableStateOf(initial.target ?: "FULL BODY") }
    var equipment by remember { mutableStateOf(initial.equipment ?: "BODYWEIGHT") }
    var description by remember { mutableStateOf(initial.description ?: "") }
    var schedule by remember { mutableStateOf(initial.scheduleJson?.toString() ?: "{\"days\":{\"0\":\"FULL\",\"3\":\"FULL\"},\"session_min\":[30,90]}") }
    var sort by remember { mutableStateOf(initial.sort.toString()) }
    var active by remember { mutableStateOf(initial.active) }

    OverlaySheet(onClose) {
        Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Grid.S8)) {
            SectionTitle("COURSE EDITOR", SkyBlue)
            Field(title, { title = it }, "TITLE")
            Field(slug, { slug = it }, "SLUG (asset key)")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("MUSCLE", "SPECIAL", "FORBIDDEN").forEach { t
                    -> FilterChipPill(t, type == t) { type = t } }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("EASY", "MEDIUM", "HARD").forEach { d -> FilterChipPill(d, difficulty == d) { difficulty = d } }
            }
            Field(months, { months = it }, "DURATION (MONTHS)")
            Field(target, { target = it }, "TARGET AREA")
            Field(equipment, { equipment = it }, "EQUIPMENT")
            Field(description, { description = it }, "DESCRIPTION")
            Field(schedule, { schedule = it }, "SCHEDULE JSON (days / session_min / rest_sec_between_sets)")
            Field(sort, { sort = it }, "SORT ORDER")
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilterChipPill(if (active) "ACTIVE" else "INACTIVE", active) { active = !active }
            }
            NeonButton("PUBLISH COURSE", {
                vm.upsertCourse(
                    initial.copy(
                        title = title, slug = slug, type = type, difficulty = difficulty,
                        durationMonths = months.toIntOrNull() ?: 6, target = target,
                        equipment = equipment, description = description,
                        sort = sort.toIntOrNull() ?: 50, active = active,
                    ),
                    schedule,
                )
                onClose()
            }, Modifier.fillMaxWidth(), color = SkyBlue)
            GhostButton("CANCEL", onClose, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun CourseQuestsTab(s: AdminState, vm: AdminViewModel) {
    var courseSlug by remember { mutableStateOf(s.courses.firstOrNull()?.slug) }
    var editing by remember { mutableStateOf<CourseQuestDto?>(null) }

    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            s.courses.take(12).forEach { c ->
                FilterChipPill(c.slug.take(14), courseSlug == c.slug) {
                    courseSlug = c.slug
                    c.slug.let { vm.loadCourseQuests(it) }
                }
            }
        }
        Spacer(Modifier.height(Grid.S8))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("COURSE → WEEK → DAY → QUEST", style = MonoLabel, color = LabelGray, modifier = Modifier.weight(1f))
            GhostButton("NEW QUEST", {
                editing = CourseQuestDto(
                    id = 0, courseId = s.courses.firstOrNull { it.slug == courseSlug }?.id ?: "",
                    title = "", week = 0, day = 0, sort = 1,
                )
            })
        }
        Spacer(Modifier.height(Grid.S8))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(s.courseQuests) { q ->
                GlowCard(modifier = Modifier.fillMaxWidth().clickable { editing = q }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("W${q.week + 1}·D${q.day + 1}", style = MonoLabel, color = LabelGray,
                            modifier = Modifier.width(56.dp))
                        Column(Modifier.weight(1f)) {
                            Text(q.title, color = PaperWhite, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${q.sets}×${q.reps} · ${q.exercise} · ${q.xp} XP · ${q.verification} · ${q.difficulty}",
                                style = MaterialTheme.typography.bodySmall, color = LabelGray,
                            )
                        }
                        Text(if (q.active) "ACTIVE" else "OFF", style = MonoLabel,
                            color = if (q.active) SkyBlue else FaintGray)
                    }
                }
            }
        }
    }

    editing?.let { q ->
        var title by remember { mutableStateOf(q.title) }
        var desc by remember { mutableStateOf(q.description) }
        var exercise by remember { mutableStateOf(q.exercise) }
        var sets by remember { mutableStateOf(q.sets.toString()) }
        var reps by remember { mutableStateOf(q.reps) }
        var duration by remember { mutableStateOf(q.durationSec.toString()) }
        var rest by remember { mutableStateOf(q.restSec.toString()) }
        var xp by remember { mutableStateOf(q.xp.toString()) }
        var difficulty by remember { mutableStateOf(q.difficulty) }
        var verification by remember { mutableStateOf(q.verification) }
        var equipment by remember { mutableStateOf(q.equipment) }
        var alternatives by remember { mutableStateOf(q.alternatives) }
        var minAge by remember { mutableStateOf(q.minAge?.toString() ?: "") }
        var maxAge by remember { mutableStateOf(q.maxAge?.toString() ?: "") }
        var minLevel by remember { mutableStateOf(q.minLevel.toString()) }
        var active by remember { mutableStateOf(q.active) }

        OverlaySheet({ editing = null }) {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Grid.S8)) {
                SectionTitle("COURSE QUEST", SkyBlue)
                Field(title, { title = it }, "TITLE")
                Field(desc, { desc = it }, "DESCRIPTION")
                Field(exercise, { exercise = it }, "EXERCISE (PUSHUP/SQUAT/LUNGE/CORE/PLANK/RUN...)")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field(sets, { sets = it }, "SETS", Modifier.weight(1f))
                    Field(reps, { reps = it }, "REPS", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field(duration, { duration = it }, "DURATION s", Modifier.weight(1f))
                    Field(rest, { rest = it }, "REST s", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field(xp, { xp = it }, "XP", Modifier.weight(1f))
                    Field(minLevel, { minLevel = it }, "MIN LEVEL", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field(minAge, { minAge = it }, "MIN AGE", Modifier.weight(1f))
                    Field(maxAge, { maxAge = it }, "MAX AGE", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("CAMERA", "STEPS", "TIMER", "MANUAL").forEach { v ->
                        FilterChipPill(v, verification == v) { verification = v }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("EASY", "MEDIUM", "HARD").forEach { d -> FilterChipPill(d, difficulty == d) { difficulty = d } }
                }
                Field(equipment, { equipment = it }, "EQUIPMENT")
                Field(alternatives, { alternatives = it }, "ALTERNATIVES (comma separated)")
                FilterChipPill(if (active) "ACTIVE" else "OFF", active) { active = !active }
                NeonButton("PUBLISH QUEST", {
                    vm.upsertCourseQuest(
                        q.copy(
                            title = title, description = desc, exercise = exercise,
                            sets = sets.toIntOrNull() ?: 3, reps = reps,
                            durationSec = duration.toIntOrNull() ?: 0, restSec = rest.toIntOrNull() ?: 120,
                            xp = xp.toIntOrNull() ?: 20, difficulty = difficulty,
                            verification = verification, equipment = equipment, alternatives = alternatives,
                            minAge = minAge.toIntOrNull(), maxAge = maxAge.toIntOrNull(),
                            minLevel = minLevel.toIntOrNull() ?: 1, active = active,
                        ),
                        courseSlug ?: "",
                    )
                    editing = null
                }, Modifier.fillMaxWidth(), color = SkyBlue)
                GhostButton("CANCEL", { editing = null }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun DailyQuestsTab(s: AdminState, vm: AdminViewModel) {
    var editing by remember { mutableStateOf<QuestTemplateDto?>(null) }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("DAILY PROTOCOL BLOCKS · 01 → 02 → 03", style = MonoLabel, color = LabelGray,
                modifier = Modifier.weight(1f))
            GhostButton("NEW BLOCK", {
                editing = QuestTemplateDto(seq = (s.questTemplates.maxOfOrNull { it.seq } ?: 0) + 1, title = "",
                    exerciseKind = "PUSHUP", targetLight = 8, targetSteady = 15)
            })
        }
        Spacer(Modifier.height(Grid.S8))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(s.questTemplates, key = { it.seq }) { t ->
                GlowCard(modifier = Modifier.fillMaxWidth().clickable { editing = t }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.seq.toString().padStart(2, '0'), style = MonoData, color = SkyBlue,
                            modifier = Modifier.width(30.dp))
                        Column(Modifier.weight(1f)) {
                            Text(t.title.ifBlank { "UNTITLED" }, color = PaperWhite, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${t.exerciseKind} · light ${t.targetLight} / steady ${t.targetSteady} ${t.targetUnit} · " +
                                    "rest ${t.restSec}s · ${t.xp} XP · ${t.verification}",
                                style = MaterialTheme.typography.bodySmall, color = LabelGray,
                            )
                        }
                        Text(if (t.active) "ON" else "OFF", style = MonoLabel,
                            color = if (t.active) SkyBlue else FaintGray)
                    }
                }
            }
        }
    }

    editing?.let { t ->
        var seq by remember { mutableStateOf(t.seq.toString()) }
        var title by remember { mutableStateOf(t.title) }
        var kind by remember { mutableStateOf(t.exerciseKind) }
        var light by remember { mutableStateOf(t.targetLight.toString()) }
        var steady by remember { mutableStateOf(t.targetSteady.toString()) }
        var unit by remember { mutableStateOf(t.targetUnit) }
        var est by remember { mutableStateOf(t.estDurationSec.toString()) }
        var rest by remember { mutableStateOf(t.restSec.toString()) }
        var xp by remember { mutableStateOf(t.xp.toString()) }
        var difficulty by remember { mutableStateOf(t.difficulty) }
        var verification by remember { mutableStateOf(t.verification) }
        var active by remember { mutableStateOf(t.active) }

        OverlaySheet({ editing = null }) {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Grid.S8)) {
                SectionTitle("DAILY BLOCK", SkyBlue)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field(seq, { seq = it }, "SEQ", Modifier.weight(1f))
                    Field(xp, { xp = it }, "XP", Modifier.weight(1f))
                }
                Field(title, { title = it }, "TITLE")
                Field(kind, { kind = it }, "EXERCISE KIND")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field(light, { light = it }, "TARGET · LIGHT", Modifier.weight(1f))
                    Field(steady, { steady = it }, "TARGET · STEADY", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("REPS", "METERS", "SECONDS").forEach { u -> FilterChipPill(u, unit == u) { unit = u } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field(est, { est = it }, "EST DURATION s", Modifier.weight(1f))
                    Field(rest, { rest = it }, "RECOVERY s", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("EASY", "MEDIUM", "HARD").forEach { d -> FilterChipPill(d, difficulty == d) { difficulty = d } }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("CAMERA", "STEPS", "TIMER", "MANUAL").forEach { v ->
                        FilterChipPill(v, verification == v) { verification = v }
                    }
                }
                FilterChipPill(if (active) "ACTIVE" else "OFF", active) { active = !active }
                NeonButton("PUBLISH BLOCK", {
                    vm.upsertQuestTemplate(
                        t.copy(
                            seq = seq.toIntOrNull() ?: t.seq, title = title, exerciseKind = kind,
                            targetLight = light.toIntOrNull() ?: t.targetLight,
                            targetSteady = steady.toIntOrNull() ?: t.targetSteady,
                            targetUnit = unit, estDurationSec = est.toIntOrNull() ?: 300,
                            restSec = rest.toIntOrNull() ?: 150, xp = xp.toIntOrNull() ?: 35,
                            difficulty = difficulty, verification = verification, active = active,
                        )
                    )
                    editing = null
                }, Modifier.fillMaxWidth(), color = SkyBlue)
                GhostButton("CANCEL", { editing = null }, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun BlackRoomAdminTab(s: AdminState, vm: AdminViewModel) {
    var openBuilderFor by remember { mutableStateOf<BlackRoomApplicationDto?>(null) }
    Column(Modifier.fillMaxWidth()) {
        Text("APPLICATIONS · gate answers + manual payment", style = MonoLabel, color = LabelGray)
        Spacer(Modifier.height(Grid.S8))
        if (s.blackRooms.isEmpty()) EmptyState("No Black Room applications yet.")
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(s.blackRooms, key = { it.id }) { a ->
                GlowCard(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("@${a.username ?: a.userId.take(8)}", color = PaperWhite, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "₹%.0f / $%.0f · ${a.status} · ${a.createdAt?.take(10) ?: ""}",
                                style = MaterialTheme.typography.bodySmall, color = LabelGray,
                            )
                            a.criteria?.let { c ->
                                Text(
                                    "ledger: ${c["checks"]?.let { ch -> ch.toString().take(120) } ?: ""}",
                                    style = MaterialTheme.typography.bodySmall, color = FaintGray,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(Grid.S8))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (a.status == "PENDING") {
                            NeonButton("APPROVE", { vm.reviewBlackRoom(a.id, true) }, color = SkyBlue)
                            NeonButton("REJECT", { vm.reviewBlackRoom(a.id, false) }, color = LabelGray)
                        }
                        GhostButton("BUILD PROGRAM", { openBuilderFor = a })
                    }
                }
            }
        }
    }

    openBuilderFor?.let { a ->
        var week by remember { mutableStateOf("0") }
        var day by remember { mutableStateOf("0") }
        var title by remember { mutableStateOf("") }
        var exercise by remember { mutableStateOf("GENERAL") }
        var sets by remember { mutableStateOf("3") }
        var reps by remember { mutableStateOf("10") }
        var duration by remember { mutableStateOf("0") }
        var rest by remember { mutableStateOf("150") }
        var notes by remember { mutableStateOf("") }
        OverlaySheet({ openBuilderFor = null }) {
            Column(Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Grid.S8)) {
                SectionTitle("BLACK ROOM PROGRAM · @${a.username ?: ""}", SkyBlue)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field(week, { week = it }, "WEEK", Modifier.weight(1f))
                    Field(day, { day = it }, "DAY 0-6", Modifier.weight(1f))
                }
                Field(title, { title = it }, "SESSION TITLE")
                Field(exercise, { exercise = it }, "PRIMARY EXERCISE")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field(sets, { sets = it }, "SETS", Modifier.weight(1f))
                    Field(reps, { reps = it }, "REPS", Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Field(duration, { duration = it }, "DURATION s", Modifier.weight(1f))
                    Field(rest, { rest = it }, "REST s", Modifier.weight(1f))
                }
                Field(notes, { notes = it }, "PROGRESSION / RECOVERY NOTES")
                NeonButton("SAVE ROW", {
                    vm.saveBlackRoomProgram(
                        a.id, week.toIntOrNull() ?: 0, day.toIntOrNull() ?: 0, title, exercise,
                        sets.toIntOrNull() ?: 3, reps, duration.toIntOrNull() ?: 0,
                        rest.toIntOrNull() ?: 150, notes,
                    )
                    openBuilderFor = null
                }, Modifier.fillMaxWidth(), color = SkyBlue)
                GhostButton("CLOSE", { openBuilderFor = null }, Modifier.fillMaxWidth())
            }
        }
    }
}

// ── shared admin bits ───────────────────────────────────────────────────────

@Composable
private fun Field(value: String, onChange: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange,
        label = { Text(label, style = MonoLabel) },
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun OverlaySheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.8f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .background(PanelGray)
                .border(1.dp, SkyBlue.copy(alpha = 0.45f), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .clickable(enabled = false) {}
                .padding(Grid.S16),
        ) { content() }
    }
}
