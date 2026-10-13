package com.thesystem.app.data.repo

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import com.thesystem.app.data.model.*
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.storage.storage
import kotlinx.serialization.builtins.serializer
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

    // ── Manual UPI payment flow (QR → UTR → screenshot → admin approval) ─────
    suspend fun uploadPaymentProof(bytes: ByteArray, extension: String = "jpg"): Result<String> = runCatching {
        val me = uid ?: error("Not signed in")
        val path = "$me/${UUID.randomUUID()}.$extension"
        supabase.storage.from("payment-proofs").upload(path, bytes)
        path
    }

    suspend fun submitManualPayment(itemType: String, itemRef: String?, amountInr: Double, utr: String, screenshotPath: String?): Result<Unit> = runCatching {
        supabase.from("manual_payments").insert(buildJsonObject {
            put("user_id", uid ?: error("Not signed in"))
            put("item_type", itemType)
            itemRef?.let { put("item_ref", it) }
            put("amount_inr", amountInr)
            put("upi_utr", utr.trim())
            screenshotPath?.let { put("screenshot_path", it) }
        })
        Unit
    }

    suspend fun myPayments(): List<PaymentDto> {
        val me = uid ?: return emptyList()
        return runCatching {
            supabase.from("manual_payments").select {
                filter { eq("user_id", me) }; order("created_at", Order.DESCENDING)
            }.decodeList<PaymentDto>()
        }.getOrDefault(emptyList())
    }

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

    // ── MONETIZATION 2.0 (mig 030) — wallet, QR orders, withdrawals ──────────

    /** Redacted public knobs: UPI destination, instructions, limits, rate. */
    suspend fun monetizationPublic(): JsonObject? = runCatching {
        val raw = supabase.postgrest.rpc("monetization_public").data ?: return@runCatching null
        Json.decodeFromString(JsonObject.serializer(), raw)
    }.getOrNull()

    suspend fun vcPackages(): List<VcPackageDto> = runCatching {
        supabase.from("vc_packages").select { order("sort", Order.ASCENDING) }.decodeList<VcPackageDto>()
    }.getOrDefault(emptyList())

    /** Server mints the order with ITS price — client never quotes VC prices. */
    suspend fun createVcOrder(packageId: String): Result<JsonObject> = runCatching {
        val raw = supabase.postgrest.rpc("create_vc_order", buildJsonObject {
            put("p_package", packageId)
        }).data ?: error("no response")
        Json.decodeFromString(JsonObject.serializer(), raw)
    }

    /** Proof in: UTR + screenshot path + OCR hint (untrusted extraction). */
    suspend fun submitVcOrderProof(orderId: String, utr: String, screenshotPath: String, ocr: JsonObject): Result<Unit> = runCatching {
        supabase.postgrest.rpc("submit_vc_order_proof", buildJsonObject {
            put("p_order", orderId); put("p_utr", utr); put("p_screenshot", screenshotPath); put("p_ocr", ocr)
        }); Unit
    }

    suspend fun myPaymentOrders(): List<PaymentOrderDto> = runCatching {
        supabase.from("payment_orders").select {
            order("created_at", Order.DESCENDING); limit(50)
        }.decodeList<PaymentOrderDto>()
    }.getOrDefault(emptyList())

    /** proofs live in the PRIVATE `payment-proofs` bucket under {uid}/{ref}.jpg */
    suspend fun uploadPaymentProof(orderRef: String, bytes: ByteArray): String? {
        val me = uid ?: return null
        val path = "$me/$orderRef.jpg"
        return runCatching {
            supabase.storage.from("payment-proofs").upload(path, bytes) { upsert = true }
            path
        }.getOrNull()
    }

    /** Server validates eligibility + splits the hold atomically. */
    suspend fun requestWithdrawal(vcAmount: Long, payeeName: String, upiId: String): Result<String> = runCatching {
        val raw = supabase.postgrest.rpc("request_withdrawal", buildJsonObject {
            put("p_vc", vcAmount); put("p_method", "UPI")
            put("p_payout", buildJsonObject { put("name", payeeName); put("upi", upiId) })
        }).data ?: error("no response")
        Json.decodeFromString(String.serializer(), raw)
    }

    suspend fun myWithdrawals(): List<WithdrawalDto> = runCatching {
        supabase.from("withdrawal_requests").select {
            order("created_at", Order.DESCENDING); limit(30)
        }.decodeList<WithdrawalDto>()
    }.getOrDefault(emptyList())

    /** CPA offerwall deep link with the hunter bound as subid. */
    fun offerwallUrl(base: String): String = "${base.trimEnd('/')}${if (base.contains("?")) "&" else "?"}subid=${uid ?: "guest"}"
}
