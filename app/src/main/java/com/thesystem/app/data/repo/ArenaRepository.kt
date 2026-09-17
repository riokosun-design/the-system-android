package com.thesystem.app.data.repo

import com.thesystem.app.data.model.*
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.realtime
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** Arena: tournaments (VC entry fees), realtime push-up battles, prediction pools, leaderboards. */
@Singleton
class ArenaRepository @Inject constructor(private val supabase: SupabaseClient) {

    private val statsJson = Json { ignoreUnknownKeys = true; isLenient = true }

    private val uid: String? get() = supabase.auth.currentSessionOrNull()?.user?.id

    // ── Tournaments ──────────────────────────────────────────────────────────
    suspend fun tournaments(): List<TournamentDto> = runCatching {
        supabase.from("tournaments").select {
            filter { neq("status", "DRAFT") }; order("created_at", Order.DESCENDING)
        }.decodeList<TournamentDto>()
    }.getOrDefault(emptyList())

    /** Pays the admin-set VC entry fee from the wallet and registers (atomic, server-side). */
    suspend fun joinTournament(tournamentId: String, clanId: String? = null): Result<Unit> = runCatching {
        supabase.postgrest.rpc("join_tournament", buildJsonObject {
            put("p_tournament_id", tournamentId); clanId?.let { put("p_clan_id", it) }
        }); Unit
    }

    suspend fun myParticipations(): List<Pair<String, Long>> {
        val me = uid ?: return emptyList()
        return runCatching {
            supabase.from("tournament_participants").select { filter { eq("user_id", me) } }
                .decodeList<Map<String, kotlinx.serialization.json.JsonElement>>()
                .map { it["tournament_id"].toString().trim('"') to (it["entry_fee_paid"]?.toString()?.toLongOrNull() ?: 0L) }
        }.getOrDefault(emptyList())
    }

    // ── Battles (realtime push-up wars) ──────────────────────────────────────
    suspend fun openBattles(): List<BattleDto> = runCatching {
        supabase.from("battles_with_names").select {
            filter { neq("status", "CANCELLED") }; order("created_at", Order.DESCENDING); limit(50)
        }.decodeList<BattleDto>()
    }.getOrDefault(emptyList())

    suspend fun battle(id: String): BattleDto? = runCatching {
        supabase.from("battles_with_names").select { filter { eq("id", id) } }.decodeList<BattleDto>().firstOrNull()
    }.getOrNull()

    suspend fun challenge(opponentId: String, durationSec: Int = 60): Result<String> = runCatching {
        val res = supabase.postgrest.rpc("create_battle", buildJsonObject {
            put("p_opponent", opponentId); put("p_duration_sec", durationSec)
        })
        res.data?.trim('"') ?: error("create_battle returned no id")
    }

    /** Arm/disarm READY in the lobby; the server flips the battle LIVE once BOTH are ready. */
    suspend fun setReady(battleId: String, ready: Boolean): Result<Unit> = runCatching {
        supabase.postgrest.rpc("set_battle_ready", buildJsonObject {
            put("p_battle_id", battleId); put("p_ready", ready)
        }); Unit
    }

    /** Abort a dead lobby; the server refunds any early prediction stakes. */
    suspend fun cancelBattle(battleId: String): Result<Unit> = runCatching {
        supabase.postgrest.rpc("cancel_battle", buildJsonObject { put("p_battle_id", battleId) }); Unit
    }

    /** Pre-prediction inspection sheet: win rate, level, pace, last 5 wars. */
    suspend fun hunterStats(userId: String): HunterStatsDto? = runCatching {
        val data = supabase.postgrest.rpc(
            "hunter_battle_stats", buildJsonObject { put("p_user", userId) }
        ).data ?: return@runCatching null
        statsJson.decodeFromString(HunterStatsDto.serializer(), data)
    }.getOrNull()

    /** Called by player A when the 60s timer ends. Server awards +150 / +20 XP and locks scores. */
    suspend fun finishBattle(battleId: String, scoreA: Int, scoreB: Int): Result<Unit> = runCatching {
        supabase.postgrest.rpc("finish_battle", buildJsonObject {
            put("p_battle_id", battleId); put("p_score_a", scoreA); put("p_score_b", scoreB)
        }); Unit
    }

    /** Live battle row (score updates + status transitions) as a flow. */
    fun battleFlow(battleId: String): Flow<BattleDto?> = callbackFlow {
        val channel = supabase.channel("battle-row-$battleId")
        val job = launch {
            channel.postgresChangeFlow<PostgresAction>(schema = "public") {
                table = "battles"
            }.collect { trySend(battle(battleId)) }
        }
        channel.subscribe()
        trySend(battle(battleId))
        awaitClose { job.cancel(); launch { supabase.realtime.runCatching { channel.unsubscribe() } } }
    }

    /**
     * Tug-of-war channel: Broadcast scores at ~2Hz (no DB write per rep — keeps 60fps
     * even on low-end devices; scores are only persisted at finish).
     */
    fun battleChannel(battleId: String) = supabase.channel("battle-live-$battleId")

    suspend fun sendScore(channel: io.github.jan.supabase.realtime.RealtimeChannel, userId: String, count: Int) {
        channel.broadcast(event = "score", buildJsonObject { put("u", userId); put("c", count) })
    }

    /** One-tap lobby taunts/signal — ephemeral broadcast, nothing persisted. */
    suspend fun sendTaunt(channel: io.github.jan.supabase.realtime.RealtimeChannel, userId: String, message: String) {
        channel.broadcast(event = "taunt", buildJsonObject { put("u", userId); put("m", message.take(120)) })
    }

    fun tauntFlow(channel: io.github.jan.supabase.realtime.RealtimeChannel, myId: String): Flow<Pair<String, String>> = callbackFlow {
        val job = launch {
            channel.broadcastFlow<JsonObject>(event = "taunt").collect { payload ->
                val u = payload["u"]?.jsonPrimitive?.content ?: return@collect
                val m = payload["m"]?.jsonPrimitive?.content ?: return@collect
                if (u != myId) trySend(u to m)
            }
        }
        channel.subscribe()
        awaitClose { job.cancel(); launch { supabase.realtime.runCatching { channel.unsubscribe() } } }
    }

    fun opponentScoreFlow(channel: io.github.jan.supabase.realtime.RealtimeChannel, myId: String): Flow<Int> = callbackFlow {
        val job = launch {
            channel.broadcastFlow<JsonObject>(event = "score").collect { payload ->
                val u = payload["u"]?.jsonPrimitive?.content
                if (u != null && u != myId) trySend(payload["c"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0)
            }
        }
        channel.subscribe()
        awaitClose { job.cancel(); launch { supabase.realtime.runCatching { channel.unsubscribe() } } }
    }

    // ── Prediction Engine ────────────────────────────────────────────────────
    suspend fun openPools(): List<Pair<PoolDto, BattleDto?>> {
        val pools = runCatching {
            supabase.from("prediction_pools").select {
                filter { eq("status", "OPEN") }; order("created_at", Order.DESCENDING)
            }.decodeList<PoolDto>()
        }.getOrDefault(emptyList())
        return pools.map { it to battle(it.battleId) }
    }

    suspend fun poolFor(battleId: String): PoolDto? = runCatching {
        supabase.from("prediction_pools").select { filter { eq("battle_id", battleId) } }
            .decodeList<PoolDto>().firstOrNull()
    }.getOrNull()

    /** Server verifies balance, debits VC, inserts the bet and updates pool totals atomically. */
    suspend fun placeBet(poolId: String, side: String, amountVc: Long): Result<Unit> = runCatching {
        supabase.postgrest.rpc("place_bet", buildJsonObject {
            put("p_pool_id", poolId); put("p_side", side); put("p_amount", amountVc)
        }); Unit
    }

    suspend fun myBets(): List<BetDto> {
        val me = uid ?: return emptyList()
        return runCatching {
            supabase.from("prediction_bets").select {
                filter { eq("user_id", me) }; order("created_at", Order.DESCENDING); limit(50)
            }.decodeList<BetDto>()
        }.getOrDefault(emptyList())
    }

    // ── Leaderboards ─────────────────────────────────────────────────────────
    suspend fun xpLeaderboard(): List<UserDto> = runCatching {
        supabase.from("users").select { order("xp", Order.DESCENDING); limit(50) }.decodeList<UserDto>()
    }.getOrDefault(emptyList())

    // ═════════════════════════════════════════════════════════════════════════
    // v0.4.1 — ARENA 3 SECTIONS (migration 012, live)
    //   1 MATCHMAKING · 2 PREDICTION/SPECTATOR · 3 CHALLENGES
    // Predictions run on FREE, non-redeemable points. No cash, no withdrawal.
    // ═════════════════════════════════════════════════════════════════════════

    /** SECTION 2 — live boards (LIVE + last 24h). Spectate via the War Room engine. */
    suspend fun arenaLive(): List<ArenaLiveRow> = runCatching {
        val raw = supabase.postgrest.rpc("arena_live").data ?: return emptyList()
        statsJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ArenaLiveRow.serializer()), raw)
    }.getOrDefault(emptyList())

    /** SECTION 2 — open boards, other hunters only, odds from the server. */
    suspend fun predictionHub(): List<PredictionHubRow> = runCatching {
        val raw = supabase.postgrest.rpc("prediction_hub").data ?: return emptyList()
        statsJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(PredictionHubRow.serializer()), raw)
    }.getOrDefault(emptyList())

    suspend fun myPredictions(): List<PredictionBetDto> {
        val me = uid ?: return emptyList()
        return runCatching {
            supabase.from("prediction_bets").select {
                filter { eq("user_id", me) }; order("created_at", Order.DESCENDING); limit(40)
            }.decodeList<PredictionBetDto>()
        }.getOrDefault(emptyList())
    }

    /** FREE points only — server debits prediction_points, never VC or cash. */
    suspend fun placePrediction(poolId: String, side: String, points: Long): Result<Unit> = runCatching {
        supabase.postgrest.rpc("place_bet", buildJsonObject {
            put("p_pool_id", poolId); put("p_side", side); put("p_amount", points)
        }); Unit
    }

    /** Daily free allowance (server caps it at one claim per day). */
    suspend fun claimPredictionAllowance(): Result<Int> = runCatching {
        val raw = supabase.postgrest.rpc("claim_prediction_allowance").data ?: error("no response")
        Json.decodeFromString(Int.serializer(), raw)
    }

    /** SECTION 1/3 — live challenge with the chosen exercise (PUSHUP | SQUAT). */
    suspend fun challengeLive(opponentId: String, exercise: String, durationSec: Int): Result<String> = runCatching {
        val raw = supabase.postgrest.rpc("create_battle", buildJsonObject {
            put("p_opponent", opponentId); put("p_duration_sec", durationSec); put("p_exercise", exercise)
        }).data ?: error("no response")
        Json.decodeFromString(String.serializer(), raw)
    }

    /** SECTION 3 — SCHEDULED war: date + time, opponent accepts, reminder fires. */
    suspend fun scheduleDuel(opponentId: String, exercise: String, durationSec: Int, atIso: String): Result<String> = runCatching {
        val raw = supabase.postgrest.rpc("schedule_duel", buildJsonObject {
            put("p_opponent", opponentId); put("p_exercise", exercise)
            put("p_duration", durationSec); put("p_at", atIso)
        }).data ?: error("no response")
        Json.decodeFromString(String.serializer(), raw)
    }

    suspend fun scheduledMatches(): List<ScheduledMatchDto> = runCatching {
        supabase.from("scheduled_matches_with_names").select { order("scheduled_time", Order.ASCENDING); limit(60) }
            .decodeList<ScheduledMatchDto>()
    }.getOrDefault(emptyList())

    suspend fun respondDuel(matchId: String, accept: Boolean): Result<Unit> = runCatching {
        supabase.postgrest.rpc("respond_duel", buildJsonObject {
            put("p_match", matchId); put("p_accept", accept)
        }); Unit
    }

    suspend fun cancelScheduled(matchId: String): Result<Unit> = runCatching {
        supabase.postgrest.rpc("cancel_scheduled", buildJsonObject { put("p_match", matchId) }); Unit
    }

    /** SECTION 3 — today's PUSH-UP + SQUAT challenge blocks, rank-adapted. */
    suspend fun dailyChallenges(): List<BattleChallengeDto> = runCatching {
        val raw = supabase.postgrest.rpc("daily_battle_challenges").data ?: return emptyList()
        statsJson.decodeFromString(kotlinx.serialization.builtins.ListSerializer(BattleChallengeDto.serializer()), raw)
    }.getOrDefault(emptyList())

    suspend fun claimBattleChallenge(kind: String): Result<Unit> = runCatching {
        supabase.postgrest.rpc("claim_battle_challenge", buildJsonObject { put("p_kind", kind) }); Unit
    }
}
