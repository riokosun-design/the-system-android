package com.thesystem.app.data.repo

import com.thesystem.app.data.model.MedalBoardRowDto
import com.thesystem.app.data.model.UserDto
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.json.buildJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** §4 v1.0 — LIVE LEADERBOARDS. OVERALL reads public hunter profiles; MEDAL
 *  TRIALS reads the mig 044 security-definer board (no private columns cross). */
@Singleton
class LeaderboardRepository @Inject constructor(private val supabase: SupabaseClient) {

    enum class Geo { GLOBAL, INDIA, LOCAL }

    suspend fun overallBoard(geo: Geo, myCity: String?, myDistrict: String?): List<UserDto> = runCatching {
        supabase.from("users").select {
            filter {
                when (geo) {
                    Geo.GLOBAL -> Unit
                    Geo.INDIA -> eq("country", "IN")
                    Geo.LOCAL -> {
                        if (!myCity.isNullOrBlank()) eq("city", myCity.trim())
                        if (!myDistrict.isNullOrBlank()) eq("district", myDistrict.trim())
                    }
                }
            }
            order("level", Order.DESCENDING)
            order("xp", Order.DESCENDING)
            limit(50)
        }.decodeList<UserDto>()
    }.getOrDefault(emptyList())

    suspend fun medalBoard(): List<MedalBoardRowDto> = runCatching {
        supabase.postgrest.rpc("medal_trial_leaderboard", buildJsonObject {}).decodeList<MedalBoardRowDto>()
    }.getOrDefault(emptyList())
}
