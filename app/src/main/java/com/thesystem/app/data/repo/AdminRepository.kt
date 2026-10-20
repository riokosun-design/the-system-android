package com.thesystem.app.data.repo

import com.thesystem.app.data.model.*
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Super Admin / Admin CMS. Every write route here is additionally locked by RLS + is_admin(). */
@Singleton
class AdminRepository @Inject constructor(private val supabase: SupabaseClient) {

    suspend fun overview(): JsonObject? = runCatching {
        val res = supabase.postgrest.rpc("admin_overview")
        res.data?.let { Json.parseToJsonElement(it) as? JsonObject }
    }.getOrNull()

    // ── Legal CMS (publish = instant, apps refetch on next cold start / pull) ─
    suspend fun publishLegal(docType: String, title: String, markdown: String): Result<Unit> = runCatching {
        val current: LegalDocDto? = runCatching {
            supabase.from("legal_documents").select { filter { eq("doc_type", docType) } }
                .decodeList<LegalDocDto>().maxByOrNull { it.version }
        }.getOrNull()
        if (current == null) {
            supabase.from("legal_documents").insert(buildJsonObject {
                put("doc_type", docType); put("title", title); put("content_markdown", markdown); put("version", 1)
                put("updated_by", supabase.auth.currentSessionOrNull()?.user?.id)
            })
        } else {
            supabase.from("legal_documents").update({
                set("title", title); set("content_markdown", markdown); set("version", current.version + 1)
                set("updated_by", supabase.auth.currentSessionOrNull()?.user?.id)
            }) { filter { eq("doc_type", docType) } }
        }
        Unit
    }

    // ── Product CMS ──────────────────────────────────────────────────────────
    suspend fun uploadProductImage(bytes: ByteArray, extension: String = "jpg"): Result<String> = runCatching {
        val path = "products/${UUID.randomUUID()}.$extension"
        supabase.storage.from("product-images").upload(path, bytes)
        supabase.storage.from("product-images").publicUrl(path)
    }

    suspend fun upsertProduct(p: ProductDto): Result<Unit> = runCatching {
        supabase.from("ecommerce_products").upsert(p); Unit
    }

    suspend fun deleteProduct(id: String): Result<Unit> = runCatching {
        supabase.from("ecommerce_products").delete { filter { eq("id", id) } }; Unit
    }

    suspend fun allProducts(): List<ProductDto> = runCatching {
        supabase.from("ecommerce_products").select { order("created_at", Order.DESCENDING) }.decodeList<ProductDto>()
    }.getOrDefault(emptyList())

    // ── Tournament management (dynamic entry fees, solo & clan) ─────────────
    suspend fun createTournament(title: String, type: String, entryFeeVc: Long, startsAt: String?, endsAt: String?): Result<Unit> = runCatching {
        supabase.from("tournaments").insert(buildJsonObject {
            put("title", title); put("type", type); put("entry_fee_vc", entryFeeVc)
            put("status", "OPEN"); put("created_by", supabase.auth.currentSessionOrNull()?.user?.id)
            startsAt?.let { put("starts_at", it) }; endsAt?.let { put("ends_at", it) }
        }); Unit
    }

    suspend fun setTournamentStatus(id: String, status: String): Result<Unit> = runCatching {
        supabase.from("tournaments").update({ set("status", status) }) { filter { eq("id", id) } }; Unit
    }

    suspend fun allTournaments(): List<TournamentDto> = runCatching {
        supabase.from("tournaments").select { order("created_at", Order.DESCENDING) }.decodeList<TournamentDto>()
    }.getOrDefault(emptyList())

    // ── Prediction pool settlement (solo + clan battles use the same engine) ─
    suspend fun settlePool(poolId: String, winningSide: String): Result<Unit> = runCatching {
        // SQL function is admin-guarded; Edge Function variant also exists for scheduled settlement.
        supabase.postgrest.rpc("settle_prediction_pool", buildJsonObject {
            put("p_pool_id", poolId); put("p_winner", winningSide)
        }); Unit
    }

    suspend fun settlePoolViaEdge(poolId: String, winningSide: String): Result<Unit> = runCatching {
        supabase.functions.invoke("settle-prediction-pool", buildJsonObject {
            put("poolId", poolId); put("winner", winningSide)
        }); Unit
    }

    // ── Bracket engine (migration 006): seed round 1, advance rounds, crown champion ──
    suspend fun generateBracket(tournamentId: String): Result<Int> = runCatching {
        supabase.postgrest.rpc("generate_bracket", buildJsonObject { put("p_tournament_id", tournamentId) })
            .decodeSingle<Int>()
    }

    suspend fun advanceBracket(tournamentId: String): Result<Int> = runCatching {
        supabase.postgrest.rpc("advance_bracket", buildJsonObject { put("p_tournament_id", tournamentId) })
            .decodeSingle<Int>()
    }

    suspend fun unsettledPools(): List<PoolDto> = runCatching {
        supabase.from("prediction_pools").select { filter { neq("status", "SETTLED") } }.decodeList<PoolDto>()
    }.getOrDefault(emptyList())

    // ── OPS COMMAND (mig 030) — metrics, roles, support ─────────────────────
    // §4 (v0.24.0): manual payment verification + withdrawal review + the
    // monetization switchboard are REMOVED from the client. Server keeps the
    // tables/functions for financial history; nothing here touches them.

    suspend fun opsMetrics(): JsonObject? = runCatching {
        val raw = supabase.postgrest.rpc("admin_ops_metrics").data ?: return@runCatching null
        Json.decodeFromString(JsonObject.serializer(), raw)
    }.getOrNull()

    suspend fun adminPerms(): List<AdminPermDto> = runCatching {
        supabase.from("admin_perms").select().decodeList<AdminPermDto>()
    }.getOrDefault(emptyList())

    suspend fun grantPerm(userId: String, perm: String): Result<Unit> = runCatching {
        supabase.postgrest.rpc("admin_grant_perm", buildJsonObject {
            put("p_user", userId); put("p_perm", perm) }); Unit
    }

    suspend fun revokePerm(userId: String, perm: String): Result<Unit> = runCatching {
        supabase.postgrest.rpc("admin_revoke_perm", buildJsonObject {
            put("p_user", userId); put("p_perm", perm) }); Unit
    }

    suspend fun auditRecent(): List<AdminAuditDto> = runCatching {
        supabase.from("admin_audit_log").select {
            order("created_at", Order.DESCENDING); limit(15)
        }.decodeList<AdminAuditDto>()
    }.getOrDefault(emptyList())

    suspend fun supportQueue(status: String = ""): List<SupportConversationDto> = runCatching {
        supabase.from("support_conversations").select(
            io.github.jan.supabase.postgrest.query.Columns.raw("*, hunter:users!user_id(username,display_name)"),
        ) {
            if (status.isNotBlank()) filter { eq("status", status) } else filter { neq("status", "RESOLVED") }
            order("updated_at", Order.DESCENDING); limit(60)
        }.decodeList<SupportConversationDto>()
    }.getOrDefault(emptyList())

    suspend fun supportReply(convId: String, body: String): Result<Unit> = runCatching {
        supabase.postgrest.rpc("support_admin_reply", buildJsonObject {
            put("p_conv", convId); put("p_body", body) }); Unit
    }

    suspend fun supportSetStatus(convId: String, status: String, reason: String = ""): Result<Unit> = runCatching {
        supabase.postgrest.rpc("support_admin_status", buildJsonObject {
            put("p_conv", convId); put("p_status", status); put("p_reason", reason) }); Unit
    }

    // ── Dynamic Theme Asset CMS ──────────────────────────────────────────────
    suspend fun allAssets(): List<AssetDto> = runCatching {
        supabase.from("dynamic_assets").select { order("sort_order", Order.ASCENDING) }.decodeList<AssetDto>()
    }.getOrDefault(emptyList())

    suspend fun uploadAsset(key: String, type: String, bytes: ByteArray, fadeOpacity: Double, extension: String = "jpg"): Result<Unit> = runCatching {
        val path = "$type/${key.lowercase()}-${UUID.randomUUID()}.$extension"
        supabase.storage.from("system_assets").upload(path, bytes) { upsert = true }
        supabase.from("dynamic_assets").upsert(buildJsonObject {
            put("key", key); put("type", type); put("storage_path", path); put("fade_opacity", fadeOpacity)
            put("enabled", true); put("updated_by", supabase.auth.currentSessionOrNull()?.user?.id)
        })
        Unit
    }

    suspend fun setAssetEnabled(id: String, enabled: Boolean): Result<Unit> = runCatching {
        supabase.from("dynamic_assets").update({ set("enabled", enabled) }) { filter { eq("id", id) } }; Unit
    }

    // ── User administration ──────────────────────────────────────────────────
    suspend fun adjustVc(userId: String, amount: Long, note: String): Result<Unit> = runCatching {
        supabase.postgrest.rpc("admin_adjust_vc", buildJsonObject {
            put("p_target", userId); put("p_amount", amount); put("p_note", note)
        }); Unit
    }

    suspend fun setSystemConfig(key: String, value: String): Result<Unit> = runCatching {
        supabase.from("system_config").upsert(buildJsonObject { put("key", key); put("value", value) })
        Unit
    }

    // ═════════════════════════════════════════════════════════════════════════
    // v0.4.1 CONTENT CMS — courses, course quests, daily quest templates,
    // Black Room review + per-user program building, scheduled-duel ops.
    // Everything ships without an app update. RLS re-checks is_admin().
    // ═════════════════════════════════════════════════════════════════════════

    suspend fun allCourses(): List<CourseDto> = runCatching {
        supabase.from("courses").select { order("sort", Order.ASCENDING) }.decodeList<CourseDto>()
    }.getOrDefault(emptyList())

    suspend fun upsertCourse(c: CourseDto, scheduleJson: String): Result<Unit> = runCatching {
        supabase.postgrest.rpc("admin_upsert_course", buildJsonObject {
            put("p_slug", c.slug); put("p_title", c.title); put("p_type", c.type)
            put("p_description", c.description ?: "")
            put("p_duration_months", c.durationMonths)
            put("p_schedule", Json.parseToJsonElement(scheduleJson))
            put("p_attribute", c.attribute)
            put("p_image_url", c.imageUrl)
            put("p_difficulty", c.difficulty)
            put("p_target", c.target ?: "FULL BODY")
            put("p_equipment", c.equipment ?: "BODYWEIGHT")
            put("p_active", c.active)
            put("p_sort", c.sort)
        }); Unit
    }

    suspend fun upsertCourseQuest(q: CourseQuestDto, courseSlug: String): Result<Unit> = runCatching {
        supabase.postgrest.rpc("admin_upsert_course_quest", buildJsonObject {
            put("p_course_slug", courseSlug); put("p_week", q.week); put("p_day", q.day); put("p_sort", q.sort)
            put("p_title", q.title); put("p_description", q.description); put("p_exercise", q.exercise)
            put("p_sets", q.sets); put("p_reps", q.reps); put("p_duration_sec", q.durationSec)
            put("p_rest_sec", q.restSec); put("p_xp", q.xp); put("p_difficulty", q.difficulty)
            put("p_verification", q.verification); put("p_equipment", q.equipment)
            put("p_alternatives", q.alternatives)
            put("p_min_age", q.minAge); put("p_max_age", q.maxAge)
            put("p_min_level", q.minLevel); put("p_active", q.active)
        }); Unit
    }

    suspend fun allCourseQuests(courseId: String): List<CourseQuestDto> = runCatching {
        supabase.from("course_quests").select { filter { eq("course_id", courseId) }; order("week", Order.ASCENDING) }
            .decodeList<CourseQuestDto>()
    }.getOrDefault(emptyList())

    suspend fun questTemplates(): List<QuestTemplateDto> = runCatching {
        supabase.from("daily_quest_templates").select { order("seq", Order.ASCENDING) }.decodeList<QuestTemplateDto>()
    }.getOrDefault(emptyList())

    suspend fun upsertQuestTemplate(t: QuestTemplateDto): Result<Unit> = runCatching {
        supabase.postgrest.rpc("admin_upsert_quest_template", buildJsonObject {
            put("p_seq", t.seq); put("p_title", t.title); put("p_kind", t.exerciseKind)
            put("p_light", t.targetLight); put("p_steady", t.targetSteady); put("p_unit", t.targetUnit)
            put("p_est_sec", t.estDurationSec); put("p_rest_sec", t.restSec); put("p_xp", t.xp)
            put("p_difficulty", t.difficulty); put("p_verification", t.verification); put("p_active", t.active)
        }); Unit
    }

    // §4 (v0.24.0): Black Room applications rode the manual payment rail — the
    // review/build client rail is removed with it. History stays server-side.

    suspend fun uploadCourseCover(slug: String, bytes: ByteArray, extension: String = "webp"): Result<String> = runCatching {
        val path = "courses/$slug.$extension"
        supabase.storage.from("system_assets").upload(path, bytes) { upsert = true }
        supabase.storage.from("system_assets").publicUrl(path)
    }
}
