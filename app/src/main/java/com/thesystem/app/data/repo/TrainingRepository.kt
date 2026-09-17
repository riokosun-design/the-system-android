package com.thesystem.app.data.repo

import com.thesystem.app.data.model.*
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * v0.4.1 TRAINING DATA LAYER — every byte here comes from the live server
 * (migration 012). Courses, weekly schedules, course quests, difficulty, XP,
 * rest windows, verification type: admin-authored, never hardcoded client-side.
 */
@Singleton
class TrainingRepository @Inject constructor(private val supabase: SupabaseClient) {

    private val uid: String? get() = supabase.auth.currentSessionOrNull()?.user?.id

    // ── Catalog ──────────────────────────────────────────────────────────────
    /** `type` = MUSCLE | SPECIAL | FORBIDDEN. Order is admin-controlled (`sort`). */
    suspend fun courses(type: String? = null): List<CourseDto> = runCatching {
        supabase.from("courses")
            .select {
                filter {
                    eq("active", true)
                    if (type != null) eq("type", type)
                }
                order("sort", Order.ASCENDING)
            }
            .decodeList<CourseDto>()
    }.getOrDefault(emptyList())

    suspend fun course(slug: String): CourseDto? = runCatching {
        supabase.from("courses").select { filter { eq("slug", slug) } }.decodeSingle<CourseDto>()
    }.getOrNull()

    /** COURSE → WEEK → DAY → QUEST plan for one course. */
    suspend fun courseQuests(courseId: String): List<CourseQuestDto> = runCatching {
        supabase.from("course_quests")
            .select {
                filter { eq("course_id", courseId); eq("active", true) }
                order("week", Order.ASCENDING); order("day", Order.ASCENDING); order("sort", Order.ASCENDING)
            }
            .decodeList<CourseQuestDto>()
    }.getOrDefault(emptyList())

    // ── Enrollment ───────────────────────────────────────────────────────────
    suspend fun myCourses(): List<UserCourseDto> {
        val me = uid ?: return emptyList()
        return runCatching {
            supabase.from("user_courses").select { filter { eq("user_id", me) } }
                .decodeList<UserCourseDto>()
        }.getOrDefault(emptyList())
    }

    /** One MUSCLE track per lifetime; ≤5 ACTIVE SPECIAL tracks — server enforced. */
    suspend fun enroll(courseId: String): Result<Unit> = runCatching {
        supabase.postgrest.rpc("enroll_course", buildJsonObject { put("p_course_id", courseId) }); Unit
    }

    suspend fun updateProgress(courseId: String, percent: Double): Result<Unit> = runCatching {
        supabase.postgrest.rpc("update_course_progress", buildJsonObject {
            put("p_course_id", courseId); put("p_percent", percent)
        }); Unit
    }

    /** Weight verification gate — muscle tracks need a fresh (<31 days) weight. */
    suspend fun verifyWeight(kg: Double): Result<Unit> = runCatching {
        supabase.postgrest.rpc("verify_weight", buildJsonObject { put("p_weight_kg", kg) }); Unit
    }

    // ── Daily protocol templates (admin console reads them too) ──────────────
    suspend fun questTemplates(): List<QuestTemplateDto> = runCatching {
        supabase.from("daily_quest_templates").select { order("seq", Order.ASCENDING) }
            .decodeList<QuestTemplateDto>()
    }.getOrDefault(emptyList())

    // ── PERFORMANCE DEVELOPMENT AREA ─────────────────────────────────────────
    /** Specials selected on muscle recovery / rest days only; overload-guarded. */
    suspend fun mySpecials(): List<UserCourseDto> {
        val mine = myCourses()
        if (mine.isEmpty()) return emptyList()
        val specialIds = courses("SPECIAL").associateBy { it.id }
        return mine.filter { specialIds.containsKey(it.courseId) }
    }

    // ── BLACK ROOM ───────────────────────────────────────────────────────────
    suspend fun blackRoomEligibility(country: String = "IN"): BlackRoomEligibilityDto? = runCatching {
        val raw = supabase.postgrest.rpc("black_room_eligibility", buildJsonObject { put("p_country", country) }).data
            ?: return null
        Json.decodeFromString(BlackRoomEligibilityDto.serializer(), raw)
    }.getOrNull()

    /** APPLY → manual UPI rail (UTR + screenshot) → Super Admin review. */
    suspend fun applyBlackRoom(utr: String, screenshotPath: String?, country: String = "IN"): Result<String> = runCatching {
        val raw = supabase.postgrest.rpc("apply_black_room", buildJsonObject {
            put("p_utr", utr)
            if (screenshotPath != null) put("p_screenshot_path", screenshotPath)
            put("p_country", country)
        }).data ?: error("no response")
        Json.decodeFromString(String.serializer(), raw)
    }

    suspend fun blackRoomProgram(): List<BlackRoomProgramDto> = runCatching {
        val raw = supabase.postgrest.rpc("black_room_program").data ?: return emptyList()
        Json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(BlackRoomProgramDto.serializer()), raw)
    }.getOrDefault(emptyList())

    private companion object {
        val Json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
    }
}
