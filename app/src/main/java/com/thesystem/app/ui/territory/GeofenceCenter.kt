package com.thesystem.app.ui.territory

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import com.thesystem.app.core.SystemMath

/**
 * Registers 1KM geofences around the hunter's current geohash grid so THE SYSTEM can
 * wake on zone ENTRY (e.g. "You entered contested zone u4pruy — capture or pay tribute").
 * Re-register on significant location change; Android caps geofences per app at 100.
 */
object GeofenceCenter {
    const val ACTION_GEOFENCE = "com.thesystem.app.GEOFENCE_EVENT"
    const val EXTRA_ZONE = "zone"

    @SuppressLint("MissingPermission") // caller must hold ACCESS_FINE_LOCATION (+ background for API 29+)
    fun registerGrid(context: Context, zones: List<Pair<String, Pair<Double, Double>>>) {
        val client = LocationServices.getGeofencingClient(context)
        val geofences = zones.take(50).map { (zone, center) ->
            Geofence.Builder()
                .setRequestId(zone)
                .setCircularRegion(center.first, center.second, SystemMath.TERRITORY_ZONE_RADIUS_METERS)
                .setExpirationDuration(Geofence.NEVER_EXPIRE)
                .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
                .build()
        }
        if (geofences.isEmpty()) return
        val request = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER)
            .addGeofences(geofences)
            .build()
        client.addGeofences(request, pendingIntent(context))
    }

    fun clear(context: Context) =
        LocationServices.getGeofencingClient(context).removeGeofences(pendingIntent(context))

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(ACTION_GEOFENCE).setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context, 77, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }
}

/** Receives ENTER/EXIT transitions → instant heads-up "contested territory" alert.
 *  Deliberately a broadcast (not a foreground service) → zero battery cost on low-end devices.
 *  Channel-level vibration = built-in haptics without a VIBRATE permission. */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = com.google.android.gms.location.GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        val zones = event.triggeringGeofences?.map { it.requestId }.orEmpty()
        if (zones.isEmpty()) return
        val entered = event.geofenceTransition == Geofence.GEOFENCE_TRANSITION_ENTER
        val zone = zones.first()

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        nm.createNotificationChannel(
            android.app.NotificationChannel(CHANNEL_ID, "Territory Alerts", android.app.NotificationManager.IMPORTANCE_HIGH).apply {
                description = "1KM zone entry/exit — capture it, or pay the guild's tribute"
                enableVibration(true)
            }
        )
        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setContentTitle(if (entered) "⚔ CONTESTED ZONE ENTERED" else "ZONE EXITED")
            .setContentText(
                if (entered) "Zone $zone — train here to claim it, or bleed the 5% guild tribute."
                else "You left zone $zone. Its ruler stays its ruler."
            )
            .setAutoCancel(true)
            .build()
        // POST_NOTIFICATIONS is declared in the manifest; on API 33+ a denial just suppresses — a receiver must NEVER crash.
        runCatching { nm.notify(zone.hashCode(), notification) }
    }

    private companion object { const val CHANNEL_ID = "territory_alerts" }
}
