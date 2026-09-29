package com.thesystem.app.ai

import java.util.Locale

/**
 * AI VALIDATOR (spec §17) — the wall between "a model said so" and
 * "the system acts". Every structured proposal passes here BEFORE the
 * existing rules engine ever sees it. Pure functions, zero I/O.
 */
object AIValidator {

    val QUEST_KINDS = setOf("PUSH", "SQUAT", "RUN", "WALK", "PLANK")

    // ── §4 SAFETY GATE ─ every inbound question crosses this BEFORE intents ──
    // Red-flag language ⇒ normal training advice halts; the answer points to
    // real-world help. The AI is never a doctor and never pushes through pain.
    private val SAFETY_RED_FLAGS = setOf(
        "chest pain", "heart", "can't breathe", "cant breathe", "not breathing",
        "faint", "fainting", "blackout", "black out", "dizzy", "dizziness",
        "sharp pain", "stabbing pain", "severe pain", "unbearable pain",
        "injured", "injury", "sprained", "sprain", "fracture", "broken bone",
        "torn", "dislocate", "blood", "bleeding", "vomit", "throwing up",
        "passed out", "pass out", "numb", "numbness",
    )
    private val SAFETY_HARD_STOP = setOf(
        "starve", "starvation", "stop eating", "not eating", "no food today",
        "rules: never", "skip water", "no water", "purge", "vomit on purpose",
        "painkiller to train", "train through the pain",
    )

    /** Non-null when the question trips the safety wall (handled by callers). */
    fun safetyBlockOf(question: String): String? {
        val q = question.lowercase()
        val red = SAFETY_RED_FLAGS.firstOrNull { q.contains(it) }
        val hard = SAFETY_HARD_STOP.firstOrNull { q.contains(it) }
        return when {
            hard != null -> "HARD_STOP"
            red != null -> "RED_FLAG: $red"
            else -> null
        }
    }

    /** §4 copy — strict, humble, human-first; never a diagnosis, never a doctor. */
    const val SAFETY_REPLY_RED_FLAG =
        "STOP. What you just described is beyond training advice — pause the session now, rest, and talk to a doctor or a qualified professional before any further exercise. I am not a doctor and I won't guess. The System will wait. Health first, protocol second."
    const val SAFETY_REPLY_HARD_STOP =
        "That request is dangerous and I will not help with it. No starvation, no dehydration games, no masking pain with pills, no training through injury. Sustainable discipline beats punishment every single week. If this is about weight, ask me for a sane calorie plan instead. ⚔️"

    val ROUTINE_CATS = setOf("TRAINING", "QUEST", "RECOVERY", "MEAL", "COMMIT", "SLEEP")
    private val TIME_RE = Regex("^([01]?\\d|2[0-3]):[0-5]\\d$")

    /** Mirrors migration 016 clamps — client-side gate, server re-enforces. */
    fun quest(proposal: QuestProposal, s: ContextEngine.Snapshot): String? {
        val kind = proposal.exercise.uppercase(Locale.US)
        if (kind !in QUEST_KINDS) return "invalid_exercise"
        val unit = if (kind == "RUN" || kind == "WALK") "METERS" else if (kind == "PLANK") "SECONDS" else "REPS"
        val ok = when (unit) {
            "METERS" -> proposal.target in 100..5000
            "SECONDS" -> proposal.target in 15..600
            else -> proposal.target in 5..300
        }
        if (!ok) return "target_out_of_range"
        if (proposal.durationMinutes !in 2..45) return "duration_out_of_range"
        // no duplicate active kind today — variation over repetition (§8)
        if (s.quests.any { it.exerciseKind == kind && !it.isDone && !it.isDead }) return "duplicate_kind_today"
        if (s.quests.any { it.source == "AI_ASSIST" }) return "ai_limit_today"
        // progression sanity vs lifetime verified bests (§8: no extreme jumps)
        s.bests?.let { b ->
            val bestFor = when (kind) {
                "PUSH" -> b.pushReps; "SQUAT" -> b.squatReps
                "RUN", "WALK" -> b.runMeters; else -> 0
            }
            if (bestFor > 0 && proposal.target > bestFor * 2) return "extreme_progression"
        }
        return null
    }

    /** Sanitizes a routine draft; returns the clean list (may be empty). */
    fun routine(items: List<RoutineItem>): List<RoutineItem> =
        items.filter { it.title.isNotBlank() && TIME_RE.matches(it.time) }
            .map {
                it.copy(
                    title = it.title.take(48),
                    category = it.category.uppercase(Locale.US).let { c -> if (c in ROUTINE_CATS) c else "COMMIT" },
                    durationMin = it.durationMin.coerceIn(5, 180),
                    status = if (it.status == "DONE" || it.status == "SKIPPED") it.status else "PENDING",
                    notes = it.notes.take(80),
                )
            }
            .sortedBy { it.time }
            .take(14)

    fun nutrition(plan: NutritionPlan, knownProductIds: Set<String>, calorieFloor: Int, minor: Boolean): String? {
        val floor = if (minor) maxOf(calorieFloor, 1800) else calorieFloor // §9: conservative for the young
        if (plan.calorieTarget !in floor..4200) return "calories_out_of_safe_range"
        if (plan.slots.size !in 3..6) return "slot_count_invalid"
        if (plan.slots.any { it.length !in 4..120 }) return "slot_text_invalid"
        if (plan.marketRefs.any { it !in knownProductIds }) return "unknown_products" // §9: never invent
        if (plan.budgetInr < 0.0 || plan.budgetInr > 5000.0) return "budget_invalid"
        return null
    }

    fun assistant(a: AssistantAnswer): AssistantAnswer =
        a.copy(
            text = a.text.trim().take(700).ifBlank { "…" },
            followUps = a.followUps.take(3).map { it.take(60) },
        )
}
