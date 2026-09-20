package com.thesystem.app.ui.training.tcn

import android.os.Build
import com.thesystem.app.data.repo.SystemRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.roundToInt

/**
 * FEATURE HARVESTER — the Phase 2 data engine (§11). Every finalized engine
 * decision inside the envelope — commits AND rejects, because hard negatives
 * must be ≥30% of the corpus — can contribute one 48×12 feature window to the
 * training corpus. Rows are features only; PIXELS NEVER LEAVE THE DEVICE and
 * recording only happens with the hunter's explicit consent (profiles flag,
 * server-checked) plus the server's harvest_enabled knob (§13 remote config).
 */
class FeatureHarvester(
    private val repo: SystemRepository,
    private val scope: CoroutineScope,
) {

    data class Decision(
        val verdict: String,
        val kind: String,
        val engine: String,
        val window: FloatArray?,
        val meta: JsonObject,
    )

    @Volatile
    var enabled: Boolean = false

    private val queue = ConcurrentLinkedQueue<Decision>()
    private var flushing = false
    private var uploadedRows = 0

    val buffered: Int get() = queue.size
    val uploaded: Int get() = uploadedRows

    fun onDecision(verdict: String, kind: String, engine: String, window: FloatArray?, meta: JsonObject = EMPTY_META) {
        if (!enabled) return
        queue.add(Decision(verdict, kind, engine, window, meta))
        if (queue.size >= 5) flush()
    }

    /** Force a drain at session end (call from screen dispose). */
    fun flush() {
        if (flushing) return
        val batch = ArrayList<Decision>(10)
        while (batch.size < 10) batch.add(queue.poll() ?: break)
        if (batch.isEmpty()) return
        flushing = true
        scope.launch(Dispatchers.IO) {
            try {
                val rows = batch.map { d ->
                    buildJsonObject {
                        put("kind", d.kind)
                        put("engine_version", d.engine)
                        put("verdict", d.verdict)
                        put("features", buildJsonArray {
                            d.window?.forEach { v -> add((v * 10000).roundToInt() / 10000.0) }
                        })
                        put("meta", buildJsonObject {
                            put("model", Build.MODEL ?: "unknown")
                            put("sdk", Build.VERSION.SDK_INT)
                            d.meta.forEach { (k, v) -> put(k, v) }
                        })
                    }
                }
                val sent = repo.uploadFeatureSequences(rows)
                uploadedRows += sent
                if (sent == 0) queue.addAll(batch)   // retry later — the corpus is precious
            } finally {
                flushing = false
                if (queue.size >= 10) flush()
            }
        }
    }

    companion object {
        const val ENGINE_TAG = "v4.1-movenet"
        val EMPTY_META = buildJsonObject { }
    }
}
