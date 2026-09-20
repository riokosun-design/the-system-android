package com.thesystem.app.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI ORCHESTRATOR (spec §1) — the single doorway.
 *
 *   request → flags → context snapshot → [local LLM if tier+artifact allow]
 *             → parse → VALIDATE → deterministic fallback if anything failed
 *             → structured result + WHO answered (brain tag for observability)
 *
 * Rules that can NEVER break (§27): this class writes nothing authoritative.
 * Quest adoption goes through the server RPC; XP/Rank/verification stay with
 * the existing engines; the camera stack is untouched.
 */
@Singleton
class AIOrchestrator @Inject constructor(
    private val config: AIRemoteConfig,
    private val capability: DeviceCapabilityManager,
    private val models: LocalModelManager,
    private val runtimeFactory: RuntimeFactory,
    private val contextEngine: ContextEngine,
    private val obs: AIObservability,
) {

    enum class Brain { LOCAL_LLM, DETERMINISTIC, DISABLED }

    data class Outcome<T>(val value: T?, val brain: Brain, val note: String = "")

    private val mu = Mutex()
    private var runtime: LocalInferenceRuntime? = null
    private var loadedModel: ModelMeta? = null
    private var lastUsedAt = 0L

    // ── local LLM ladder (§3, §4) — every rung can only fall DOWN ────────────
    private suspend fun llmInfer(task: String, snapshotBlock: String, maxOut: Int): String? {
        val f = config.flags
        if (!f.localAiEnabled) return null
        val tier = capability.tier(f.minFreeStorageMb)
        if (tier == ModelTier.NONE) { obs.count("tier_none"); return null }
        obs.tierSelected(tier)
        val meta = config.modelFor(tier) ?: return null // catalog has no artifact for us yet
        return mu.withLock {
            runCatching {
                // idle unload policy (§4: no permanent residency)
                if (runtime?.loaded == true && System.currentTimeMillis() - lastUsedAt > f.unloadAfterMs) {
                    runtime?.unload(); loadedModel = null; obs.count("unload_idle")
                }
                var rt = runtime
                if (rt?.loaded != true || loadedModel?.id != meta.id) {
                    val file = models.ensure(meta) ?: run { obs.count("model_download_fail"); return@runCatching null }
                    val fresh = runtimeFactory.create()
                    if (!fresh.load(file.absolutePath, meta.ctx)) {
                        obs.count("model_load_fail"); fresh.unload(); return@runCatching null
                    }
                    runtime = fresh; rt = fresh; loadedModel = meta; obs.count("model_load_ok")
                }
                obs.peakMb("ram_peak_mb", Runtime.getRuntime().totalMemory() / (1024 * 1024))
                val prompt = buildString {
                    appendLine("You are THE SYSTEM's embedded strategist. Reply ONLY with one JSON object. No prose, no markdown.")
                    appendLine("TASK: $task")
                    appendLine("CONTEXT:")
                    appendLine(snapshotBlock)
                    appendLine("JSON:")
                }
                val res = rt.infer(prompt, maxOut, f.inferenceTimeoutMs)
                lastUsedAt = System.currentTimeMillis()
                if (res == null) { obs.count("inference_fail"); null } else {
                    obs.latency("infer_ms", res.latencyMs)
                    obs.count("inference_ok")
                    res.text
                }
            }.getOrElse { obs.count("inference_fail"); null }
        }
    }

    /** §4: when vision runs, LLM sheds load — never stacked inference on low-end. */
    fun visionActiveHint(active: Boolean) {
        if (active && runtime?.loaded == true) {
            runtime?.unload(); loadedModel = null; obs.count("unload_for_vision")
        }
    }

    fun release() {
        runtime?.unload(); runtime = null; loadedModel = null
        obs.count("unload_release")
    }

    // ── §8 DAILY QUEST ───────────────────────────────────────────────────────
    suspend fun proposeQuest(s: ContextEngine.Snapshot): Outcome<QuestProposal> = withContext(Dispatchers.Default) {
        val f = config.refresh()
        if (!f.questAiEnabled) return@withContext Outcome(null, Brain.DISABLED, "quest_ai off")
        obs.count("req_quest")
        llmInfer(
            "Propose ONE bonus daily quest as {\"type\":\"daily_quest\",\"title\":\"..\",\"exercise\":\"PUSH|SQUAT|RUN|WALK|PLANK\",\"target\":int,\"target_unit\":\"REPS|METERS|SECONDS\",\"duration_minutes\":int,\"difficulty\":\"EASY|MEDIUM|HARD\",\"reason\":\"..\",\"confidence\":0-1}. Numbers must respect verified bests (never >1.5x jumps).",
            contextEngine.promptBlock(s), f.maxOutputTokens,
        )?.let { raw ->
            runCatching { AiJson.decodeFromString(QuestProposal.serializer(), extractJsonObject(raw) ?: return@let null) }
                .getOrNull()
                ?.takeIf { AIValidator.quest(it, s) == null }
                ?.let { return@withContext Outcome(it, Brain.LOCAL_LLM) }
            obs.count("llm_rejected")
        }
        val det = DeterministicAI.questProposal(s)
        val why = AIValidator.quest(det, s)
        if (why != null) Outcome(null, Brain.DISABLED, why)
        else Outcome(det, Brain.DETERMINISTIC).also { obs.count("fallback_quest") }
    }

    // ── §9 DAILY NUTRITION ───────────────────────────────────────────────────
    suspend fun planNutrition(s: ContextEngine.Snapshot, budgetInr: Double): Outcome<NutritionPlan> = withContext(Dispatchers.Default) {
        val f = config.refresh()
        if (!f.nutritionAiEnabled) return@withContext Outcome(null, Brain.DISABLED, "nutrition_ai off")
        obs.count("req_nutrition")
        val ids = s.products.map { it.id }.toSet()
        llmInfer(
            buildString {
                append("Build a practical indian daily meal plan as {\"type\":\"daily_nutrition\",\"calorie_target\":int,\"slots\":[3..6 strings BREAKFAST/LUNCH/SNACK/DINNER with kcal],\"market_refs\":[],\"budget_inr\":0,\"reason\":\"..\"}. ")
                append("Rules: calories>=${f.calorieFloor}")
                if (s.minor) append(" (1800+ conservative — user is under 18)")
                append("; NEVER invent products or prices — market_refs must come from the market context ids or stay empty.")
            },
            contextEngine.promptBlock(s), f.maxOutputTokens,
        )?.let { raw ->
            runCatching { AiJson.decodeFromString(NutritionPlan.serializer(), extractJsonObject(raw) ?: return@let null) }
                .getOrNull()
                ?.takeIf { AIValidator.nutrition(it, ids, f.calorieFloor, s.minor) == null }
                ?.let { return@withContext Outcome(it, Brain.LOCAL_LLM) }
            obs.count("llm_rejected")
        }
        val det = DeterministicAI.nutritionPlan(s, budgetInr, f.calorieFloor)
        val why = AIValidator.nutrition(det, ids, f.calorieFloor, s.minor)
        if (why != null) Outcome(null, Brain.DISABLED, why)
        else Outcome(det, Brain.DETERMINISTIC).also { obs.count("fallback_nutrition") }
    }

    // ── §14 DAILY ROUTINE ────────────────────────────────────────────────────
    suspend fun draftRoutine(s: ContextEngine.Snapshot): Outcome<RoutineDraft> = withContext(Dispatchers.Default) {
        val f = config.refresh()
        if (!f.routineAiEnabled) return@withContext Outcome(null, Brain.DISABLED, "routine_ai off")
        obs.count("req_routine")
        llmInfer(
            "Draft today's routine as {\"type\":\"daily_routine\",\"items\":[{\"title\":\"..\",\"time\":\"HH:MM\",\"category\":\"TRAINING|QUEST|RECOVERY|MEAL|COMMIT|SLEEP\",\"duration_min\":int,\"status\":\"PENDING\",\"notes\":\"\"}],\"reason\":\"..\"}. Include every open quest block from context, meals, sleep prep. Max 10 items.",
            contextEngine.promptBlock(s), f.maxOutputTokens,
        )?.let { raw ->
            runCatching { AiJson.decodeFromString(RoutineDraft.serializer(), extractJsonObject(raw) ?: return@let null) }
                .getOrNull()
                ?.let { it.copy(items = AIValidator.routine(it.items)) }
                ?.takeIf { it.items.size >= 3 }
                ?.let { return@withContext Outcome(it, Brain.LOCAL_LLM) }
            obs.count("llm_rejected")
        }
        Outcome(DeterministicAI.routineDraft(s), Brain.DETERMINISTIC).also { obs.count("fallback_routine") }
    }

    // ── §12 SYSTEM ASSISTANT ─────────────────────────────────────────────────
    suspend fun ask(question: String, personality: Personality, s: ContextEngine.Snapshot): Outcome<AssistantAnswer> = withContext(Dispatchers.Default) {
        val f = config.refresh()
        if (!f.assistantEnabled) return@withContext Outcome(
            AIValidator.assistant(AssistantAnswer(text = "Assistant is off-grid by admin flag. Status board still works.")),
            Brain.DISABLED, "assistant off",
        )
        obs.count("req_assistant")
        llmInfer(
            """Hunter asks: "${question.take(200)}". Reply as {"type":"assistant_reply","text":"..","follow_ups":["<=3 short strings"]}. Personality: ${personality.systemNote} Answer ONLY from context facts. Never invent stats, prices or ranks.""",
            contextEngine.promptBlock(s), f.maxOutputTokens,
        )?.let { raw ->
            runCatching { AiJson.decodeFromString(AssistantAnswer.serializer(), extractJsonObject(raw) ?: return@let null) }
                .getOrNull()
                ?.let { return@withContext Outcome(AIValidator.assistant(it), Brain.LOCAL_LLM) }
            obs.count("llm_rejected")
        }
        Outcome(DeterministicAI.assistantAnswer(question, s, personality), Brain.DETERMINISTIC)
            .also { obs.count("fallback_assistant") }
    }
}
