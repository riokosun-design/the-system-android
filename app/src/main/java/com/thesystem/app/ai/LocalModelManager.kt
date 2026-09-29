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
 * LOCAL MODEL MANAGER (master prompt §13–§15) — versioned, integrity-verified,
 * on-demand model packages. Nothing model-sized ships in the APK.
 *
 * 0.14.0 hardening:
 *  · downloads are EXPLICIT ONLY (hunter taps GET on the status page) — the
 *    inference path never silently fetches hundreds of MB in the background
 *  · exact published byte count + sha256 pin — drift = CORRUPT, never loaded
 *  · honest progress callback (bytes received / total) for the UI bar
 *  · storage pre-flight (bundle × 1.15 headroom) before a single byte moves
 *  · one download at a time; stale versions purged after successful verify
 *  · failure at any step = clean ABSENT state = deterministic fallback
 */
@Singleton
class LocalModelManager @Inject constructor(
    @ApplicationContext app: Context,
) {

    enum class ModelState { ABSENT, READY, CORRUPT }

    private val dir = File(app.filesDir, "aimodels").apply { mkdirs() }
    private val mu = Mutex()
    private val dlMu = Mutex()          // global "one fetch at a time" gate

    fun fileFor(m: ModelMeta): File = File(dir, "${m.id}-v${m.version}.bin")

    fun state(m: ModelMeta): ModelState {
        val f = fileFor(m)
        if (!f.exists()) return ModelState.ABSENT
        if (m.sha256 != null) return if (sha256(f).equals(m.sha256, ignoreCase = true)) ModelState.READY else ModelState.CORRUPT
        // no pin published yet: size sanity only (floor 1 MB — a real bundle is never tiny)
        return if (f.length() > 1_000_000) ModelState.READY else ModelState.CORRUPT
    }

    /** READY bundle path for inference — null when not present. NEVER downloads. */
    fun readyFile(m: ModelMeta): File? = if (state(m) == ModelState.READY) fileFor(m) else null

    fun freeStorageMb(): Long = runCatching {
        android.os.StatFs(dir.absolutePath).availableBytes / (1024 * 1024)
    }.getOrDefault(0L)

    /** Pre-flight: enough headroom for this bundle (§14: fail before byte one). */
    fun storageFits(m: ModelMeta): Boolean =
        freeStorageMb() > (if (m.sizeBytes > 0) m.sizeBytes else m.sizeMb * 1L * 1024 * 1024) * 115 / (100 * 1024 * 1024)

    /**
     * EXPLICIT download — hunter-initiated only. Streams to a tmp file with
     * progress pulses (every ~2 MB), enforces the exact published byte count,
     * verifies the sha256 pin, then atomically installs + purges stale
     * versions. Null on ANY failure; partial files always deleted.
     */
    suspend fun download(m: ModelMeta, onProgress: (received: Long, total: Long) -> Unit = { _, _ -> }): File? =
        withContext(Dispatchers.IO) {
            val target = fileFor(m)
            if (state(m) == ModelState.READY) { purgeStale(keep = target); return@withContext target }
            val url = m.url?.takeIf { it.startsWith("https://") } ?: run {
                target.delete(); return@withContext null
            }
            if (!storageFits(m)) return@withContext null
            dlMu.withLock {
                if (state(m) == ModelState.READY) { purgeStale(keep = target); return@withLock target }
                target.delete()
                val tmp = File(dir, "${m.id}-${System.currentTimeMillis()}.part")
                val ok = runCatching {
                    val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 20_000
                        readTimeout = 120_000       // generous per-read window for real cellular
                        instanceFollowRedirects = true
                    }
                    val total = conn.contentLengthLong.takeIf { it > 0 }
                        ?: m.sizeBytes.takeIf { it > 0 } ?: 0L
                    var got = 0L
                    var lastPulse = 0L
                    conn.inputStream.use { ins ->
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(128 * 1024)
                            while (true) {
                                val n = ins.read(buf); if (n < 0) break
                                out.write(buf, 0, n); got += n
                                if (got - lastPulse >= 2 * 1024 * 1024) {
                                    lastPulse = got
                                    runCatching { onProgress(got, total) }
                                }
                            }
                        }
                    }
                    conn.disconnect()
                    runCatching { onProgress(got, if (total > 0) total else got) }
                    // exact-byte law: when the catalog pins a size, drift = reject
                    (m.sizeBytes <= 0 || tmp.length() == m.sizeBytes) &&
                        (m.sha256 == null || sha256(tmp).equals(m.sha256, ignoreCase = true))
                }.getOrDefault(false)
                val result = if (ok && tmp.length() > 1_000_000 && tmp.renameTo(target)) target else null
                if (result == null) { tmp.delete(); target.delete() } else purgeStale(keep = target)
                result
            }
        }

    /** Delete one model (status page manage action). */
    fun delete(m: ModelMeta) { fileFor(m).delete() }

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
