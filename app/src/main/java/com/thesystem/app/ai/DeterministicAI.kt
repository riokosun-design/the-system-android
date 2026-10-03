package com.thesystem.app.ai

import com.thesystem.app.core.FitnessMath
import com.thesystem.app.core.routine.RoutineEngine
import com.thesystem.app.data.model.VerifiedBestsDto
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * DETERMINISTIC AI (master prompt §3–§12) — the expert rules brain.
 *
 * Two jobs:
 *  1. The safety net that never fails behind the local LLM ladder.
 *  2. TODAY's production fitness intelligence: every answer is assembled
 *     from VERIFIED snapshot facts (profile, camera-verified bests, streak /
 *     decay counters, course catalogue) through FitnessIntelligence — the
 *     fixed pipeline PROFILE → STATE → HISTORY → GOAL → RECOVERY → RULES.
 *
 * It NEVER: invents user history, grants XP/quests/ranks, diagnoses a
 * medical state, or calls itself a doctor (§4).
 */
object DeterministicAI {

    private val timeFmt: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    // ── DAILY QUEST (§5, §6, §11) ────────────────────────────────────────────
    fun questProposal(s: ContextEngine.Snapshot): QuestProposal {
        val activeKinds = s.quests.filter { !it.isDone && !it.isDead }.mapNotNull { it.exerciseKind }.toSet()
        val anyKindToday = s.quests.mapNotNull { it.exerciseKind }.toSet()
        val candidates = AIValidator.QUEST_KINDS.filter { it !in anyKindToday && it !in activeKinds }
        val b = s.bests
        val daySeed = LocalDate.now().toEpochDay().toInt()
        val kind = when {
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
        // §6: continuous comeback easing — missed days scale VOLUME, never punish
        val comeback = s.comebackFactor
        val best = when (kind) {
            "PUSH" -> b?.pushReps ?: 0
            "SQUAT" -> b?.squatReps ?: 0
            "RUN", "WALK" -> b?.runMeters ?: 0
            else -> 0
        }
        val rich = if (best > 0) FitnessIntelligence.richQuest(kind, best, comeback) else when (kind) {
            "PUSH" -> FitnessIntelligence.richQuest("PUSH", 10, comeback)
            "SQUAT" -> FitnessIntelligence.richQuest("SQUAT", 15, comeback)
            "RUN" -> FitnessIntelligence.RichQuest((1200 * comeback).toInt().coerceIn(400, 1500), "easy pace · walk breaks allowed", "no verified run yet — base building, not speed")
            "WALK" -> FitnessIntelligence.richQuest("WALK", 0, comeback)
            else -> FitnessIntelligence.richQuest("PLANK", 0, comeback)
        }
        val (unit, durMin) = when (kind) {
            "RUN", "WALK" -> "METERS" to if (kind == "RUN") 14 else 20
            "PLANK" -> "SECONDS" to 6
            else -> "REPS" to if (kind == "PUSH") 8 else 9
        }
        val strong = when (kind) {
            "PUSH" -> (b?.pushReps ?: 0) >= 25
            "SQUAT" -> (b?.squatReps ?: 0) >= 30
            "RUN", "WALK" -> (b?.runMeters ?: 0) >= 2000
            else -> false
        }
        return QuestProposal(
            title = when (kind) {
                "PUSH" -> "PUSH-UP BONUS VOLLEY"; "SQUAT" -> "SQUAT BONUS VOLLEY"
                "RUN" -> "SHADOW RUN"; "WALK" -> "SILENT MARCH"; else -> "CORE OATH"
            },
            exercise = kind,
            target = rich.target,
            targetUnit = unit,
            durationMinutes = durMin,
            difficulty = if (strong && comeback == 1.0) "MEDIUM" else "EASY",
            reason = rich.structure + " · " + rich.note +
                if (comeback < 1.0) " · comeback ×${"%.2f".format(comeback)}" else "",
            confidence = 0.9,
        )
    }

    // ── DAILY NUTRITION (§3, §9 science-first) ───────────────────────────────
    fun nutritionPlan(s: ContextEngine.Snapshot, budgetInr: Double, floor: Int): NutritionPlan {
        val body = FitnessIntelligence.body(s.profile, floor)
        val goal = (s.profile?.goal ?: "").uppercase(Locale.US)
        val trainingDay = s.quests.any { !it.isDead }
        val minor = s.minor
        // §2: calories come from app math (FitnessMath), the fallback heuristic
        // only fires when the profile literally lacks the numbers.
        val calories = body.calorieTarget ?: run {
            var c = when {
                minor -> 2200
                goal.contains("BULK") || goal.contains("GAIN") || goal.contains("MUSCLE") -> 2600
                goal.contains("CUT") || goal.contains("FAT") || goal.contains("LOSE") -> maxOf(floor, 1850)
                else -> 2200
            }
            if (trainingDay && !minor) c += 150
            c
        }
        val gaining = goal.contains("BULK") || goal.contains("GAIN") || goal.contains("MUSCLE")
        val cutting = goal.contains("CUT") || goal.contains("FAT") || goal.contains("LOSE")
        val slots = when {
            gaining -> listOf(
                "BREAKFAST — 4 eggs + 2 rotis + banana (~620 kcal, 28g protein)",
                "LUNCH — rice + dal + chicken/paneer 150g + salad (~800 kcal)",
                "SNACK — curd + peanuts + fruit (~350 kcal)",
                "DINNER — 3 rotis + dal sabzi + milk (~830 kcal)",
            )
            cutting -> listOf(
                "BREAKFAST — oats + milk + nuts (~420 kcal, 20g protein)",
                "LUNCH — 2 rotis + dal + grilled protein 120g + veg (~600 kcal)",
                "SNACK — buttermilk + roasted chana (~250 kcal)",
                "DINNER — light khichdi / 2 rotis + sabzi (~530 kcal)",
            )
            else -> listOf(
                "BREAKFAST — 3 eggs / poha + milk (~500 kcal)",
                "LUNCH — rice + dal + sabzi + curd (~700 kcal)",
                "SNACK — fruit + peanuts (~300 kcal)",
                "DINNER — 3 rotis + dal / paneer + salad (~700 kcal)",
            )
        }
        val refs = s.products
            .filter { it.category == "SUPPLEMENT" && it.priceInr <= budgetInr * 0.3 }
            .take(2)
            .map { it.id }
        val spend = s.products.filter { it.id in refs }.sumOf { it.priceInr }
        return NutritionPlan(
            calorieTarget = calories,
            slots = slots,
            marketRefs = refs,
            budgetInr = spend,
            reason = buildString {
                if (body.tdee != null) {
                    append("TDEE-est ${body.tdee} → $calories kcal")
                    if (gaining) append(" (+300 lean gain)")
                    if (cutting) append(" (−400 max, no crash cuts)")
                    body.proteinG?.let { append(" · protein ~${it}g") }
                } else {
                    append("goal=${goal.ifBlank { "MAINTAIN" }} · $calories kcal target (profile numbers incomplete — estimate)")
                    if (trainingDay) append(" · training day +150")
                }
                if (minor) append(" · conservative (under-18)")
                if (refs.isEmpty()) append(" · no market add-ons matched")
            },
        )
    }

    // ── DAILY ROUTINE (§6/§14 + STEP 7 week arc) — built AROUND anchors and
    //    the hunter's fixed life blocks. Week params come from the server-owned
    //    routine_week (mig 023) via RoutineEngine: anchors never move, work
    //    blocks scale, Sunday swaps training for recovery emphasis. ──────────
    fun routineDraft(
        s: ContextEngine.Snapshot,
        now: LocalTime = LocalTime.now(),
        today: LocalDate = LocalDate.now(),
    ): RoutineDraft {
        val plan = RoutineEngine.weekPlan(s.profile?.routineWeek ?: 1, s.minor)
        val isSunday = today.dayOfWeek == DayOfWeek.SUNDAY
        val items = ArrayList<RoutineItem>()

        // ANCHOR LAW (STEP 7) — immovable identity blocks, identical every week
        items += RoutineItem(
            "WAKE + HYDRATE", "06:30", "RECOVERY", 10,
            notes = "ANCHOR — never moves · sunlight + 500ml water",
        )
        items += RoutineItem(
            "LIGHTS OUT", "23:00", "SLEEP", 10,
            notes = "ANCHOR — never moves · 7.5h window begins",
        )

        s.commitments.forEach { c ->
            if (c.title.isBlank() || !c.start.matches(Regex("^([01]?\\d|2[0-3]):[0-5]\\d$"))) return@forEach
            val startMin = c.start.substringBefore(":").toInt() * 60 + c.start.substringAfter(":").toInt()
            val endMin = if (c.end.matches(Regex("^([01]?\\d|2[0-3]):[0-5]\\d$"))) {
                c.end.substringBefore(":").toInt() * 60 + c.end.substringAfter(":").toInt()
            } else startMin + 60
            items += RoutineItem(
                title = c.title.uppercase(Locale.US).take(36),
                time = c.start, category = "COMMIT",
                durationMin = (endMin - startMin).coerceIn(5, 240),
                notes = if (c.days == "DAILY") "" else c.days,
            )
        }

        fun busyAt(min: Int): Boolean = items.any { it.category == "COMMIT" } &&
            items.filter { it.category == "COMMIT" }.any {
                val sm = it.time.substringBefore(":").toInt() * 60 + it.time.substringAfter(":").toInt()
                min in sm until (sm + it.durationMin)
            }

        /** First free [dur]-minute slot at/after [fromMin]; returns end cursor or null. */
        fun findSlot(dur: Int, fromMin: Int): Int? {
            var t = fromMin
            var guard = 0
            while (t + dur <= 23 * 60 && guard++ < 40) {
                if (!busyAt(t) && !busyAt(t + dur - 5)) return t + dur
                t += 15
            }
            return null
        }

        val open = s.quests.filter { !it.isDone && !it.isDead }.sortedBy { it.seq }.take(5)
        val nowCursor = ((now.hour * 60 + now.minute) / 30 + 1) * 30

        // morning cluster — habit front-loading, capped by the week plan
        var cursor = maxOf(nowCursor, RoutineEngine.MORNING_SLOT_MIN)
        var morningPlaced = 0
        val deferred = ArrayList<com.thesystem.app.data.model.QuestDto>()
        open.forEach { q ->
            if (morningPlaced >= plan.morningQuestCap) { deferred += q; return@forEach }
            val dur = (q.estDurationSec / 60).coerceIn(4, 40)
            val end = findSlot(dur, cursor)
            if (end == null || cursor > 11 * 60) { deferred += q; return@forEach }
            items += RoutineItem(
                title = "QUEST — ${q.title.take(32)}",
                time = "%02d:%02d".format((end - dur) / 60, (end - dur) % 60),
                category = "QUEST", durationMin = dur,
                notes = "${q.targetValue} ${q.targetUnit.lowercase(Locale.US)} · morning cluster",
            )
            cursor = end + 15
            morningPlaced++
        }

        // evening cluster — overflow from 16:00, never before
        if (deferred.isNotEmpty()) {
            cursor = maxOf(cursor, RoutineEngine.EVENING_SLOT_MIN)
            deferred.forEach { q ->
                val dur = (q.estDurationSec / 60).coerceIn(4, 40)
                val end = findSlot(dur, cursor) ?: return@forEach
                items += RoutineItem(
                    title = "QUEST — ${q.title.take(32)}",
                    time = "%02d:%02d".format((end - dur) / 60, (end - dur) % 60),
                    category = "QUEST", durationMin = dur,
                    notes = "${q.targetValue} ${q.targetUnit.lowercase(Locale.US)}",
                )
                cursor = end + 15
            }
        }

        // primary work block — scaled by the week; Sunday = RECOVERY EMPHASIS
        // at the same slot (no skip-day psychology)
        if (s.primaryCourseTitle != null || isSunday) {
            var t = 17 * 60
            var guard = 0
            while (busyAt(t) && guard++ < 20) t += 30
            if (t <= 21 * 60) {
                if (isSunday) {
                    items += RoutineItem(
                        "RECOVERY EMPHASIS — MOBILITY + BREATHWORK",
                        "%02d:%02d".format(t / 60, t % 60), "RECOVERY", plan.recoveryMin + 20,
                        notes = "W${plan.week} ${plan.name} · lighter by design, anchors stand",
                    )
                } else if (s.primaryCourseTitle != null) {
                    items += RoutineItem(
                        title = "SYSTEM TRAINING — ${s.primaryCourseTitle.take(24)}",
                        time = "%02d:%02d".format(t / 60, t % 60),
                        category = "TRAINING", durationMin = plan.trainingMin,
                        notes = "W${plan.week} ${plan.name}",
                    )
                }
            }
        }

        // NEAT walk — the smallest habit that survives a bad day (week-scaled)
        run {
            var t = 18 * 60 + 30
            var guard = 0
            while (busyAt(t) && guard++ < 16) t += 30
            if (t <= 21 * 60) items += RoutineItem(
                "NEAT WALK", "%02d:%02d".format(t / 60, t % 60), "RECOVERY", plan.walkMin,
                notes = "${plan.walkMin} min · W${plan.week} base — outside, no phone",
            )
        }

        if (s.chess != null && !busyAt(20 * 60 + 30)) {
            items += RoutineItem(
                "CHESS TRAINING — MIND PROTOCOL", "20:30", "QUEST", 30,
                notes = "${s.chess.mentalRank}-rank · keep the mind sharp",
            )
        }

        if (!busyAt(13 * 60)) items += RoutineItem("LUNCH", "13:00", "MEAL", 25)
        if (!busyAt(21 * 60)) items += RoutineItem("DINNER", "21:00", "MEAL", 25)
        if (!busyAt(22 * 60 + 45)) items += RoutineItem(
            "WIND DOWN", "22:45", "SLEEP", plan.recoveryMin,
            notes = "recovery is training · W${plan.week}",
        )

        return RoutineDraft(
            items = AIValidator.routine(items),
            reason = "W${plan.week} ${plan.name} · anchors 06:30/23:00 fixed · ${open.size} quest block(s) · ${s.commitments.size} commitment(s)",
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SYSTEM ASSISTANT (§3–§12) — the fitness intelligence voice.
    // Order of gates: SAFETY → body facts → training intents → system intents.
    // Every fact read from the snapshot; unknowns are SAID unknown (§10).
    // ═══════════════════════════════════════════════════════════════════════
    fun assistantAnswer(question: String, s: ContextEngine.Snapshot, p: Personality): AssistantAnswer {
        val q = question.lowercase(Locale.US)

        // ── §4 SAFETY WALL — before everything, personality never dilutes it ──
        AIValidator.safetyBlockOf(q)?.let { block ->
            val text = if (block == "HARD_STOP") AIValidator.SAFETY_REPLY_HARD_STOP else AIValidator.SAFETY_REPLY_RED_FLAG
            return AssistantAnswer(text = text, followUps = listOf("sane calorie plan" , "comeback plan", "help"))
        }

        val prof = s.profile
        val open = s.quests.firstOrNull { !it.isDone && it.status != "LOCKED" && !it.isDead }
        val done = s.quests.count { it.isDone }
        val body = s.body
        val comeback = s.comebackFactor

        fun bodyReport(): String = buildString {
            if (!body.complete) {
                append("Body plate incomplete — I don't see age/height/weight on your vessel record, so I won't guess. Update your intake metrics and I'll compute BMR/TDEE honestly. ")
                append("Until then I can still plan from your verified bests."); return@buildString
            }
            append("Body readings: BMI ${body.bmi} (${body.bmiClass}) · BMR ≈${body.bmr} kcal · TDEE ≈${body.tdee} kcal at ${(prof?.activityLevel ?: "STEADY").uppercase()}. ")
            prof?.heightCm?.let { h ->
                FitnessMath.healthyWeightRange(h)?.let { (lo, hi) ->
                    prof.weightKg?.let { w ->
                        if (w < lo || w > hi) append("Healthy band for ${h.toInt()}cm sits ≈ ${lo}–${hi}kg — you're logged at ${w.toInt()}kg. ")
                    }
                }
            }
            if (body.weightStale) append("Scale reading is >30 days old — refresh it or the targets drift. ")
            append(FitnessMath.ESTIMATE_NOTE)
        }

        fun todaysMove(): String = buildString {
            open?.let {
                append("Order: BLOCK ${it.blockLabel} ${it.title} — ${it.progress}/${it.targetValue} ${it.targetUnit.lowercase(Locale.US)} to clear today. ")
            } ?: append(if (done > 0) "Protocol blocks: CLEAR. " else "No live blocks — refresh the Status grid. ")
            s.bests?.let { b ->
                val kind = if ((b.pushReps.takeIf { it > 0 } ?: 5) <= (b.squatReps.takeIf { it > 0 } ?: 5)) "PUSH" else "SQUAT"
                val rich = FitnessIntelligence.richQuest(kind, if (kind == "PUSH") b.pushReps.takeIf { it > 0 } ?: 10 else b.squatReps.takeIf { it > 0 } ?: 15, comeback)
                append("If spare capacity: ${rich.structure} (${rich.note}). ")
            }
            if (comeback < 1.0) append("Comeback scaling ×${"%.2f".format(comeback)} is armed — missed days shrink the volume, not the standards.")
        }

        val body_ = when {
            // ── BODY / METABOLISM (§1) ────────────────────────────────────────
            q.contains("bmi") || q.contains("bmr") || q.contains("tdee") || q.contains("weight") ||
                q.contains("metabolism") || q.contains("body") || q.contains("height") -> bodyReport()

            // ── CALORIES / FOOD / PROTEIN (§3, §17) ──────────────────────────
            q.contains("calori") || q.contains("protein") || q.contains("nutrition") || q.contains("meal") ||
                q.contains("food") || q.contains("diet") || q.contains("eat") -> buildString {
                if (body.calorieTarget != null) {
                    append("Daily target ≈${body.calorieTarget} kcal (TDEE ≈${body.tdee}, goal-adjusted). ")
                    body.proteinG?.let { append("Protein ≈${it}g/day — anchor every meal around it. ") }
                    body.waterMl?.let { append("Water ≈${it}ml. ") }
                } else {
                    append("Your intake metrics are incomplete, so I can't compute honest calories. Update age/height/weight and the numbers appear. ")
                }
                append("Full day plan with REAL market items: Market tab → Nutrition Planner. Conservative floors apply; no crash diets, ever.${if (s.minor) " You're under 18 — no deficit games, growth first." else ""}")
            }

            // ── HYDRATION (§3) ───────────────────────────────────────────────
            q.contains("water") || q.contains("hydrat") || q.contains("thirst") ->
                body.waterMl?.let {
                    "Hydration order: ≈${it}ml today (~${"%.1f".format(it / 1000.0)}L) spread across the day — more in training heat, never force-chug. Small steady glasses beat litre bombs."
                } ?: "Your weight isn't on record, so the standard applies: ≈2–3 litres spread across the day, more if you train. No dehydration shortcuts — ever."

            // ── COMEBACK / MISSED (§6, §9) ───────────────────────────────────
            q.contains("missed") || q.contains("comeback") || q.contains("reset") || q.contains("fell off") ||
                q.contains("streak") && (q.contains("lost") || q.contains("recover") || q.contains("back")) -> buildString {
                append("No excuses needed. Let's reset. ")
                when {
                    (prof?.missedDays ?: 0) <= 0 -> append("Your chain is live — streak ${prof?.streakDays ?: 0}. Protect it today. ")
                    else -> {
                        append("${prof?.missedDays} missed day(s) logged; comeback scaling ×${"%.2f".format(comeback)} is on — lighter entry, same discipline. ")
                        open?.let { append("Clear ONE block now (${it.title}) and decay stops bleeding. ⚔️ ") }
                            ?: append("One completed block today restarts the chain. ")
                    }
                }
            }

            // ── MUSCLE COURSE SESSION (§7) ───────────────────────────────────
            q.contains("muscle") || q.contains("course") || q.contains("gym") || q.contains("push-up plan") ||
                q.contains("program") || q.contains("sets") && q.contains("reps") -> buildString {
                if (s.primaryCourseTitle == null) {
                    append("No primary muscle course enrolled. ONE course is the law — pick yours on the Training catalog (muscle path = your main forge). ")
                    append("Until then, today's verified bests steer everything: ")
                    s.bests?.let { b ->
                        val rich = FitnessIntelligence.richQuest("PUSH", b.pushReps.takeIf { it > 0 } ?: 10, comeback)
                        append(rich.structure).append(". ")
                    }
                } else {
                    append("PRIMARY COURSE: ${s.primaryCourseTitle}. Today's session (band ${s.band.name}${if (comeback < 1.0) ", comeback easing" else ""}):\n")
                    FitnessIntelligence.muscleSession(s.band, comeback).forEach { append("• $it\n") }
                    append("Stop 1–2 reps shy of form failure. Pain is a stop sign, not a challenge.")
                }
            }

            // ── EQUIPMENT (§7 safe alternatives) ─────────────────────────────
            q.contains("equipment") || q.contains("dumbbell") || q.contains("no gym") || q.contains("home workout") ||
                q.contains("pull-up") || q.contains("barbell") -> buildString {
                append("No gym required — bodyweight-first, always. Safe swaps:\n")
                FitnessIntelligence.EQUIPMENT_EQUIVALENTS.take(3).forEach { (need, swap) -> append("• $need → $swap\n") }
                append("Household loads only when obviously safe (seams, straps, solid anchors). Anything sketchy stays on the floor.")
            }

            // ── SPECIAL TRACKS (§8 honest suggestions) ───────────────────────
            q.contains("special") || q.contains("speed") || q.contains("agility") || q.contains("stamina") ||
                q.contains("endurance") || q.contains("grip") || q.contains("reflex") || q.contains("mobility") -> buildString {
                val sugg = FitnessIntelligence.specialSuggestion(s.bests)
                if (sugg == null) {
                    append("No verified performance lines yet, so I can't suggest a special track honestly. Bank camera-verified push/squat/run bests first — then the data picks your track. ")
                } else {
                    append("${FitnessIntelligence.SUGGESTION_PHRASE} ${sugg.second}. Track: ${sugg.first}. ")
                    append("Specials stay OPTIONAL seasoning — the ONE primary muscle course is the meal.")
                }
            }

            // ── SLEEP / RECOVERY (§3, §17) ───────────────────────────────────
            q.contains("sleep") || q.contains("recovery") || q.contains("rest day") || q.contains("tired") -> buildString {
                append("Recovery is training. Target 7–9h in bed, screens off 30m before. ")
                if (s.recoveryRemainingSec > 0) append("Live recovery window: ${s.recoveryRemainingSec}s before the next block unlocks. ")
                if (s.band != FitnessIntelligence.Band.BEGINNER) append("One full rest day a week minimum — growth happens between sessions, not inside them. ")
                append("Quality beats volume when the body says it's cooked. ⚡")
            }

            // ── GOAL GUIDANCE (§3 science-first) ─────────────────────────────
            q.contains("goal") || q.contains("lose weight") || q.contains("gain") || q.contains("bulk") ||
                q.contains("cut") || q.contains("lean") || q.contains("fat loss") -> buildString {
                val g = (prof?.goal ?: "UNSET").uppercase()
                append("Logged goal: $g. ")
                if (body.tdee != null) {
                    when {
                        g.contains("CUT") || g.contains("FAT") || g.contains("LOSE") -> {
                            append("Fat loss pace that keeps muscle: ~0.25–0.5 kg/week via a ≤400 kcal deficit — your target ≈${body.calorieTarget} kcal. ")
                            append("Training stays; protein ${body.proteinG ?: ""}g shields the mass. Slower is safer; faster rebounds. ")
                        }
                        g.contains("BULK") || g.contains("GAIN") || g.contains("MUSCLE") -> {
                            append("Lean gain pace: +300 kcal over TDEE (≈${body.calorieTarget}), ~0.25–0.5 kg/month on the bar, not the belly. ")
                            append("Protein ≈${body.proteinG}g, progressive overload on the verified lines. ")
                        }
                        else -> append("Maintenance ≈${body.tdee} kcal — use surplus/deficit only with a declared goal; the System switches targets when you do. ")
                    }
                } else append("Complete your intake metrics and I'll attach exact calorie math to that goal. ")
                append("Change the goal in your profile and the system re-targets the same day.")
            }

            // ── PROGRESS (existing, deepened) ────────────────────────────────
            q.contains("progress") || q.contains("summary") || q.contains("how am i") -> buildString {
                append("LV ${prof?.level ?: "?"} · ${prof?.rank?.title ?: "—"} · streak ${prof?.streakDays ?: 0}. ")
                append("Quests today ${done}/${s.quests.size}. ")
                s.bests?.let { append("Verified: ${it.pushReps} push · ${it.squatReps} squat · ${it.runMeters}m run across ${it.sessions} sessions. ") }
                if (s.nextTargets.isNotEmpty()) {
                    append("Next overload marks: ${s.nextTargets.entries.joinToString(" · ") { "${it.key.lowercase()} ${it.value}" }}. ")
                }
                if ((prof?.missedDays ?: 0) > 0) append("Decay is armed — clear one block today to stop it.")
                else append("No decay. Keep the chain.")
            }

            // ── QUEST (existing, comeback-aware) ─────────────────────────────
            q.contains("quest") -> buildString {
                if (s.quests.isEmpty()) append("No quest set yet — pull refresh on Status when the grid is back. ")
                else {
                    append("Protocol ${done}/${s.quests.size} clear. ")
                    open?.let { append("Next: BLOCK ${it.blockLabel} ${it.title} — ${it.progress}/${it.targetValue} ${it.targetUnit.lowercase(Locale.US)} (${it.difficulty}). ") }
                    if (open == null && done > 0) append("All blocks clear. Bonus: ask for an AI PROPOSE quest on Status. ")
                }
                if (s.recoveryRemainingSec > 0) append("Recovery window: ${s.recoveryRemainingSec}s before the next block unlocks.")
            }

            // ── RANK / XP (existing) ─────────────────────────────────────────
            q.contains("rank") || q.contains("level") || q.contains("xp") -> buildString {
                append("You are ${prof?.rank?.title ?: "—"}, LV ${prof?.level ?: "?"} (${prof?.xp ?: 0} XP). ")
                when (prof?.missedDays ?: 0) {
                    0 -> append("Rank holds while the streak lives.")
                    1 -> append("One miss logged — decay starts tomorrow if you skip again.")
                    else -> append("${prof?.missedDays} misses — XP decaying daily. One verified block stops the bleed.")
                }
            }

            // ── MIND (existing) ──────────────────────────────────────────────
            q.contains("chess") || q.contains("mind") || q.contains("puzzle") || q.contains("mental") -> buildString {
                val c = s.chess
                if (c == null || (c.games == 0 && c.puzzlesAttempted == 0)) {
                    append("Mental ladder untouched. Open the CHESS tab — daily challenge takes two minutes and starts the record. ")
                } else {
                    append("Mind: ${c.mentalRank}-RANK LV ${c.mentalLevel} · rating ${c.rating} · ${c.wins}W/${c.draws}D/${c.losses}L · ${c.puzzlesSolved} puzzles banked. ")
                    val weakest = listOf("tactics", "focus", "memory", "calculation", "adaptability", "decision", "composure")
                        .map { it to c.stat(it) }.filter { it.second > 0 }.minByOrNull { it.second }
                    weakest?.let { append("Weakest signal: ${it.first.uppercase()} — run puzzles today to move it. ") }
                }
                if (q.contains("lose") || q.contains("loss") || q.contains("blunder")) {
                    append("Losses are data: after each engine game, MENTAL WAR logs your blunders and worst moment — study that square, not the shame. ")
                }
                if (q.contains("improve") || q.contains("better") || q.contains("train")) {
                    append("Route: DAILY CHALLENGE → PUZZLE TRAINING until tier rises → MENTAL WAR twice a week. No shortcuts, no noise. ")
                }
            }

            // ── TODAY'S MOVE (new core intent) ───────────────────────────────
            q.contains("workout") || q.contains("train") || q.contains("exercise") || q.contains("what should i do") ||
                q.contains("today") && (q.contains("plan") || q.contains("session") || q.contains("work")) -> todaysMove()

            // ── ROUTINE (existing) ───────────────────────────────────────────
            q.contains("routine") || q.contains("schedule") ->
                "Open SYSTEM ROUTINE from Status → Generate. I anchor today's quests + training around your fixed commitments; you accept, edit or reject. Confirmed routines sync to the vault."

            // ── HELP (existing) ──────────────────────────────────────────────
            q.contains("help") || q.contains("navigate") || q.contains("how to") || q.contains("what can you") ->
                "Roster: body readings (BMI/BMR/TDEE) · calorie+protein targets · workout orders · comeback resets · muscle-course sessions · equipment swaps · special-track suggestions · rank/XP decode · routine drafts. Ask in plain words — I answer only from verified context, and I'll say when I don't know."

            // ── DEFAULT ──────────────────────────────────────────────────────
            else -> buildString {
                open?.let { append("Order: clear ${it.title} — ${it.progress}/${it.targetValue} ${it.targetUnit.lowercase(Locale.US)} to go. ") }
                    ?: append(if (done > 0) "Day clear, hunter. Recover well." else "Status board is syncing — refresh and return.")
            }
        }

        val shaped = when (p) {
            Personality.QUIET -> body_.split("\n").take(4).joinToString("\n").split(". ").take(2).joinToString(". ") + "."
            Personality.COACH -> body_
            Personality.COMPANION -> "Good to see you, hunter. $body_"
            Personality.COMMAND -> body_.replace("Order:", "DO:")
        }
        return AIValidator.assistant(
            AssistantAnswer(
                text = shaped,
                followUps = listOfNotNull(
                    when {
                        q.contains("bmi") || q.contains("bmr") || q.contains("calori") -> "today's workout"
                        q.contains("workout") || q.contains("train") -> "my progress"
                        q.contains("missed") || q.contains("comeback") -> "quest status"
                        q.contains("special") -> "equipment swaps"
                        else -> "quest status".takeIf { s.quests.isNotEmpty() } ?: "today's workout"
                    },
                    "my progress",
                    if (body.complete) "calorie target" else null,
                ).distinct().take(3),
            ),
        )
    }

    private data class Quad(val a: Int, val b: String, val c: Int, val d: String)
}
