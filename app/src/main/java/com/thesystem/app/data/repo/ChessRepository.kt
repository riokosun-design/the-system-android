package com.thesystem.app.data.repo

import com.thesystem.app.data.model.ChessProfileDto
import com.thesystem.app.data.model.ChessSessionDto
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * MENTAL ASCENSION repository — every economy number is computed server-side
 * by mig-018 RPCs. The client only ships honest measurements (accuracy,
 * blunders, think time) and renders what the server returns.
 */
@Singleton
class ChessRepository @Inject constructor(
    private val client: SupabaseClient,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val uid get() = client.auth.currentSessionOrNull()?.user?.id

    suspend fun profile(): ChessProfileDto? = withContext(Dispatchers.IO) {
        val me = uid ?: return@withContext null
        runCatching {
            client.postgrest.from("chess_profile")
                .select { filter { eq("user_id", me) } }
                .decodeSingleOrNull<ChessProfileDto>()
        }.getOrNull()
    }

    data class LogResult(val rating: Int, val xpGained: Int, val ratingDelta: Int)

    /** Server clamps + computes everything; throws on hard failure. */
    suspend fun logSession(
        kind: String,          // GAME | PUZZLE | DAILY
        mode: String,
        result: String,        // WIN | LOSS | DRAW | SOLVED | FAILED
        accuracy: Double? = null,
        blunders: Int = 0,
        thinkMs: Int = 0,
        stats: JsonObject = buildJsonObject {},
        analysis: JsonObject = buildJsonObject {},
    ): Result<LogResult> = withContext(Dispatchers.IO) {
        runCatching {
            val raw = client.postgrest.rpc("log_chess_session", buildJsonObject {
                put("p_kind", kind)
                put("p_mode", mode)
                put("p_result", result)
                accuracy?.let { put("p_accuracy", it) }
                put("p_blunders", blunders)
                put("p_think_ms", thinkMs)
                put("p_stats", stats)
                put("p_analysis", analysis)
            }).data ?: error("no response")
            val obj = json.parseToJsonElement(raw) as JsonObject
            LogResult(
                rating = obj["rating"]!!.jsonPrimitive.intOrNull ?: 400,
                xpGained = obj["xp_gained"]!!.jsonPrimitive.intOrNull ?: 0,
                ratingDelta = obj["rating_delta"]!!.jsonPrimitive.intOrNull ?: 0,
            )
        }
    }

    /** Once/day/title. Returns mental XP actually granted (0 = already claimed). */
    suspend fun completeMentalQuest(title: String, xp: Int): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val raw = client.postgrest.rpc("complete_mental_quest", buildJsonObject {
                put("p_title", title)
                put("p_xp", xp)
            }).data ?: error("no response")
            raw.trim('"').toIntOrNull() ?: 0
        }
    }

    /** BODY × MIND SYNC (§8): +25 XP +5 VC, once/day, both quests proven. */
    suspend fun claimSynergy(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val raw = client.postgrest.rpc("claim_synergy").data ?: error("no response")
            raw.trim('"')
        }
    }

    suspend fun recentSessions(limit: Int = 10): List<ChessSessionDto> = withContext(Dispatchers.IO) {
        val me = uid ?: return@withContext emptyList()
        runCatching {
            client.postgrest.from("chess_sessions")
                .select {
                    filter { eq("user_id", me) }
                    order("created_at", Order.DESCENDING)
                    limit(limit.toLong())
                }
                .decodeList<ChessSessionDto>()
        }.getOrDefault(emptyList())
    }

    /** Today's sessions for mental-quest progress (kind + result matching). */
    suspend fun todayCount(kind: String, result: String? = null): Int = withContext(Dispatchers.IO) {
        val me = uid ?: return@withContext 0
        runCatching {
            client.postgrest.from("chess_sessions")
                .select {
                    filter {
                        eq("user_id", me)
                        eq("kind", kind)
                        result?.let { eq("result", it) }
                        gte("created_at", java.time.LocalDate.now().toString())
                    }
                }
                .decodeList<ChessSessionDto>().size
        }.getOrDefault(0)
    }

    suspend fun mentalClaimsToday(): Set<String> = withContext(Dispatchers.IO) {
        val me = uid ?: return@withContext emptySet()
        runCatching {
            client.postgrest.from("mental_quest_claims")
                .select {
                    filter {
                        eq("user_id", me)
                        eq("quest_date", java.time.LocalDate.now().toString())
                    }
                }
                .decodeList<MentalClaimDto>().map { it.title }.toSet()
        }.getOrDefault(emptySet())
    }

    @kotlinx.serialization.Serializable
    private data class MentalClaimDto(
        @kotlinx.serialization.SerialName("title") val title: String,
    )
}
