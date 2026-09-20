package com.thesystem.app.core.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.sqrt

/**
 * IMU STABILITY WITNESS — CV-BATTLE-ARCHITECTURE §2.
 *
 * A phone propped on a chair/stack of books is MOTIONLESS during legitimate
 * capture. Any sustained acceleration variance or rotation therefore means the
 * camera (or the floor) moved — the one fact the camera itself cannot judge.
 *
 *   accel_var = variance(|a| − 9.81) over a rolling 500 ms window
 *   gyro_norm = max |ω| over the same window
 *   STABLE ⇔ accel_var < 0.35 m²/s⁴ AND gyro_norm < 0.6 rad/s
 *
 * The engine freezes the moment this trips and re-anchors on recovery; a rep
 * witnessed by a moving camera is a rep that never happened.
 */
class ImuStabilityWitness(context: Context) : SensorEventListener {

    data class Reading(
        val accelVar: Float = 0f,
        val gyroNorm: Float = 0f,
        val stable: Boolean = true,
        val atMs: Long = 0L,
    )

    private val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyro: Sensor? = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

    /** (sensorTimestampNs, value) — timestamps stay inside the sensor clock domain. */
    private val accWin = ArrayDeque<Pair<Long, Float>>()
    private val gyroWin = ArrayDeque<Pair<Long, Float>>()

    private val _reading = MutableStateFlow(Reading())
    val reading: StateFlow<Reading> = _reading

    /** Polling view for the engine hot path (no flow collection on analyzers). */
    val stable: Boolean get() = _reading.value.stable

    val available: Boolean get() = accel != null

    fun start() {
        accWin.clear(); gyroWin.clear()
        accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }   // ~50 Hz
        gyro?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ACCELEROMETER -> {
                val m = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                push(accWin, e.timestamp, m - 9.81f)
            }
            Sensor.TYPE_GYROSCOPE -> {
                val w = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                push(gyroWin, e.timestamp, w)
            }
        }
        evaluate()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private fun push(win: ArrayDeque<Pair<Long, Float>>, ts: Long, v: Float) {
        win.addLast(ts to v)
        // GM068: trim everything older than 500 ms (500_000_000 ns)
        while (win.size > 1 && ts - win.first().first > 500_000_000L) win.removeFirst()
    }

    private fun evaluate() {
        var varAcc = 0f
        if (accWin.size >= 4) {
            var mean = 0f
            for ((_, v) in accWin) mean += v
            mean /= accWin.size
            var acc = 0f
            for ((_, v) in accWin) { val d = v - mean; acc += d * d }
            varAcc = acc / accWin.size
        }
        var gNorm = 0f
        for ((_, v) in gyroWin) if (v > gNorm) gNorm = v
        val stable = varAcc < ACCEL_VAR_LIMIT && gNorm < GYRO_NORM_LIMIT
        val prev = _reading.value
        if (stable != prev.stable || varAcc != prev.accelVar || gNorm != prev.gyroNorm) {
            _reading.value = Reading(varAcc, gNorm, stable, System.currentTimeMillis())
        }
    }

    companion object {
        const val ACCEL_VAR_LIMIT = 0.35f   // m²/s⁴ — §2 of the architecture
        const val GYRO_NORM_LIMIT = 0.6f    // rad/s
    }
}
