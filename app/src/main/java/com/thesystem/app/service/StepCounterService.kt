package com.thesystem.app.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.thesystem.app.MainActivity
import com.thesystem.app.TheSystemApplication

/**
 * Foreground HEALTH service: hardware step counting for RUN / WALK quest proof.
 *
 * Keeps counting through music playback, app switching and a locked screen,
 * then the proof screen syncs on return. The camera is NEVER started here —
 * pose verification is always an explicit in-app screen.
 */
class StepCounterService : LifecycleService(), SensorEventListener {

    private lateinit var sensors: SensorManager
    private var stepSensor: Sensor? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastStepAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            // active time only accumulates while steps are actually landing
            if (System.currentTimeMillis() - lastStepAt < 5_000) StepTracking.tickActiveSecond()
            pushNotification()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        sensors = getSystemService(SENSOR_SERVICE) as SensorManager
        stepSensor = sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        StepTracking.sensorPresent(stepSensor != null)
        startForegroundCompat()
        if (stepSensor != null) {
            sensors.registerListener(this, stepSensor, SensorManager.SENSOR_DELAY_UI)
            handler.post(tick)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        return START_STICKY // process death → the protocol resumes on its own
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            StepTracking.onHardwareTotal(event.values[0].toLong())
            lastStepAt = System.currentTimeMillis()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        runCatching { sensors.unregisterListener(this) }
        StepTracking.stopped()
        super.onDestroy()
    }

    private fun startForegroundCompat() {
        val n = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    private fun pushNotification() {
        val nm = getSystemService(android.app.NotificationManager::class.java)
        runCatching { nm.notify(NOTIF_ID, buildNotification()) }
    }

    private fun buildNotification(): Notification {
        val s = StepTracking.snapshot.value
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val mins = s.activeSec / 60
        val quest = if (s.questTarget > 0) " · quest ${s.questProgress}/${s.questTarget}m" else ""
        return NotificationCompat.Builder(this, TheSystemApplication.CHANNEL_TRACKING)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentTitle("DISTANCE PROTOCOL ACTIVE")
            .setContentText("${s.steps} steps · %.2f km · %d min$quest".format(s.km, mins))
            .setOngoing(true).setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_WORKOUT)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val NOTIF_ID = 7001

        fun start(context: Context) {
            val i = Intent(context, StepCounterService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(i)
            else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, StepCounterService::class.java))
        }
    }
}
