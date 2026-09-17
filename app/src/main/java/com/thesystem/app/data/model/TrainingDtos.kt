package com.thesystem.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ═══════════════════════════════════════════════════════════════════════════
// v0.4.1 — SEQUENTIAL QUEST PROTOCOL · TRAINING CATALOG · PERFORMANCE AREA ·
// BLACK ROOM · ARENA 3-SECTION (migration 012, live on the server).
// Every field maps 1:1 to a live column; nothing here is invented client-side.
// ═══════════════════════════════════════════════════════════════════════════

/** `courses` — one row per training program (MUSCLE | SPECIAL | FORBIDDEN). */
@Serializable
data class CourseDto(
    val id: String,
    val slug: String,
    val title: String,
    val type: String = "MUSCLE",              // MUSCLE | SPECIAL | FORBIDDEN
    val attribute: String? = null,
    val description: String? = null,
    @SerialName("schedule_json") val scheduleJson: JsonObject? = null,
    @SerialName("duration_months") val durationMonths: Int = 3,
    @SerialName("image_url") val imageUrl: String? = null,
    val active: Boolean = true,
    val sort: Int = 50,
    val difficulty: String = "MEDIUM",        // EASY | MEDIUM | HARD
    val target: String? = null,
    val equipment: String? = null,
) {
    /** Bundled cover art fallback — the server may override with its own URL. */
    val cover: String get() = imageUrl?.takeIf { it.isNotBlank() } ?: "file:///android_asset/courses/$slug.webp"

    val weeklyDays: List<String>
        get() = scheduleJson?.get("days")
            ?.let { d -> (d as? JsonObject)?.entries?.sortedBy { it.key.toIntOrNull() ?: 0 }?.map { it.value.toString().trim('"') } }
            .orEmpty()

    val sessionRange: Pair<Int, Int>
        get() {
            val arr = scheduleJson?.get("session_min")?.toString()?.trim('[', ']')
                ?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()
            return (arr.getOrNull(0) ?: 30) to (arr.getOrNull(1) ?: 60)
        }

    val restDayOnly: Boolean get() = scheduleJson?.get("rest_days_only")?.toString() == "true"
}

/** `user_courses` — the hunter's enrollment + progress for one course. */
@Serializable
data class UserCourseDto(
    val id: Long = 0,
    @SerialName("user_id") val userId: String = "",
    @SerialName("course_id") val courseId: String,
    val status: String = "ACTIVE",            // ACTIVE | COMPLETED | DROPPED
    @SerialName("progress_percent") val progressPercent: Double = 0.0,
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("completed_at") val completedAt: String? = null,
)

/** `course_quests` — COURSE → WEEK → DAY → QUEST, fully admin-authored. */
@Serializable
data class CourseQuestDto(
    val id: Long,
    @SerialName("course_id") val courseId: String,
    val week: Int = 0,
    val day: Int = 0,
    val sort: Int = 0,
    val title: String,
    val description: String = "",
    val exercise: String = "GENERAL",
    val sets: Int = 3,
    val reps: String = "10",
    @SerialName("duration_sec") val durationSec: Int = 0,
    @SerialName("rest_sec") val restSec: Int = 120,
    val xp: Int = 20,
    val difficulty: String = "MEDIUM",
    val verification: String = "CAMERA",      // CAMERA | STEPS | TIMER | MANUAL
    val equipment: String = "BODYWEIGHT",
    val alternatives: String = "",
    @SerialName("min_age") val minAge: Int? = null,
    @SerialName("max_age") val maxAge: Int? = null,
    @SerialName("min_level") val minLevel: Int = 1,
    val active: Boolean = true,
)

/** `daily_quest_templates` — admin-controlled daily protocol blocks. */
@Serializable
data class QuestTemplateDto(
    val id: Long = 0,
    val seq: Int,
    val title: String,
    @SerialName("exercise_kind") val exerciseKind: String,
    @SerialName("target_light") val targetLight: Int,
    @SerialName("target_steady") val targetSteady: Int,
    @SerialName("target_unit") val targetUnit: String = "REPS",
    @SerialName("est_duration_sec") val estDurationSec: Int = 300,
    @SerialName("rest_sec") val restSec: Int = 150,
    val xp: Int = 35,
    val difficulty: String = "EASY",
    val verification: String = "CAMERA",
    val active: Boolean = true,
)

/** `black_room_eligibility()` — the gate answers, straight from the server. */
@Serializable
data class BlackRoomEligibilityDto(
    val eligible: Boolean = false,
    val checks: JsonObject? = null,
    val upi: Double? = null,
    val usd: Double? = null,
    @SerialName("potential_index") val potentialIndex: Int = 0,
)

/** `black_room_programs` — the Super Admin's hand-built personalized track. */
@Serializable
data class BlackRoomProgramDto(
    val id: Long = 0,
    val week: Int = 0,
    val day: Int = 0,
    val title: String,
    val exercise: String = "GENERAL",
    val sets: Int = 3,
    val reps: String = "10",
    @SerialName("duration_sec") val durationSec: Int = 0,
    @SerialName("rest_sec") val restSec: Int = 150,
    val notes: String = "",
)

/** `black_room_applications_with_user` — admin review queue row. */
@Serializable
data class BlackRoomApplicationDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    val status: String = "PENDING",
    @SerialName("price_inr") val priceInr: Double = 0.0,
    @SerialName("price_usd") val priceUsd: Double = 0.0,
    val criteria: JsonObject? = null,
    val regiment: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    val username: String? = null,
)

/** `arena_live()` row — the LIVE section of the Arena (spectate + war room). */
@Serializable
data class ArenaLiveRow(
    @SerialName("battle_id") val battleId: String,
    @SerialName("exercise_type") val exerciseType: String = "PUSHUP",
    @SerialName("duration_sec") val durationSec: Int = 60,
    val status: String = "LIVE",
    @SerialName("started_at") val startedAt: String? = null,
    @SerialName("score_a") val scoreA: Int = 0,
    @SerialName("score_b") val scoreB: Int = 0,
    @SerialName("player_a_name") val playerAName: String? = null,
    @SerialName("player_b_name") val playerBName: String? = null,
    @SerialName("player_a_id") val playerAId: String,
    @SerialName("player_b_id") val playerBId: String,
    @SerialName("i_am_in") val iAmIn: Boolean = false,
)

/** `prediction_hub()` row — FREE, non-redeemable points only (13+ product). */
@Serializable
data class PredictionHubRow(
    @SerialName("battle_id") val battleId: String,
    @SerialName("exercise_type") val exerciseType: String = "PUSHUP",
    @SerialName("duration_sec") val durationSec: Int = 60,
    val status: String = "LOBBY",
    @SerialName("scheduled_at") val scheduledAt: String? = null,
    @SerialName("pool_id") val poolId: String? = null,
    @SerialName("pool_status") val poolStatus: String? = null,
    @SerialName("player_a_name") val playerAName: String? = null,
    @SerialName("player_b_name") val playerBName: String? = null,
    @SerialName("player_a_id") val playerAId: String,
    @SerialName("player_b_id") val playerBId: String,
    @SerialName("odds_a") val oddsA: Double? = null,
    @SerialName("odds_b") val oddsB: Double? = null,
    @SerialName("my_side") val mySide: String? = null,
    @SerialName("my_amount") val myAmount: Long? = null,
)

/** `prediction_bets` joined locally against the hub — my prediction slips. */
@Serializable
data class PredictionBetDto(
    val id: Long,
    @SerialName("pool_id") val poolId: String,
    @SerialName("user_id") val userId: String,
    val side: String,
    @SerialName("amount_vc") val amount: Long = 0,
    @SerialName("payout_vc") val payout: Long? = null,
    val status: String = "OPEN",
    @SerialName("odds_multiplier") val oddsMultiplier: Double? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

/** `scheduled_matches_with_names` — SCHEDULED section of the Arena. */
@Serializable
data class ScheduledMatchDto(
    val id: String,
    @SerialName("battle_id") val battleId: String,
    @SerialName("player1_id") val player1Id: String,
    @SerialName("player2_id") val player2Id: String,
    @SerialName("exercise_type") val exerciseType: String = "PUSHUP",
    @SerialName("scheduled_time") val scheduledTime: String,
    val status: String = "PENDING",            // PENDING | ACCEPTED | DECLINED | CANCELLED | DONE
    @SerialName("duration_seconds") val durationSeconds: Int = 60,
    @SerialName("player1_name") val player1Name: String? = null,
    @SerialName("player2_name") val player2Name: String? = null,
)

/** `daily_battle_challenges()` row — PUSH-UP + SQUAT, rank-adapted. */
@Serializable
data class BattleChallengeDto(
    val id: String,                            // PUSH | SQUAT
    val required: Int = 2,
    val wins: Int = 0,
    val claimed: Boolean = false,
    val exercise: String = "PUSH-UP BATTLES",
)
