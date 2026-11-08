package com.eyespy.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.eyespy.app.viewer.Presence

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // exported for the system broadcasts; ignore anything else another app might send
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val prefs = Prefs(ctx)
        when (prefs.role) {
            "camera" -> if (prefs.cameraRunning && Settings.canDrawOverlays(ctx)) {
                // Camera foreground services can't be started from the background; the overlay
                // permission lets us bring the activity up, which then starts the service.
                ctx.startActivity(
                    Intent(ctx, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(MainActivity.EXTRA_AUTOSTART, true)
                )
            }
            "viewer" -> if (prefs.presenceEnabled) Presence.register(ctx) // geofences are cleared on reboot
        }
    }
}
