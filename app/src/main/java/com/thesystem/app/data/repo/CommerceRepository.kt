package com.thesystem.app.data.repo

import com.thesystem.app.data.model.*
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

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

    // ── VC wallet ledger ─────────────────────────────────────────────────────
    suspend fun walletLedger(): List<VcTxnDto> {
        val me = uid ?: return emptyList()
        return runCatching {
            supabase.from("vc_transactions").select {
                filter { eq("user_id", me) }; order("created_at", Order.DESCENDING); limit(100)
            }.decodeList<VcTxnDto>()
        }.getOrDefault(emptyList())
    }

    // ── MONETIZATION (v0.24.0, spec §4): the manual rail is REMOVED — no QR,
    //    no reference numbers, no screenshot proofs, no manual withdrawals.
    //    Money movement returns only through PaymentGateways (Razorpay wiring).
    //    Server-side functions/tables remain for financial history; execute was
    //    revoked server-side in migration 033. ──────────────────────────────

    /** CPA offerwall deep link with the hunter bound as subid. */
    fun offerwallUrl(base: String): String = "${base.trimEnd('/')}${if (base.contains("?")) "&" else "?"}subid=${uid ?: "guest"}"
}
