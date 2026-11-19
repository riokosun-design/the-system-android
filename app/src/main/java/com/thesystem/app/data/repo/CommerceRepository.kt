package com.thesystem.app.data.repo

import com.thesystem.app.data.model.*
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v1.0-RELEASE §1/§2 — the VC coin economy is DEAD. Rewards are XP, rank and
 * prestige only. What remains here: the store shelf, workout-verification
 * media, and the Razorpay-ready purchase rails (Medal Trials, Dungeon
 * Protocols, Black Room). Every rail mints a PENDING_PAYMENT order
 * server-side; only the live gateway webhook settles it. No client money math.
 */
@Singleton
class CommerceRepository @Inject constructor(private val supabase: SupabaseClient) {

    private val uid: String? get() = supabase.auth.currentSessionOrNull()?.user?.id

    // ── Store (level-gated merch + affiliate supplements) ────────────────────
    suspend fun products(): List<ProductDto> = runCatching {
        supabase.from("ecommerce_products").select {
            filter { eq("active", true) }; order("created_at", Order.DESCENDING)
        }.decodeList<ProductDto>()
    }.getOrDefault(emptyList())

    // ── Workout verification media (auto-purged server-side after 10 minutes) ─
    suspend fun uploadWorkoutVerification(workoutId: Long, bytes: ByteArray): Result<Unit> = runCatching {
        val me = uid ?: error("Not signed in")
        val path = "$me/$workoutId-${UUID.randomUUID()}.jpg"
        supabase.storage.from("workout-verification").upload(path, bytes)
        supabase.from("workout_media").insert(buildJsonObject {
            put("workout_id", workoutId); put("user_id", me); put("storage_path", path)
        })
        Unit
    }

    // ── §2 MEDAL TRIALS ──────────────────────────────────────────────────────
    suspend fun medalTrials(): List<MedalTrialDto> = runCatching {
        supabase.from("medal_trials").select {
            filter { eq("active", true) }; order("price_inr", Order.ASCENDING)
        }.decodeList<MedalTrialDto>()
    }.getOrDefault(emptyList())

    suspend fun myMedalEnrollments(): List<MedalEnrollmentDto> {
        val me = uid ?: return emptyList()
        return runCatching {
            supabase.from("medal_trial_enrollments").select {
                filter { eq("user_id", me) }; order("created_at", Order.DESCENDING)
            }.decodeList<MedalEnrollmentDto>()
        }.getOrDefault(emptyList())
    }

    /** Mints (or idempotently returns) the PENDING_PAYMENT order for a trial. */
    suspend fun medalTrialEnroll(trialId: String): Result<PurchaseOrder> = runCatching {
        supabase.postgrest.rpc("medal_trial_enroll", buildJsonObject {
            put("p_trial", trialId)
        }).decodeAs<PurchaseOrder>()
    }

    /** 100% completion → shipping form → physical dog-tag claim (server-judged). */
    suspend fun medalTrialClaimCompletion(enrollmentId: String, shippingJson: String): Result<String> = runCatching {
        val raw = supabase.postgrest.rpc("medal_trial_claim_completion", buildJsonObject {
            put("p_enrollment", enrollmentId)
            put("p_shipping", Json.parseToJsonElement(shippingJson))
        }).data
        val j = Json.parseToJsonElement(raw).jsonObject
        if (j["ok"]?.jsonPrimitive?.content == "true") j["medal"]?.jsonPrimitive?.content ?: "MEDAL"
        else error(j["err"]?.jsonPrimitive?.content ?: "claim_failed")
    }

    // ── §2 DUNGEON PROTOCOLS (one-time DLC unlocks) ──────────────────────────
    suspend fun dungeonProtocols(): List<DungeonProtocolDto> = runCatching {
        supabase.from("dungeon_protocols").select {
            filter { eq("active", true) }; order("price_inr", Order.ASCENDING)
        }.decodeList<DungeonProtocolDto>()
    }.getOrDefault(emptyList())

    suspend fun myDungeonUnlocks(): List<DungeonUnlockDto> {
        val me = uid ?: return emptyList()
        return runCatching {
            supabase.from("dungeon_unlocks").select {
                filter { eq("user_id", me) }
            }.decodeList<DungeonUnlockDto>()
        }.getOrDefault(emptyList())
    }

    suspend fun dungeonUnlockRequest(protocolId: String): Result<PurchaseOrder> = runCatching {
        supabase.postgrest.rpc("dungeon_unlock_request", buildJsonObject {
            put("p_protocol", protocolId)
        }).decodeAs<PurchaseOrder>()
    }

    // ── §2 BLACK ROOM — 3-year arc, exactly 2 payments (UNLOCK → 6mo RENEWAL) ─
    suspend fun blackRoomOrderRequest(): Result<PurchaseOrder> = runCatching {
        supabase.postgrest.rpc("black_room_order_request", buildJsonObject {}).decodeAs<PurchaseOrder>()
    }

    suspend fun myBlackRoomOrders(): List<BlackRoomOrderDto> {
        val me = uid ?: return emptyList()
        return runCatching {
            supabase.from("black_room_orders").select {
                filter { eq("user_id", me) }; order("created_at", Order.DESCENDING)
            }.decodeList<BlackRoomOrderDto>()
        }.getOrDefault(emptyList())
    }
}
