package com.thesystem.app.ai

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DEVICE CAPABILITY MANAGER (spec §3) — the router's eyes.
 *
 * Model tier is NEVER total-RAM alone: memory pressure (lowRam device flag +
 * live free headroom), thermal state, ABI and free storage all vote, and any
 * stress signal can only DOWNGRADE, never upgrade.
 */
@Singleton
class DeviceCapabilityManager @Inject constructor(
    @ApplicationContext private val app: Context,
) {

    data class Report(
        val totalRamMb: Long,
        val availRamMb: Long,
        val thresholdMb: Long,
        val lowRamDevice: Boolean,
        val underPressure: Boolean,
        val sdk: Int,
        val abis: String,
        val arm64: Boolean,
        val freeStorageMb: Long,
        val thermalStatus: Int,
        val tier: ModelTier,
    )

    private fun memInfo(): ActivityManager.MemoryInfo {
        val am = app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    }

    private fun isLowRamDevice(): Boolean =
        (app.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice

    fun freeStorageMb(): Long = runCatching {
        StatFs(File(app.filesDir.absolutePath).absolutePath).availableBytes / (1024 * 1024)
    }.getOrDefault(0L)

    fun thermalStatus(): Int =
        if (Build.VERSION.SDK_INT >= 29) runCatching {
            (app.getSystemService(Context.POWER_SERVICE) as PowerManager).currentThermalStatus
        }.getOrDefault(0) else 0

    private fun arm64(): Boolean = Build.SUPPORTED_ABIS.any { it.contains("arm64") }

    /** Live memory pressure: low-RAM device or free headroom below 20%. */
    fun memoryPressure(mi: ActivityManager.MemoryInfo = memInfo()): Boolean =
        mi.lowMemory || mi.availMem < (mi.totalMem * 0.20).toLong() || mi.availMem <= mi.threshold

    /**
     * §3 policy ladder. Total RAM proposes; pressure/thermal/storage/ABI veto.
     * A MediaPipe .task bundle effectively needs arm64 for sane latency at
     * MID+; 32-bit devices cap at TINY.
     */
    fun tier(minFreeStorageMb: Int = 400): ModelTier {
        val mi = memInfo()
        val totalMb = mi.totalMem / (1024 * 1024)
        val pressure = memoryPressure(mi)
        val thermal = thermalStatus()
        var t: ModelTier = when {
            totalMb >= ModelTier.PLUS.floorTotalRamMb -> ModelTier.PLUS
            totalMb >= ModelTier.MID.floorTotalRamMb -> ModelTier.MID
            totalMb >= ModelTier.SMALL.floorTotalRamMb -> ModelTier.SMALL
            totalMb >= ModelTier.TINY.floorTotalRamMb -> ModelTier.TINY
            else -> ModelTier.NONE
        }
        if (isLowRamDevice() && t > ModelTier.TINY) t = ModelTier.TINY
        if (pressure && t > ModelTier.TINY) t = ModelTier.TINY
        if (pressure && t == ModelTier.TINY) t = ModelTier.NONE // active pressure: text rules only
        if (thermal >= 3 && t > ModelTier.TINY) t = ModelTier.TINY // SEVERE+
        if (!arm64() && t > ModelTier.TINY) t = ModelTier.TINY
        if (freeStorageMb() < minFreeStorageMb) t = ModelTier.NONE
        if (Build.VERSION.SDK_INT < 26) t = ModelTier.NONE
        return t
    }

    fun report(minFreeStorageMb: Int = 400): Report {
        val mi = memInfo()
        return Report(
            totalRamMb = mi.totalMem / (1024 * 1024),
            availRamMb = mi.availMem / (1024 * 1024),
            thresholdMb = mi.threshold / (1024 * 1024),
            lowRamDevice = isLowRamDevice(),
            underPressure = memoryPressure(mi),
            sdk = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS.joinToString(","),
            arm64 = arm64(),
            freeStorageMb = freeStorageMb(),
            thermalStatus = thermalStatus(),
            tier = tier(minFreeStorageMb),
        )
    }
}
