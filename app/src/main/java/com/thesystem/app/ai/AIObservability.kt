package com.thesystem.app.ai

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI OBSERVABILITY (spec §25) — technical counters ONLY. No prompts, no
 * responses, no personal content. Local persistence; the benchmark screen
 * reads the same store.
 */
@Singleton
class AIObservability @Inject constructor(
    @ApplicationContext app: Context,
) {
    private val prefs = app.getSharedPreferences("ai_metrics", Context.MODE_PRIVATE)

    @Volatile
    var lastEvent: String = ""
        private set

    fun count(key: String) {
        lastEvent = key
        prefs.edit().putLong("c_$key", prefs.getLong("c_$key", 0L) + 1L).apply()
    }

    /** Exponential moving average for latencies (ms). */
    fun latency(key: String, ms: Long) {
        val prev = prefs.getFloat("l_$key", -1f)
        val next = if (prev < 0) ms.toFloat() else prev * 0.7f + ms * 0.3f
        prefs.edit().putFloat("l_$key", next).apply()
    }

    fun peakMb(key: String, mb: Long) {
        if (mb > prefs.getLong("p_$key", 0L)) prefs.edit().putLong("p_$key", mb).apply()
    }

    fun tierSelected(tier: ModelTier) {
        prefs.edit().putString("tier_last", tier.name).apply()
        count("tier_${tier.name.lowercase()}")
    }

    fun snapshot(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        prefs.all.forEach { (k, v) ->
            when (v) {
                is Long -> out[k] = v.toString()
                is Float -> out[k] = "%.0f".format(v)
                is String -> out[k] = v
            }
        }
        return out
    }
}
