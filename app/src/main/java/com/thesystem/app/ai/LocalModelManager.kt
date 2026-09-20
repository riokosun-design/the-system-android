package com.thesystem.app.ai

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * LOCAL MODEL MANAGER (spec §5, §23) — versioned, integrity-verified,
 * on-demand model packages. Nothing model-sized ships in the APK; the device
 * downloads ONLY the bundle its tier selected, verifies it, and purges stale
 * versions. Failure at any step = clean ABSENT state = deterministic fallback.
 */
@Singleton
class LocalModelManager @Inject constructor(
    @ApplicationContext app: Context,
) {

    enum class ModelState { ABSENT, READY, CORRUPT }

    private val dir = File(app.filesDir, "aimodels").apply { mkdirs() }
    private val mu = Mutex()

    fun fileFor(m: ModelMeta): File = File(dir, "${m.id}-v${m.version}.bin")

    fun state(m: ModelMeta): ModelState {
        val f = fileFor(m)
        if (!f.exists()) return ModelState.ABSENT
        if (m.sha256 != null) return if (sha256(f).equals(m.sha256, ignoreCase = true)) ModelState.READY else ModelState.CORRUPT
        // no pin published yet: size sanity only (floor 1 MB — a real bundle is never tiny)
        return if (f.length() > 1_000_000) ModelState.READY else ModelState.CORRUPT
    }

    /**
     * Ensure the bundle is local + verified. Null = unavailable (any reason).
     * Streams to a tmp file, verifies the pin, then atomically renames and
     * deletes every OTHER version of any catalog model (§23: no hoarding).
     */
    suspend fun ensure(m: ModelMeta): File? = withContext(Dispatchers.IO) {
        val target = fileFor(m)
        if (state(m) == ModelState.READY) { purgeStale(keep = target); return@withContext target }
        val url = m.url?.takeIf { it.startsWith("https://") } ?: run {
            target.delete(); return@withContext null
        }
        mu.withLock {
            if (state(m) == ModelState.READY) { purgeStale(keep = target); return@withLock target }
            target.delete()
            val tmp = File(dir, "${m.id}-${System.currentTimeMillis()}.part")
            val ok = runCatching {
                val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15_000
                    readTimeout = 60_000
                    instanceFollowRedirects = true
                }
                conn.inputStream.use { ins ->
                    tmp.outputStream().use { out -> ins.copyTo(out, 64 * 1024) }
                }
                conn.disconnect()
                m.sha256 == null || sha256(tmp).equals(m.sha256, ignoreCase = true)
            }.getOrDefault(false)
            val result = if (ok && tmp.length() > 1_000_000 && tmp.renameTo(target)) target else null
            if (result == null) { tmp.delete(); target.delete() } else purgeStale(keep = target)
            result
        }
    }

    fun purgeStale(keep: File) {
        dir.listFiles()?.forEach { f -> if (f.absolutePath != keep.absolutePath) f.delete() }
    }

    fun totalBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    private fun sha256(f: File): String = runCatching {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = ins.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }.getOrDefault("")
}
