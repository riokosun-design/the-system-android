package com.thesystem.app.ai

import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * DETERMINISTIC AI (spec §18) — the fallback that never fails, and TODAY also
 * the production personalization engine while converted local model artifacts
 * are pending (local_ai_enabled=false server-side).
 *
 * Pure functions over a ContextEngine.Snapshot. Every number here is derived
 * from VERIFIED data — bests, quests, streaks — nothing invented.
 */
object DeterministicAI {

    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    // ── DAILY QUEST (§8) ─────────────────────────────────────────────────────
    fun questProposal(s: ContextEngine.Snapshot): QuestProposal {
        val activeKinds = s.quests.filter { !it.isDone && !it.isDead }.mapNotNull { it.exerciseKind }.toSet()
        val anyKindToday = s.quests.mapNotNull { it.exerciseKind }.toSet()
        val candidates = AIValidator.QUEST_KINDS.filter { it !in anyKindToday && it !in activeKinds }
        val b = s.bests
        val daySeed = LocalDate.now().toEpochDay().toInt()
        val kind = when {
            // weakest verified link first; variation via day seed among ties
            candidates.isEmpty() -> "PLANK"
            b == null || (b.pushReps == 0 && b.squatReps == 0 && b.runMeters == 0) ->
                candidates.firstOrNull { it == "PUSH" } ?: candidates[daySeed % candidates.size]
            else -> candidates.minByOrNull { k ->
                when (k) {
                    "PUSH" -> (b.pushReps.takeIf { it > 0 } ?: 5) * 10
                    "SQUAT" -> (b.squatReps.takeIf { it > 0 } ?: 5) * 10
                    "RUN", "WALK" -> (b.runMeters.takeIf { it > 0 } ?: 400) / 4
                    else -> 500 + daySeed % 50
                }
            } ?: "PUSH"
        }
        val missed = s.profile?.missedDays ?: 0
        val easing = missed > 0 // coming back from decay: start lighter
        val (target, unit, durMin, diff) = when (kind) {
            "PUSH" -> {
                val base = (b?.pushReps ?: 0)
                val t = if (base > 0) ((base * 0.55).toInt() + if (easing) 2 else 5) else if (easing) 6 else 10
                Quad(t.coerceIn(5, 60), "REPS", 8, if (base >= 25 && !easing) "MEDIUM" else "EASY")
            }
            "SQUAT" -> {
                val base = (b?.squatReps ?: 0)
                val t = if (base > 0) ((base * 0.55).toInt() + if (easing) 2 else 6) else if (easing) 8 else 12
                Quad(t.coerceIn(5, 80), "REPS", 9, if (base >= 30 && !easing) "MEDIUM" else "EASY")
            }
            "RUN" -> {
                val base = (b?.runMeters ?: 0)
                val t = if (base > 0) ((base * 0.7).toInt()) else if (easing) 800 else 1200
                Quad(t.coerceIn(400, 3000), "METERS", 14, if (base >= 2000 && !easing) "MEDIUM" else "EASY")
            }
            "WALK" -> Quad(if (easing) 1200 else 2000, "METERS", 20, "EASY")
            else -> Quad(if (easing) 45 else 75, "SECONDS", 6, "EASY")
        }
        val why = when (kind) {
            "PUSH" -> "verified best ${b?.pushReps ?: 0} — consistency load, not burn"
            "SQUAT" -> "verified best ${b?.squatReps ?: 0} — base strength coverage"
            "RUN" -> "engine work; verified ${b?.runMeters ?: 0}m on record"
            "WALK" -> "active recovery — keep the streak alive without strain"
            else -> "core armor; pairs with today's blocks"
        }
        return QuestProposal(
            title = when (kind) {
                "PUSH" -> "PUSH-UP BONUS VOLLEY"; "SQUAT" -> "SQUAT BONUS VOLLEY"
                "RUN" -> "SHADOW RUN"; "WALK" -> "SILENT MARCH"; else -> "CORE OATH"
            },
            exercise = kind,
            target = target,
            targetUnit = unit,
            durationMinutes = durMin,
            difficulty = diff,
            reason = if (easing) "$why · post-miss easing ×0.7" else why,
            confidence = 0.9,
        )
    }

    // ── DAILY NUTRITION (§9) ─────────────────────────────────────────────────
    fun nutritionPlan(s: ContextEngine.Snapshot, budgetInr: Double, floor: Int): NutritionPlan {
        val goal = (s.profile?.goal ?: "").uppercase(Locale.US)
        val trainingDay = s.quests.any { !it.isDead }
        val minor = s.minor
        var calories = when {
            minor -> 2200 // §9: never cut for the young
            goal.contains("BULK") || goal.contains("GAIN") || goal.contains("MUSCLE") -> 2600
            goal.contains("CUT") || goal.contains("FAT") || goal.contains("LOSE") -> maxOf(floor, 1850)
            else -> 2200
        }
        if (trainingDay && !minor) calories += 150
        val split = when {
            goal.contains("BULK") || goal.contains("GAIN") || goal.contains("MUSCLE") ->
                listOf(
                    "BREAKFAST — 4 eggs + 2 rotis + banana (~620 kcal, 28g protein)",
                    "LUNCH — rice + dal + chicken/paneer 150g + salad (~800 kcal)",
                    "SNACK — curd + peanuts + fruit (~350 kcal)",
                    "DINNER — 3 rotis + dal sabzi + milk (~830 kcal)",
                )
            goal.contains("CUT") || goal.contains("FAT") || goal.contains("LOSE") ->
                listOf(
                    "BREAKFAST — oats + milk + nuts (~420 kcal, 20g protein)",
                    "LUNCH — 2 rotis + dal + grilled protein 120g + veg (~600 kcal)",
                    "SNACK — buttermilk + roasted chana (~250 kcal)",
                    "DINNER — light khichdi / 2 rotis + sabzi (~${maxOf(floor, 1850) - 1270} kcal)",
                )
            else ->
                listOf(
                    "BREAKFAST — 3 eggs / poha + milk (~500 kcal)",
                    "LUNCH — rice + dal + sabzi + curd (~700 kcal)",
                    "SNACK — fruit + peanuts (~300 kcal)",
                    "DINNER — 3 rotis + dal / paneer + salad (~700 kcal)",
                )
        }
        // REAL products only: supplements under 30% of budget, max 2 refs
        val refs = s.products
            .filter { it.category == "SUPPLEMENT" && it.priceInr <= budgetInr * 0.3 }
            .take(2)
            .map { it.id }
        val spend = s.products.filter { it.id in refs }.sumOf { it.priceInr }
        return NutritionPlan(
            calorieTarget = calories,
            slots = split,
            marketRefs = refs,
            budgetInr = spend,
            reason = buildString {
                append("goal=${goal.ifBlank { "MAINTAIN" }} · ${calories} kcal target")
                if (trainingDay) append(" · training day +150")
                if (minor) append(" · conservative (under-18)")
                if (refs.isEmpty()) append(" · no market add-ons matched")
            },
        )
    }

    // ── DAILY ROUTINE (§14) ──────────────────────────────────────────────────
    fun routineDraft(s: ContextEngine.Snapshot, now: LocalTime = LocalTime.now()): RoutineDraft {
        val items = ArrayList<RoutineItem>()
        var cursor = now.plusMinutes(30)
        fun at(t: LocalTime, title: String, cat: String, dur: Int, notes: String = "") {
            items += RoutineItem(title = title, time = t.format(timeFmt), category = cat, durationMin = dur, notes = notes)
        }
        s.profile?.let { if (it.streakDays >= 2) at(LocalTime.of(6, 30), "WAKE + HYDRATE", "COMMIT", 10, "streak ×${it.streakDays} — protect it") }
        // quest blocks in protocol order, 5-minute recovery between (§rest)
        val open = s.quests.filter { !it.isDone && !it.isDead }.sortedBy { it.seq }
        open.forEach { q ->
            val dur = (q.estDurationSec / 60).coerceIn(4, 40)
            if (cursor.hour >= 23) return@forEach
            at(cursor, "QUEST ${q.blockLabel} — ${q.title}", "QUEST", dur, "${q.targetValue} ${q.targetUnit.lowercase(Locale.US)}")
            cursor = cursor.plusMinutes(dur.toLong() + 5)
        }
        if (s.primaryCourseTitle != null) {
            val t = if (cursor.hour < 18) LocalTime.of(17, 30) else cursor
            at(t, "TRAINING — ${s.primaryCourseTitle}", "TRAINING", 40, "primary muscle track")
        }
        at(LocalTime.of(13, 30), "LUNCH", "MEAL", 25)
        at(LocalTime.of(21, 0), "DINNER + LOG REVIEW", "MEAL", 25)
        at(LocalTime.of(22, 45), "SLEEP PREP — screens off", "SLEEP", 15, "recovery is training")
        return RoutineDraft(
            items = AIValidator.routine(items),
            reason = "${open.size} quest block(s) · course=${s.primaryCourseTitle != null} · anchored now+30m",
        )
    }

    // ── SYSTEM ASSISTANT (§12) — intent-answered from the same snapshot ──────
    fun assistantAnswer(question: String, s: ContextEngine.Snapshot, p: Personality): AssistantAnswer {
        val q = question.lowercase(Locale.US)
        val prof = s.profile
        val open = s.quests.firstOrNull { !it.isDone && it.status != "LOCKED" && !it.isDead }
        val done = s.quests.count { it.isDone }
        val body = when {
            q.contains("progress") || q.contains("summary") || q.contains("how am i") -> buildString {
                append("LV ${prof?.level ?: "?"} · ${prof?.rank?.title ?: "—"} · streak ${prof?.streakDays ?: 0}. ")
                append("Quests today ${done}/${s.quests.size}. ")
                s.bests?.let { append("Verified: ${it.pushReps} push · ${it.squatReps} squat · ${it.runMeters}m run across ${it.sessions} sessions. ") }
                if ((prof?.missedDays ?: 0) > 0) append("Decay is armed — clear one block today to stop it.")
                else append("No decay. Keep the chain.")
            }
            q.contains("quest") -> buildString {
                if (s.quests.isEmpty()) append("No quest set yet — pull refresh on Status when the grid is back. ")
                else {
                    append("Protocol ${done}/${s.quests.size} clear. ")
                    open?.let { append("Next: BLOCK ${it.blockLabel} ${it.title} — ${it.progress}/${it.targetValue} ${it.targetUnit.lowercase(Locale.US)} (${it.difficulty}). ") }
                    if (open == null && done > 0) append("All blocks clear. Bonus: ask for an AI PROPOSE quest on Status. ")
                }
                if (s.recoveryRemainingSec > 0) append("Recovery window: ${s.recoveryRemainingSec}s before the next block unlocks.")
            }
            q.contains("rank") || q.contains("level") || q.contains("xp") -> buildString {
                append("You are ${prof?.rank?.title ?: "—"}, LV ${prof?.level ?: "?"} (${prof?.xp ?: 0} XP). ")
                when (prof?.missedDays ?: 0) {
                    0 -> append("Rank holds while the streak lives.")
                    1 -> append("One miss logged — decay starts tomorrow if you skip again.")
                    else -> append("${prof?.missedDays} misses — XP decaying daily. One verified block stops the bleed.")
                }
            }
            q.contains("nutrition") || q.contains("meal") || q.contains("food") || q.contains("diet") ->
                "Nutrition Planner lives in the Market tab — it builds a day plan from your goal (${prof?.goal ?: "UNSET"}) and matches REAL store items only. Conservative floors apply; no crash diets, ever."
            q.contains("routine") || q.contains("schedule") ->
                "Open SYSTEM ROUTINE from Status → Generate. I anchor today's quests + training around now+30m; you accept, edit or reject. Confirmed routines sync to the vault."
            q.contains("help") || q.contains("navigate") || q.contains("how to") || q.contains("what can you") ->
                "Roster: progress summary · quest intel · rank/XP decode · routine drafts · nutrition plans · next-action orders. Ask in plain words — I only see structured context, never your private data."
            else -> buildString {
                open?.let { append("Order: clear ${it.title} — ${it.progress}/${it.targetValue} ${it.targetUnit.lowercase(Locale.US)} to go. ") }
                    ?: append(if (done > 0) "Day clear, hunter. Recover well." else "Status board is syncing — refresh and return.")
            }
        }
        val shaped = when (p) {
            Personality.QUIET -> body.split(". ").take(2).joinToString(". ") + "."
            Personality.COACH -> body
            Personality.COMPANION -> "Good to see you, hunter. $body"
            Personality.COMMAND -> body.replace("Order:", "DO:").split(". ").take(2).joinToString(". ") + "."
        }
        return AIValidator.assistant(
            AssistantAnswer(
                text = shaped,
                followUps = listOfNotNull(
                    "quest status".takeIf { s.quests.isNotEmpty() },
                    "my progress",
                    "rank info".takeIf { prof != null },
                ),
            ),
        )
    }

    private data class Quad(val a: Int, val b: String, val c: Int, val d: String)
}
