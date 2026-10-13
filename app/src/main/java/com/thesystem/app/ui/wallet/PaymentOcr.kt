package com.thesystem.app.ui.wallet

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

/**
 * PAYMENT PROOF OCR — on-device ML Kit text recognition (free, no cloud AI).
 * The extraction is a HINT ONLY (spec §8: vision AI is never proof of
 * settlement) — it pre-fills the submission form; the hunter confirms, and
 * the server still checks amount/UTR/uniqueness, then a finance admin decides.
 */
data class PaymentOcrResult(
    val utrCandidate: String? = null,
    val amountCandidate: Double? = null,
    val dateCandidate: String? = null,
    val timeCandidate: String? = null,
    val statusCandidate: String? = null,
    val raw: String = "",
)

object PaymentOcrParser {

    private val UTR_RE = Regex("""\b(?:UTR|UPI\s*Ref(?:erence)?(?:\s*No\.?)?|Ref(?:erence)?\s*(?:No\.?|ID)?|Transaction\s*ID|Txn\s*ID)[:\s]*([A-Za-z0-9]{8,22})\b""", RegexOption.IGNORE_CASE)
    private val LONG_REF_RE = Regex("""\b([0-9]{12,18})\b""")
    private val AMOUNT_RE = Regex("""(?:₹|Rs\.?|INR)\s*([0-9]{1,3}(?:,[0-9]{2,3})*(?:\.[0-9]{1,2})?|[0-9]+(?:\.[0-9]{1,2})?)""")
    private val DATE_RE = Regex("""\b(\d{1,2}[/-]\d{1,2}[/-]\d{2,4}|\d{1,2}\s+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\s+\d{2,4})\b""", RegexOption.IGNORE_CASE)
    private val TIME_RE = Regex("""\b(\d{1,2}:\d{2}(?::\d{2})?\s*(?:AM|PM|am|pm)?)\b""")
    private val STATUS_RE = Regex("""\b(success(?:ful)?|completed|paid|failed|pending)\b""", RegexOption.IGNORE_CASE)

    /** Deterministic "transaction & time matcher" — regex law, no hallucination. */
    fun parse(rawText: String): PaymentOcrResult {
        val utr = UTR_RE.find(rawText)?.groupValues?.get(1)
            ?: LONG_REF_RE.find(rawText)?.groupValues?.get(1)
        val amount = AMOUNT_RE.find(rawText)?.groupValues?.get(1)
            ?.replace(",", "")?.toDoubleOrNull()
        return PaymentOcrResult(
            utrCandidate = utr?.uppercase(),
            amountCandidate = amount,
            dateCandidate = DATE_RE.find(rawText)?.groupValues?.get(1),
            timeCandidate = TIME_RE.find(rawText)?.groupValues?.get(1),
            statusCandidate = STATUS_RE.find(rawText)?.groupValues?.get(1),
            raw = rawText.take(800),
        )
    }

    /** Serialized UNTRUSTED hint — keys: utr/amount/date/time/status + model + trusted=false. */
    fun toJson(r: PaymentOcrResult): kotlinx.serialization.json.JsonObject {
        val m = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        r.utrCandidate?.let { m["utr"] = kotlinx.serialization.json.JsonPrimitive(it) }
        r.amountCandidate?.let { m["amount"] = kotlinx.serialization.json.JsonPrimitive(it) }
        r.dateCandidate?.let { m["date"] = kotlinx.serialization.json.JsonPrimitive(it) }
        r.timeCandidate?.let { m["time"] = kotlinx.serialization.json.JsonPrimitive(it) }
        r.statusCandidate?.let { m["status"] = kotlinx.serialization.json.JsonPrimitive(it) }
        m["model"] = kotlinx.serialization.json.JsonPrimitive("mlkit-text-recognition-ondevice")
        m["trusted"] = kotlinx.serialization.json.JsonPrimitive(false)
        return kotlinx.serialization.json.JsonObject(m)
    }
}

suspend fun scanPaymentProof(context: Context, uri: Uri): PaymentOcrResult = runCatching {
    val image = InputImage.fromFilePath(context, uri)
    val result = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS).process(image).await()
    PaymentOcrParser.parse(result.text)
}.getOrDefault(PaymentOcrResult(raw = "scan_failed"))
