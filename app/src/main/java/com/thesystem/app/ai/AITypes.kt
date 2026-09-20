package com.thesystem.app.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * AI ENGINE — shared contracts (spec §1, §2, §16).
 *
 * Adapter-based by law: anything the UI or business rules touch is defined
 * here as plain data; model/runtime details NEVER leak past the router.
 */

// ── Model metadata (§2): fully configurable, nothing hardcoded ───────────────
enum class ModelTier(val floorTotalRamMb: Int) {
    NONE(0), TINY(1600), SMALL(2600), MID(3200), PLUS(4400)
}

@Serializable
data class ModelMeta(
    val id: String,
    @SerialName("params_m") val paramsM: Int = 0,
    val quant: String = "INT8",
    @SerialName("size_mb") val sizeMb: Int = 0,
    @SerialName("ram_mb") val ramMb: Int = 0,
    val ctx: Int = 1024,
    val tier: String = "TINY",
    val backend: String = "MEDIAPIPE_TASK",
    val version: String = "0",
    val url: String? = null,
    val sha256: String? = null,
)

// ── Remote feature flags (§24) — parsed from engine_config.ai ────────────────
@Serializable
data class AiFlags(
    @SerialName("local_ai_enabled") val localAiEnabled: Boolean = false,
    @SerialName("quest_ai_enabled") val questAiEnabled: Boolean = true,
    @SerialName("nutrition_ai_enabled") val nutritionAiEnabled: Boolean = true,
    @SerialName("assistant_enabled") val assistantEnabled: Boolean = true,
    @SerialName("routine_ai_enabled") val routineAiEnabled: Boolean = true,
    @SerialName("max_context_tokens") val maxContextTokens: Int = 512,
    @SerialName("max_output_tokens") val maxOutputTokens: Int = 220,
    @SerialName("inference_timeout_ms") val inferenceTimeoutMs: Long = 12000,
    @SerialName("unload_after_ms") val unloadAfterMs: Long = 30000,
    @SerialName("min_free_storage_mb") val minFreeStorageMb: Int = 400,
    @SerialName("nutrition_daily_calorie_floor") val calorieFloor: Int = 1600,
    val models: List<ModelMeta> = emptyList(),
)

// ── Assistant personalities (§13) — tone ONLY; never rules ───────────────────
enum class Personality(val systemNote: String) {
    QUIET("Reply in at most two short lines. Zero decoration."),
    COACH("Training and progress oriented. Compact, motivating, factual."),
    COMPANION("Warm and conversational, still compact."),
    COMMAND("Direct imperative style. Verbs first. Ultra short."),
}

// ── Structured AI output schemas (§16) — validated before anything acts ──────
@Serializable
data class QuestProposal(
    val type: String = "daily_quest",
    val title: String = "",
    val exercise: String = "PUSH",            // PUSH|SQUAT|RUN|WALK|PLANK
    val target: Int = 10,
    @SerialName("target_unit") val targetUnit: String = "REPS",
    @SerialName("duration_minutes") val durationMinutes: Int = 10,
    val difficulty: String = "adaptive",
    val reason: String = "",
    val confidence: Double = 0.0,
)

@Serializable
data class RoutineItem(
    val title: String = "",
    val time: String = "09:00",               // HH:MM
    val category: String = "COMMIT",          // TRAINING|QUEST|RECOVERY|MEAL|COMMIT|SLEEP
    @SerialName("duration_min") val durationMin: Int = 30,
    val status: String = "PENDING",           // PENDING|DONE|SKIPPED
    val notes: String = "",
)

@Serializable
data class RoutineDraft(
    val type: String = "daily_routine",
    val items: List<RoutineItem> = emptyList(),
    val reason: String = "",
)

@Serializable
data class NutritionPlan(
    val type: String = "daily_nutrition",
    @SerialName("calorie_target") val calorieTarget: Int = 2200,
    val slots: List<String> = emptyList(),    // human meal lines, 3..6
    @SerialName("market_refs") val marketRefs: List<String> = emptyList(), // REAL product ids only
    @SerialName("budget_inr") val budgetInr: Double = 0.0,
    val reason: String = "",
)

@Serializable
data class AssistantAnswer(
    val type: String = "assistant_reply",
    val text: String = "",
    @SerialName("follow_ups") val followUps: List<String> = emptyList(),
)

val AiJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
}

/**
 * Extract the first balanced {...} region from raw model text (tolerates
 * markdown fences and chatter). Returns null when no object is present.
 */
fun extractJsonObject(raw: String): String? {
    val start = raw.indexOf('{')
    if (start < 0) return null
    var depth = 0
    var inStr = false
    var esc = false
    for (i in start until raw.length) {
        val c = raw[i]
        if (inStr) {
            if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false
            continue
        }
        when (c) {
            '"' -> inStr = true
            '{' -> depth++
            '}' -> { depth--; if (depth == 0) return raw.substring(start, i + 1) }
        }
    }
    return null
}
