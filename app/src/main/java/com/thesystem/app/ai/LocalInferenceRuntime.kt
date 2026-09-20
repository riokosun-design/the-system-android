package com.thesystem.app.ai

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LOCAL INFERENCE RUNTIME (spec §6) — the ONLY place the app knows which
 * inference library exists. Everything else talks to this interface.
 *
 * Single-model law (§4): one runtime instance, one loaded model, explicit
 * unload. No background service. No permanent residency.
 */

data class InferResult(
    val text: String,
    val latencyMs: Long,
    val approxOutTokens: Int,
)

interface LocalInferenceRuntime {
    val loaded: Boolean
    val backend: String

    /** Load a model bundle from local storage. False on ANY failure — never throws. */
    suspend fun load(modelPath: String, maxContextTokens: Int): Boolean

    /** One-shot completion. Null on timeout/failure — callers fall back. */
    suspend fun infer(prompt: String, maxOutputTokens: Int, timeoutMs: Long): InferResult?

    fun unload()
}

/** Air-gapped stub used when no runtime can be constructed. */
class NoopRuntime : LocalInferenceRuntime {
    override val loaded = false
    override val backend = "NONE"
    override suspend fun load(modelPath: String, maxContextTokens: Int) = false
    override suspend fun infer(prompt: String, maxOutputTokens: Int, timeoutMs: Long): InferResult? = null
    override fun unload() {}
}

/**
 * MediaPipe LLM Inference adapter — CPU/GPU delegate handled by the runtime
 * itself; we feed it a converted .task bundle from [LocalModelManager].
 * Every call is wrapped: a native crash path must degrade to fallback, never
 * take the app down (spec §3, §18).
 */
class MediaPipeRuntime(private val context: Context) : LocalInferenceRuntime {

    private var llm: LlmInference? = null

    override val loaded: Boolean get() = llm != null
    override val backend = "MEDIAPIPE_TASK"

    override suspend fun load(modelPath: String, maxContextTokens: Int): Boolean =
        withContext(Dispatchers.Default) {
            runCatching {
                unload()
                val opts = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(modelPath)
                    .setMaxTokens(maxContextTokens + 384) // headroom over prompt window
                    .build()
                llm = LlmInference.createFromOptions(context, opts)
                true
            }.getOrElse { unload(); false }
        }

    override suspend fun infer(prompt: String, maxOutputTokens: Int, timeoutMs: Long): InferResult? =
        withTimeoutOrNull(timeoutMs) {
            withContext(Dispatchers.Default) {
                val engine = llm ?: return@withContext null
                val t0 = android.os.SystemClock.elapsedRealtime()
                val out = runCatching { engine.generateResponse(prompt) }.getOrNull()
                    ?: return@withContext null
                val el = android.os.SystemClock.elapsedRealtime() - t0
                InferResult(out, el, (out.length / 4).coerceAtLeast(1))
            }
        }

    override fun unload() {
        runCatching { llm?.close() }
        llm = null
    }
}

/** Factory: returns a real runtime or the noop — construction itself is guarded. */
@Singleton
class RuntimeFactory @Inject constructor(
    @ApplicationContext private val app: Context,
) {
    fun create(): LocalInferenceRuntime =
        runCatching { MediaPipeRuntime(app) }.getOrElse { NoopRuntime() }
}
