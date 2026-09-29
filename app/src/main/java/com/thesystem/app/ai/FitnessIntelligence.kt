package com.thesystem.app.ai

import com.thesystem.app.core.FitnessMath
import com.thesystem.app.data.model.UserDto
import com.thesystem.app.data.model.VerifiedBestsDto
import java.util.Locale

/**
 * FITNESS INTELLIGENCE CORE (master prompt §3, §5–§8, §11) — the rules body
 * every brain (local LLM or deterministic) must respect. Pure functions over
 * VERIFIED data only: profile fields, camera-verified bests, streak/decay
 * counters, course catalogue facts. Nothing here invents a history.
 *
 * Pipeline law (§11): PROFILE → CURRENT STATE → RECENT HISTORY → GOAL →
 * RECOVERY → APP RULES → SAFE DIFFICULTY → QUEST/PLAN. Never random.
 */
object FitnessIntelligence {

    /** Training maturity from MEASURED data + self-declared experience. */
    enum class Band { BEGINNER, REGULAR, ADVANCED }

    /** Body block the AI reads (§2): deterministic estimates, never AI-math. */
    data class Body(
        val bmi: Double?,
        val bmiClass: String?,
        val bmr: Int?,
        val tdee: Int?,
        val calorieTarget: Int?,
        val proteinG: Int?,
        val waterMl: Int?,
        val weightStale: Boolean,
        val complete: Boolean,          // false = some fields UNKNOWN — say so, never invent
    )

    fun body(p: UserDto?, calorieFloor: Int = 1600): Body {
        if (p == null) return Body(null, null, null, null, null, null, null, false, false)
        val minor = (p.age ?: 99) < 18
        val bmi = FitnessMath.bmi(p.weightKg, p.heightCm)
        val bmr = FitnessMath.bmrEstimate(p.weightKg, p.heightCm, p.age)
        val tdee = FitnessMath.tdee(bmr, p.activityLevel)
        val scaleStale = run {
            val days = p.weightVerifiedAt?.take(10)?.let { d ->
                runCatching { java.time.LocalDate.parse(d) }.getOrNull()
            }?.let { java.time.temporal.ChronoUnit.DAYS.between(it, java.time.LocalDate.now()) }
            days == null || days > 30
        }
        return Body(
            bmi = bmi, bmiClass = bmi?.let { FitnessMath.bmiClass(it) },
            bmr = bmr, tdee = tdee,
            calorieTarget = FitnessMath.calorieTarget(tdee, p.goal, minor, calorieFloor),
            proteinG = FitnessMath.proteinGrams(p.weightKg, p.goal),
            waterMl = FitnessMath.waterMl(p.weightKg),
            weightStale = scaleStale,
            complete = bmr != null,
        )
    }

    /** §6: band from verified bests — claims follow measurement, not vibes. */
    fun band(b: VerifiedBestsDto?, exp: String?): Band {
        val push = b?.pushReps ?: 0
        val squat = b?.squatReps ?: 0
        val sessions = b?.sessions ?: 0
        val expHi = exp?.uppercase(Locale.US).orEmpty().let { it.contains("ADV") || it.contains("VET") }
        return when {
            push >= 30 && squat >= 45 && sessions >= 20 -> Band.ADVANCED
            push >= 12 || squat >= 20 || sessions >= 6 || expHi -> Band.REGULAR
            else -> Band.BEGINNER
        }
    }

    /** §6 comeback law — missed days NEVER mean punishment volume. */
    fun comebackFactor(missedDays: Int): Double = when {
        missedDays <= 0 -> 1.00
        missedDays == 1 -> 0.85
        missedDays == 2 -> 0.75
        else -> 0.65                    // 3+: gentle re-entry, rebuild the chain first
    }

    /** Next verified target (§6 progressive overload): +5–8%, never >1.5×. */
    fun nextTarget(kind: String, best: Int): Int? {
        if (best <= 0) return null
        val up = (best * 1.06).toInt().coerceAtLeast(best + 1)
        return up.coerceAtMost((best * 1.5).toInt())
    }

    /**
     * §5/§11 rich daily block — one quest can be a ROUND SET, but the adopted
     * quest (server RPC, mig 016) stays single-exercise; the round structure
     * rides in the proposal's reason text so the hunter sees the full honest
     * session instead of one naked rep-count. Targets sit at ~0.55× best
     * (consistency day, not test day), comeback-scaled, hard floor/ceiling.
     */
    data class RichQuest(val target: Int, val structure: String, val note: String)

    fun richQuest(kind: String, best: Int, comeback: Double): RichQuest {
        val base = (best * 0.55 * comeback)
        return when (kind) {
            "PUSH" -> {
                val t = base.toInt().coerceIn(5, 60)
                RichQuest(t, "3 rounds: ${(t / 3).coerceAtLeast(3)} push-up · 60–90s rest", "55% of verified best $best — volume day")
            }
            "SQUAT" -> {
                val t = base.toInt().coerceIn(5, 80)
                RichQuest(t, "3 rounds: ${(t / 3).coerceAtLeast(4)} squat · 60–90s rest", "55% of verified best $best — base strength")
            }
            "RUN" -> {
                val t = (base.toInt() / 50 * 50).coerceIn(400, 3000)
                RichQuest(t, "${t}m easy pace · walk breaks allowed", "70% of verified ${best}m — engine work, nose breathing")
            }
            "WALK" -> {
                val t = (2000.0 * comeback).toInt() / 50 * 50
                RichQuest(t.coerceIn(800, 2500), "${(t / 100) * 100}m relaxed walk", "active recovery — keep the chain alive")
            }
            else -> {
                val t = (75.0 * comeback).toInt().coerceIn(30, 120)
                RichQuest(t, "2 planks: ${t / 3}s · ${t - t / 3}s · 45s rest", "core armor — quality over stopwatch heroics")
            }
        }
    }

    /**
     * §7 MUSCLE COURSE SESSION — bodyweight-first ladder around push work.
     * Adds difficulty ONE rung at a time; household loads ONLY the obviously
     * safe kind (backpack with books — check the seams); never unsafe objects.
     */
    fun muscleSession(bnd: Band, comeback: Double): List<String> {
        fun ladder(items: List<Pair<String, String>>): List<String> = items.map { (ex, dose) -> "$ex — $dose" }
        val scaled = when (bnd) {
            Band.BEGINNER -> ladder(
                listOf(
                    "Wall push-up" to "3×10", "Incline push-up (table/desk)" to "3×8",
                    "Bodyweight squat" to "3×10", "Glute bridge" to "3×12",
                    "Plank" to "3×20s", "Rest between rounds" to "60–90s",
                ),
            )
            Band.REGULAR -> ladder(
                listOf(
                    "Incline push-up" to "3×10", "Standard push-up" to "3×8",
                    "Squat" to "4×12", "Reverse lunge" to "3×8/leg",
                    "Pike push-up" to "2×6", "Plank" to "3×30s", "Rest" to "60–90s",
                ),
            )
            Band.ADVANCED -> ladder(
                listOf(
                    "Standard push-up" to "4×14", "Decline push-up (legs on chair)" to "3×10",
                    "Diamond push-up" to "2×8", "Bulgarian split squat" to "3×10/leg",
                    "Pike push-up (feet high)" to "3×8", "Plank" to "3×45s", "Rest" to "90s",
                ),
            )
        }
        val factorNote = when {
            comeback < 0.7 -> " · comeback week — cut ONE set per movement"
            comeback < 0.9 -> " · re-entry — keep reps crisp, stop 2 shy of failure"
            else -> ""
        }
        return scaled + listOf(
            "Load option: backpack with 2–6kg books (✔ seams + straps first) — only for squats/lunges$factorNote",
        )
    }

    /** §7 honest equipment equivalents — no unsafe improvisation. */
    val EQUIPMENT_EQUIVALENTS = listOf(
        "dumbbells" to "water bottles (1L ≈ 1kg each) or a loaded backpack — rows, presses, squats",
        "pull-up bar" to "sturdy door-frame bar OR table rows (chest under a solid table)",
        "bench" to "floor presses + a firm chair for splits/decline",
        "resistance bands" to "wall/door anchors with any elastic band — check attachment points",
        "gym access" to "not required — every plan here runs bodyweight-first",
    )

    /**
     * §8 SPECIAL-TRACK suggestion — honestly sourced from MEASURED bests.
     * "Recent performance suggests…", never a diagnosis.
     */
    fun specialSuggestion(b: VerifiedBestsDto?): Pair<String, String>? {
        b ?: return null
        val push = b.pushReps; val squat = b.squatReps; val run = b.runMeters
        if (push == 0 && squat == 0 && run == 0) return null
        return when {
            run in 1..900 -> "Iron Lungs Stamina" to
                "your verified engine (${run}m) is the thinnest link — easy distance twice a week moves it fastest"
            squat in 1..12 -> "Ram Leg Power" to
                "legs are the least-verified line (${squat} squats on record) — squat-chain strength carries everything above it"
            push in 1..10 -> "Anvil Punch Power" to
                "upper-body push (${push}) trails your other lines — push chains + knuckle-safe striking fundamentals"
            else -> "Shadow Agility" to
                "all three measured lines are live — movement quality (change-of-direction, balance) is the next frontier"
        }
    }

    /** §9 phrasing law for assistant copy: suggestion, not diagnosis. */
    const val SUGGESTION_PHRASE = "Your recent performance suggests"
}
