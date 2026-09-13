package com.thesystem.app.data.repo

import com.thesystem.app.data.model.UserDto
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.Google
import io.github.jan.supabase.auth.providers.builtin.IDToken
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(private val supabase: SupabaseClient) {

    val sessionStatus: Flow<SessionStatus> = supabase.auth.sessionStatus

    val currentUserId: String? get() = supabase.auth.currentSessionOrNull()?.user?.id

    /** Exchange a Google ID token (from Android Credential Manager) for a Supabase session. */
    suspend fun signInWithGoogleIdToken(idToken: String, rawNonce: String) {
        supabase.auth.signInWith(IDToken) {
            this.idToken = idToken
            provider = Google
            nonce = rawNonce
        }
    }

    suspend fun signOut() = supabase.auth.signOut()

    suspend fun myProfile(): UserDto? = currentUserId?.let { uid ->
        runCatching {
            supabase.from("users").select { filter { eq("id", uid) } }.decodeSingle<UserDto>()
        }.getOrNull()
    }

    /** Claim a unique @handle. Server validates regex ^[a-z0-9_]{3,20}$ + uniqueness atomically. */
    suspend fun claimUsername(handle: String): Result<Unit> = runCatching {
        supabase.postgrest.rpc("claim_username", buildJsonObject { put("p_handle", handle) })
        Unit
    }

    suspend fun isUsernameAvailable(handle: String): Boolean = runCatching {
        supabase.from("users").select { filter { eq("username", handle.lowercase()) } }.decodeList<UserDto>().isEmpty()
    }.getOrDefault(false)

    /** Final step of onboarding: write metrics + mark gate complete. Role/xp columns are trigger-guarded. */
    suspend fun completeOnboarding(
        age: Int,
        heightCm: Double,
        weightKg: Double,
        goal: String,
        activityLevel: String = "STEADY",
    ): Result<Unit> = runCatching {
        val uid = currentUserId ?: error("Not signed in")
        supabase.from("users").update({
            set("age", age); set("height_cm", heightCm); set("weight_kg", weightKg)
            set("goal", goal); set("activity_level", activityLevel.uppercase())
            set("onboarding_completed", true)
        }) { filter { eq("id", uid) } }
        Unit
    }

    suspend fun applyReferralCode(code: String): Result<Unit> = runCatching {
        supabase.postgrest.rpc("apply_referral", buildJsonObject { put("p_code", code.trim().uppercase()) })
        Unit
    }
}
