package com.thesystem.app.ai

import android.content.Context
import com.thesystem.app.data.repo.SystemRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI REMOTE CONFIG (spec §24) — flags + model catalog from engine_config.ai.
 * Cached locally so a dead network never disables the fallback brains;
 * defaults are the SAFE ones (deterministic on, local LLM off).
 */
@Singleton
class AIRemoteConfig @Inject constructor(
    private val system: SystemRepository,
    @ApplicationContext app: Context,
) {

    private val prefs = app.getSharedPreferences("ai_remote_config", Context.MODE_PRIVATE)
    private val fetchMu = Mutex()

    @Volatile
    var flags: AiFlags = loadCached()
        private set

    private fun loadCached(): AiFlags = runCatching {
        prefs.getString("flags_json", null)?.let { AiJson.decodeFromString(AiFlags.serializer(), it) }
    }.getOrNull() ?: AiFlags()

    suspend fun refresh(force: Boolean = false): AiFlags = withContext(Dispatchers.IO) {
        val stale = System.currentTimeMillis() - prefs.getLong("fetched_at", 0L) > 15 * 60_000L
        if (!force && !stale) return@withContext flags
        fetchMu.withLock {
            if (!force && System.currentTimeMillis() - prefs.getLong("fetched_at", 0L) <= 15 * 60_000L)
                return@withContext flags
            runCatching {
                val cfg = system.engineConfig() ?: return@runCatching
                val aiEl = cfg["ai"]?.jsonObject ?: return@runCatching
                flags = AiJson.decodeFromJsonElement(AiFlags.serializer(), aiEl)
                prefs.edit()
                    .putString("flags_json", AiJson.encodeToString(AiFlags.serializer(), flags))
                    .putLong("fetched_at", System.currentTimeMillis())
                    .apply()
            }
            flags
        }
    }

    /**
     * Best catalog model for a tier: exact tier first, otherwise the largest
     * model the tier can still carry. Models without an artifact URL are
     * NOT selectable (honest catalog: metadata may precede bundles).
     */
    fun modelFor(tier: ModelTier): ModelMeta? {
        if (tier == ModelTier.NONE) return null
        val order = listOf(ModelTier.PLUS, ModelTier.MID, ModelTier.SMALL, ModelTier.TINY)
        val allowed = order.drop(order.indexOf(tier))
        val eligible = flags.models.filter { it.url != null && it.backend == "MEDIAPIPE_TASK" }
        for (t in allowed) eligible.firstOrNull { it.tier == t.name }?.let { return it }
        return null
    }

    fun catalog(): List<ModelMeta> = flags.models
}
