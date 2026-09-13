package com.thesystem.app.data.repo

import android.content.Context
import com.thesystem.app.data.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Core player-state repository: profile, dynamic theme assets, legal docs,
 * Leguna S.1 AI daily quests, training arcs, and Form Evolution.
 * ROUND 5: offline-first — profile & today's quests survive dead networks via SharedPreferences.
 */
@Singleton
class SystemRepository @Inject constructor(
    private val supabase: SupabaseClient,
    @ApplicationContext private val app: Context,
) {

    private val uid: String? get() = supabase.auth.currentSessionOrNull()?.user?.id

    // ── Offline cache: last-known-good profile + today's quest set ───────────
    private val prefs by lazy { app.getSharedPreferences("system_offline_cache", Context.MODE_PRIVATE) }
    private val cacheJson by lazy { Json { ignoreUnknownKeys = true } }

    private fun cacheProfile(p: UserDto) = runCatching {
        prefs.edit().putString("profile", cacheJson.encodeToString(UserDto.serializer(), p)).apply()
    }
    private fun cachedProfile(): UserDto? = runCatching {
        prefs.getString("profile", null)?.let { cacheJson.decodeFromString(UserDto.serializer(), it) }
    }.getOrNull()

    private fun cacheQuests(list: List<QuestDto>) = runCatching {
        prefs.edit().putString("quests", cacheJson.encodeToString(ListSerializer(QuestDto.serializer()), list)).apply()
    }
    private fun cachedQuestsToday(): List<QuestDto> = runCatching {
        val today = LocalDate.now().toString()
        prefs.getString("quests", null)
            ?.let { cacheJson.decodeFromString(ListSerializer(QuestDto.serializer()), it) }
            ?.filter { it.questDate == today }
            .orEmpty()
    }.getOrDefault(emptyList())

    // ── Profile ──────────────────────────────────────────────────────────────
    suspend fun profile(): UserDto? = uid?.let { id ->
        runCatching { supabase.from("users").select { filter { eq("id", id) } }.decodeSingle<UserDto>() }
            .onSuccess { cacheProfile(it) }
            .getOrElse { cachedProfile() } // dead network → last-known hunter state, UI stays alive
    }

    // ── Dynamic Theme Asset Engine (Section 1) ───────────────────────────────
    suspend fun enabledAssets(): List<AssetDto> = runCatching {
        supabase.from("dynamic_assets").select {
            filter { eq("enabled", true) }; order("sort_order", Order.ASCENDING)
        }.decodeList<AssetDto>()
    }.getOrDefault(emptyList())

    fun assetUrl(path: String): String = supabase.storage.from("system_assets").publicUrl(path)

    /** Picks the wallpaper for a screen slot, e.g. "DASHBOARD_HERO". Falls back to any character art. */
    suspend fun wallpaperFor(slot: String): Pair<String, Float>? = withContext(Dispatchers.IO) {
        val assets = enabledAssets()
        val pick = assets.firstOrNull { it.key == slot }
            ?: assets.filter { it.type == "CHARACTER_WALLPAPER" }.randomOrNull()
            ?: assets.firstOrNull { it.type == "AMBIENT_BACKGROUND" }
        pick?.let { assetUrl(it.storagePath) to it.fadeOpacity.toFloat().coerceIn(0.05f, 0.6f) }
    }

    // ── Dynamic Legal Documents (Section 2-B) ────────────────────────────────
    suspend fun legalDocument(docType: String): LegalDocDto? = runCatching {
        supabase.from("legal_documents").select { filter { eq("doc_type", docType) } }
            .decodeList<LegalDocDto>().maxByOrNull { it.version }
    }.getOrNull()

    // ── Daily Quests (Leguna S.1 AI) ─────────────────────────────────────────
    suspend fun dailyQuests(): List<QuestDto> {
        val id = uid ?: return cachedQuestsToday()
        runCatching { supabase.postgrest.rpc("ensure_daily_quests") } // server generates today's set once
        return runCatching {
            supabase.from("daily_quests").select { filter { eq("user_id", id) } }
                .decodeList<QuestDto>().sortedBy { it.id }
                .also { cacheQuests(it) }
        }.getOrElse { cachedQuestsToday() } // offline → today's cached set, quests still visible
    }

    /** Server marks the quest done, awards XP, and refreshes streak/last-activity atomically. */
    suspend fun completeQuest(questId: Long): Result<Unit> = runCatching {
        supabase.postgrest.rpc("complete_quest", buildJsonObject { put("p_quest_id", questId) }); Unit
    }

    /**
     * Log a physically-verified session (camera rep counts / sensor distance)
     * against a quest: writes an immutable workouts proof row and advances
     * progress atomically. Returns the post-log server progress state.
     */
    suspend fun logQuestProof(
        questId: Long,
        kind: String,        // QUEST_PUSH | QUEST_SQUAT | QUEST_RUN
        amount: Int,         // reps (push/squat) or meters (run)
        durationSec: Int,
    ): Result<QuestProofResult> = runCatching {
        val raw = supabase.postgrest.rpc("log_quest_proof", buildJsonObject {
            put("p_quest_id", questId); put("p_kind", kind)
            put("p_amount", amount); put("p_duration_sec", durationSec)
        }).data ?: error("no response")
        cacheJson.decodeFromString(QuestProofResult.serializer(), raw)
    }

    // ── Training Arcs (4-month anime courses, progression locked) ────────────
    suspend fun arcs(): List<ArcDto> = runCatching {
        supabase.from("training_arcs").select { order("sort", Order.ASCENDING) }.decodeList<ArcDto>()
    }.getOrDefault(emptyList())

    suspend fun myArcProgress(): List<ArcProgressDto> = uid?.let { id ->
        runCatching { supabase.from("user_arc_progress").select { filter { eq("user_id", id) } }.decodeList<ArcProgressDto>() }.getOrNull()
    } ?: emptyList()

    suspend fun startArc(arcId: String): Result<Unit> = runCatching {
        supabase.postgrest.rpc("start_arc", buildJsonObject { put("p_arc_id", arcId) }); Unit
    }

    // ── Form Evolution / Mystery Power ───────────────────────────────────────
    suspend fun myForms(): List<FormDto> {
        runCatching { supabase.postgrest.rpc("maybe_unlock_forms") } // unlock-by-level happens server-side
        val id = uid ?: return emptyList()
        return runCatching {
            supabase.from("user_forms").select { filter { eq("user_id", id) } }.decodeList<FormDto>()
                .sortedBy { it.formIndex }
        }.getOrDefault(emptyList())
    }

    // ── Award XP (self, honor-capped by the SQL function) ────────────────────
    suspend fun awardXp(amount: Int, zone: String? = null): Result<Unit> = runCatching {
        supabase.postgrest.rpc("award_xp", buildJsonObject {
            put("p_amount", amount); zone?.let { put("p_zone", it) }
        }); Unit
    }

    suspend fun logWorkout(workout: WorkoutDto): Result<Unit> = runCatching {
        supabase.from("workouts").insert(workout); Unit
    }

    /** Body stats editor — touches only whitelisted columns (role/xp/vc are trigger-guarded). */
    /** Play policy: self-service account deletion. Server cascades every row. */
    suspend fun deleteAccount() {
        supabase.postgrest.rpc("delete_account"); Unit
    }

    suspend fun updateBodyStats(userId: String, age: Int, heightCm: Double, weightKg: Double) {
        supabase.from("users").update({
            set("age", age); set("height_cm", heightCm); set("weight_kg", weightKg)
        }) { filter { eq("id", userId) } }
    }

    suspend fun updateDisplayName(userId: String, displayName: String) {
        supabase.from("users").update({ set("display_name", displayName) }) { filter { eq("id", userId) } }
    }

    // ── System config (UPI id, offerwall, etc. — admin editable) ────────────
    suspend fun config(key: String): String? = runCatching {
        supabase.from("system_config").select { filter { eq("key", key) } }
            .decodeSingle<SystemConfigDto>().value
    }.getOrNull()
}
