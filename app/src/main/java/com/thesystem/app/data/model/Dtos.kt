package com.thesystem.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

// Every DTO maps 1:1 to a table/view in supabase/migrations. snake_case ↔ camelCase via @SerialName.

@Serializable
data class UserDto(
    val id: String,
    val username: String,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val role: String = "USER",
    val level: Int = 1,
    val xp: Long = 0,
    @SerialName("vc_balance") val vcBalance: Long = 0,
    @SerialName("penalty_state") val penaltyState: String = "CLEAR",
    @SerialName("missed_days") val missedDays: Int = 0,
    @SerialName("streak_days") val streakDays: Int = 0,
    @SerialName("last_activity_date") val lastActivityDate: String? = null,
    val goal: String? = null,
    val age: Int? = null,
    @SerialName("height_cm") val heightCm: Double? = null,
    @SerialName("weight_kg") val weightKg: Double? = null,
    @SerialName("referral_code") val referralCode: String = "",
    @SerialName("referred_by") val referredBy: String? = null,
    @SerialName("onboarding_completed") val onboardingCompleted: Boolean = false,
    @SerialName("black_room_until") val blackRoomUntil: String? = null,
    @SerialName("activity_level") val activityLevel: String? = null,
    @SerialName("athletic_experience") val athleticExperience: String? = null,
    @SerialName("weight_verified_at") val weightVerifiedAt: String? = null,
    /** FREE, non-redeemable spectator points — the only prediction currency. */
    @SerialName("prediction_points") val predictionPoints: Long = 0,
) {
    val isAdmin: Boolean get() = role == "SUPER_ADMIN" || role == "ADMIN"
    val isSuperAdmin: Boolean get() = role == "SUPER_ADMIN"
    /** Rank is level-driven but degradation states override. */
    val rank: SystemMathRank get() = SystemMathRank(level, missedDays)
}

/** Tiny bridge so the DTO layer doesn't import Compose. */
class SystemMathRank(level: Int, missedDays: Int) {
    val value = com.thesystem.app.core.SystemMath.rankFor(level, missedDays)
    val title get() = value.title
}

@Serializable
data class AssetDto(
    val id: String,
    val key: String,
    val type: String, // CHARACTER_WALLPAPER | AMBIENT_BACKGROUND | UI_OVERLAY | SPLASH_ART
    val title: String? = null,
    @SerialName("storage_path") val storagePath: String,
    @SerialName("fade_opacity") val fadeOpacity: Double = 0.25,
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
)

@Serializable
data class LegalDocDto(
    val id: String,
    @SerialName("doc_type") val docType: String, // PRIVACY_POLICY | TERMS_OF_SERVICE
    val title: String,
    @SerialName("content_markdown") val contentMarkdown: String,
    val version: Int = 1,
    @SerialName("updated_at") val updatedAt: String? = null,
)

@Serializable
data class ProductDto(
    val id: String,
    val name: String,
    val description: String? = null,
    @SerialName("image_url") val imageUrl: String? = null,
    @SerialName("price_inr") val priceInr: Double = 0.0,
    val category: String = "MERCH", // MERCH | SUPPLEMENT | DIGITAL
    @SerialName("outbound_url") val outboundUrl: String,
    @SerialName("affiliate_commission_pct") val affiliateCommissionPct: Double = 0.0,
    @SerialName("min_rank_required") val minRankRequired: String = "AVERAGE",
    val active: Boolean = true,
)

/** log_quest_proof RPC result — server progress after the verified session. */
@Serializable
data class QuestProofResult(
    val progress: Int = 0,
    val target: Int = 0,
    val complete: Boolean = false,
)

@Serializable
data class QuestDto(
    val id: Long,
    @SerialName("user_id") val userId: String,
    @SerialName("quest_date") val questDate: String,
    val title: String,
    @SerialName("target_value") val targetValue: Int,
    val progress: Int = 0,
    @SerialName("xp_reward") val xpReward: Int = 50,
    val completed: Boolean = false,
    val source: String = "DAILY_PROTOCOL",
    // ── sequential protocol (migration 012): 01 → 02 → 03 ───────────────────
    val seq: Int = 1,
    @SerialName("exercise_kind") val exerciseKind: String? = null,
    @SerialName("target_unit") val targetUnit: String = "REPS",
    @SerialName("est_duration_sec") val estDurationSec: Int = 300,
    @SerialName("rest_sec") val restSec: Int = 150,
    val difficulty: String = "EASY",
    val verification: String = "CAMERA",       // CAMERA | STEPS | TIMER | MANUAL
    val status: String = "READY",              // LOCKED READY ACTIVE VERIFYING COMPLETE MISSED PENALIZED
) {
    val isLocked: Boolean get() = status == "LOCKED"
    val isDone: Boolean get() = completed || status == "COMPLETE"
    val isDead: Boolean get() = status == "MISSED" || status == "PENALIZED"
    /** 01 / 02 / 03 — the protocol block number shown in the HUD. */
    val blockLabel: String get() = seq.toString().padStart(2, '0')
}

@Serializable
data class ArcDto(
    val id: String,
    val hero: String,
    val title: String,
    @SerialName("duration_months") val durationMonths: Int = 4,
    @SerialName("unlock_req_arc") val unlockReqArc: String? = null,
    @SerialName("min_level") val minLevel: Int = 1,
    val description: String? = null,
    val disclaimer: String = "",
    val sort: Int = 0,
)

@Serializable
data class ArcProgressDto(
    @SerialName("user_id") val userId: String,
    @SerialName("arc_id") val arcId: String,
    @SerialName("days_completed") val daysCompleted: Int = 0,
    @SerialName("penalty_extra_days") val penaltyExtraDays: Int = 0,
    val completed: Boolean = false,
)

@Serializable
data class FormDto(
    @SerialName("user_id") val userId: String,
    @SerialName("form_index") val formIndex: Int,
    val name: String,
    @SerialName("combat_style") val combatStyle: String = "BALANCED",
    @SerialName("base_power") val basePower: Double = 0.0,
    @SerialName("hard_work_multiplier") val hardWorkMultiplier: Double = 1.0,
    @SerialName("computed_power") val computedPower: Double = 0.0,
    @SerialName("unlocked_at") val unlockedAt: String? = null,
)

@Serializable
data class ClanDto(
    val id: String,
    val name: String,
    val tag: String,
    val description: String? = null,
    @SerialName("crest_path") val crestPath: String? = null,
    @SerialName("guild_master") val guildMaster: String,
    val level: Int = 1,
    @SerialName("treasury_vc") val treasuryVc: Long = 0,
    @SerialName("member_count") val memberCount: Int? = null, // joined view column
)

@Serializable
data class ClanMemberDto(
    @SerialName("clan_id") val clanId: String,
    @SerialName("user_id") val userId: String,
    val role: String = "MEMBER", // GUILD_MASTER | VICE_CAPTAIN | ELITE_HUNTER | MEMBER
    val username: String? = null, // joined
)

@Serializable
data class ZoneLeaderboardDto(
    val zone: String,
    @SerialName("user_id") val userId: String,
    val username: String,
    val captures: Long,
    @SerialName("clan_id") val clanId: String? = null,
    @SerialName("clan_tag") val clanTag: String? = null,
)

@Serializable
data class ClanTerritoryDto(
    val zone: String,
    @SerialName("clan_id") val clanId: String,
    @SerialName("shield_active") val shieldActive: Boolean = true,
    @SerialName("tax_bps") val taxBps: Int = 500,
    @SerialName("clan_tag") val clanTag: String? = null, // joined
)

@Serializable
data class TournamentDto(
    val id: String,
    val title: String,
    val type: String = "SOLO", // SOLO | CLAN
    val status: String = "DRAFT", // DRAFT | OPEN | LOCKED | IN_PROGRESS | COMPLETED | CANCELLED
    @SerialName("entry_fee_vc") val entryFeeVc: Long = 0,
    @SerialName("prize_pool_vc") val prizePoolVc: Long = 0,
    @SerialName("max_participants") val maxParticipants: Int = 64,
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null,
)

@Serializable
data class BattleDto(
    val id: String,
    @SerialName("tournament_id") val tournamentId: String? = null,
    @SerialName("player_a") val playerA: String,
    @SerialName("player_b") val playerB: String,
    @SerialName("player_a_name") val playerAName: String? = null,
    @SerialName("player_b_name") val playerBName: String? = null,
    @SerialName("score_a") val scoreA: Int = 0,
    @SerialName("score_b") val scoreB: Int = 0,
    val status: String = "LOBBY", // LOBBY | LIVE | FINISHED | CANCELLED
    @SerialName("duration_sec") val durationSec: Int = 60,
    @SerialName("exercise_type") val exerciseType: String = "PUSHUP",
    @SerialName("scheduled_at") val scheduledAt: String? = null,
    @SerialName("player_a_ready") val playerAReady: Boolean = false,
    @SerialName("player_b_ready") val playerBReady: Boolean = false,
    val host: String? = null,
    val winner: String? = null,
    /** §8 referee verdicts per player (server-written by battle_plausibility). */
    @SerialName("plausibility") val plausibility: JsonObject? = null,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("started_at") val startedAt: String? = null,
)

/** Hunter battle-stats sheet (RPC hunter_battle_stats) — pre-prediction inspection. */
@Serializable
data class HunterStatsDto(
    @SerialName("user_id") val userId: String,
    val level: Int = 1,
    val total: Int = 0,
    val wins: Int = 0,
    val losses: Int = 0,
    @SerialName("win_rate") val winRate: Int = 0,
    @SerialName("avg_score") val avgScore: Int = 0,
    @SerialName("pace_per_min") val pacePerMin: Int = 0,
    val recent: List<HunterStatsRecentDto> = emptyList(),
)

@Serializable
data class HunterStatsRecentDto(
    @SerialName("score_me") val scoreMe: Int = 0,
    @SerialName("score_foe") val scoreFoe: Int = 0,
    val won: Boolean = false,
    val at: String? = null,
)

/** `verified_bests()` — lifetime bests from immutable workout proofs + wars (mig 014). */
@Serializable
data class VerifiedBestsDto(
    @SerialName("push_reps") val pushReps: Int = 0,
    @SerialName("squat_reps") val squatReps: Int = 0,
    @SerialName("run_meters") val runMeters: Int = 0,
    val sessions: Int = 0,
    @SerialName("battle_wins") val battleWins: Int = 0,
)

@Serializable
data class PoolDto(
    val id: String,
    @SerialName("battle_id") val battleId: String,
    val status: String = "OPEN",
    @SerialName("platform_cut_bps") val platformCutBps: Int = 1500,
    @SerialName("total_pool_vc") val totalPoolVc: Long = 0,
    @SerialName("total_a_vc") val totalAVc: Long = 0,
    @SerialName("total_b_vc") val totalBVc: Long = 0,
    @SerialName("winning_side") val winningSide: String? = null,
)

@Serializable
data class BetDto(
    val id: Long,
    @SerialName("pool_id") val poolId: String,
    @SerialName("user_id") val userId: String,
    val side: String, // A | B
    @SerialName("amount_vc") val amountVc: Long,
    @SerialName("payout_vc") val payoutVc: Long? = null,
    val status: String = "OPEN", // OPEN | WON | LOST | REFUNDED
)

@Serializable
data class MessageDto(
    val id: Long = 0,
    @SerialName("sender_id") val senderId: String,
    @SerialName("sender_username") val senderUsername: String? = null, // joined in dm_inbox view / selected
    @SerialName("clan_id") val clanId: String? = null,
    @SerialName("recipient_id") val recipientId: String? = null,
    val kind: String, // CLAN | DM
    val body: String,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class PaymentDto(
    val id: String,
    @SerialName("user_id") val userId: String,
    @SerialName("item_type") val itemType: String, // BLACK_ROOM_PASS | MERCH | VC_TOPUP
    @SerialName("item_ref") val itemRef: String? = null,
    @SerialName("amount_inr") val amountInr: Double,
    @SerialName("upi_utr") val upiUtr: String,
    @SerialName("screenshot_path") val screenshotPath: String? = null,
    val status: String = "PENDING", // PENDING | APPROVED | REJECTED
    @SerialName("review_note") val reviewNote: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val username: String? = null, // joined for admin list
)

@Serializable
data class VcTxnDto(
    val id: Long,
    @SerialName("user_id") val userId: String,
    val amount: Long,
    val reason: String,
    val reference: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
)

@Serializable
data class ReferralHallDto(
    val username: String,
    @SerialName("referral_count") val referralCount: Long,
)

@Serializable
data class SystemConfigDto(
    val key: String,
    val value: String,
)

@Serializable
data class WorkoutDto(
    @SerialName("user_id") val userId: String,
    val kind: String,
    val reps: Int = 0,
    @SerialName("duration_sec") val durationSec: Int = 0,
    @SerialName("xp_earned") val xpEarned: Int = 0,
    val zone: String? = null,
)

// ── HUNTER FEED — X-style social layer (migration 008) ───────────────────────

/** Row of the `hunter_posts_feed` view: post + author plate + counts + quote preview. */
@Serializable
data class HunterPostDto(
    val id: String,
    @SerialName("author_id") val authorId: String,
    val username: String? = null,
    @SerialName("display_name") val displayName: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    val level: Int = 1,
    @SerialName("missed_days") val missedDays: Int = 0,
    @SerialName("author_clan_id") val authorClanId: String? = null,
    val content: String,
    @SerialName("media_url") val mediaUrl: String? = null,
    @SerialName("quest_verification_id") val questVerificationId: Long? = null,
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("quoted_post_id") val quotedPostId: String? = null,
    @SerialName("quoted_username") val quotedUsername: String? = null,
    @SerialName("quoted_excerpt") val quotedExcerpt: String? = null,
    @SerialName("mana_count") val manaCount: Long = 0,
    @SerialName("transmit_count") val transmitCount: Long = 0,
    @SerialName("reply_count") val replyCount: Long = 0,
    @SerialName("created_at") val createdAt: String? = null,
)

/** Insert payload for a new dispatch / reply / quote. Server defaults fill the rest. */
@Serializable
data class NewHunterPost(
    @SerialName("author_id") val authorId: String,
    val content: String,
    @SerialName("media_url") val mediaUrl: String? = null,
    @SerialName("parent_id") val parentId: String? = null,
    @SerialName("quoted_post_id") val quotedPostId: String? = null,
)

@Serializable
data class HunterInteractionRow(
    @SerialName("post_id") val postId: String,
    @SerialName("user_id") val userId: String,
    val type: String, // mana_boost | transmit | challenge
)

// ── AI ENGINE (migration 016): assistant notes · daily routine ───────────────

/** Explicitly-saved assistant memory. Chat logs are NEVER stored — only these. */
@Serializable
data class AiNoteDto(
    val id: Long = 0,
    @SerialName("user_id") val userId: String = "",
    val note: String,
    @SerialName("created_at") val createdAt: String? = null,
)

/** One daily routine per hunter per day; items are RoutineItem JSON (ai pkg). */
@Serializable
data class RoutineDto(
    @SerialName("user_id") val userId: String = "",
    @SerialName("routine_date") val routineDate: String = "",
    val items: JsonArray = JsonArray(emptyList()),
    val status: String = "DRAFT",            // DRAFT | CONFIRMED
    val source: String = "AI",               // AI | MANUAL
    @SerialName("updated_at") val updatedAt: String? = null,
)
