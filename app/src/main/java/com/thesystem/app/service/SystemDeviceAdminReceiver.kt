package com.thesystem.app.service

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/**
 * §7 DEVICE GUARD — while active, Android itself refuses to uninstall
 * THE SYSTEM without first deactivating this admin; and any deactivation
 * attempt is a Settings screen the accessibility guardian pounces on with
 * the System Abandonment Protocol. The two layers interlock.
 */
class SystemDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Toast.makeText(context, "[ THE SYSTEM: DEVICE GUARD ACTIVE ]", Toast.LENGTH_SHORT).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "Deactivate the Device Guard and the System Abandonment Protocol fires. " +
            "Quit only if you mean it — the confession ritual will judge you."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        Toast.makeText(context, "[ THE SYSTEM: DEVICE GUARD SURRENDERED ]", Toast.LENGTH_SHORT).show()
    }
}
