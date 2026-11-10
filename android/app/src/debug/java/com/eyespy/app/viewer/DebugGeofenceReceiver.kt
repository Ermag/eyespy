package com.eyespy.app.viewer

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug builds only: simulate a geofence transition without Play services geofencing, e.g.
 *   adb shell am broadcast -n com.eyespy.app/.viewer.DebugGeofenceReceiver --es state away
 *   adb shell am broadcast -n com.eyespy.app/.viewer.DebugGeofenceReceiver --es state check
 */
class DebugGeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (val s = intent.getStringExtra("state")) {
            "home", "away" -> PresenceCheck.confirm(ctx, s)
            "check" -> androidx.work.WorkManager.getInstance(ctx).enqueue(
                androidx.work.OneTimeWorkRequestBuilder<PresenceCheckWorker>().build()
            )
        }
    }
}
