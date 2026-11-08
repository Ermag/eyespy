package com.eyespy.app

import android.content.Context
import android.os.Build
import android.view.Surface

class Prefs(ctx: Context) {
    private val p = ctx.applicationContext.getSharedPreferences("eyespy", Context.MODE_PRIVATE)

    private fun str(k: String, d: String) = p.getString(k, d) ?: d
    private fun put(k: String, v: Any?) = p.edit().apply {
        when (v) {
            null -> remove(k)
            is String -> putString(k, v)
            is Int -> putInt(k, v)
            is Long -> putLong(k, v)
            is Boolean -> putBoolean(k, v)
            is Float -> putFloat(k, v)
            is Double -> putLong(k, java.lang.Double.doubleToRawLongBits(v))
        }
    }.apply()

    /** "camera" | "viewer" | null (not set up yet) */
    var role: String? get() = p.getString("role", null); set(v) = put("role", v)
    var serverUrl: String get() = str("serverUrl", ""); set(v) = put("serverUrl", v.trim().trimEnd('/'))
    var token: String get() = str("token", ""); set(v) = put("token", v.trim())
    var deviceName: String get() = str("deviceName", Build.MODEL); set(v) = put("deviceName", v.trim())

    // from server /api/client-config
    var ntfyServer: String get() = str("ntfyServer", "https://ntfy.sh"); set(v) = put("ntfyServer", v)
    var ntfyTopic: String get() = str("ntfyTopic", ""); set(v) = put("ntfyTopic", v)
    var presenceTopic: String get() = str("presenceTopic", ""); set(v) = put("presenceTopic", v)
    var ntfyToken: String get() = str("ntfyToken", ""); set(v) = put("ntfyToken", v)

    // ---- camera mode ----
    var cameraRunning: Boolean get() = p.getBoolean("cameraRunning", false); set(v) { put("cameraRunning", v); if (v) put("cameraEverStarted", true) }
    val cameraEverStarted: Boolean get() = p.getBoolean("cameraEverStarted", false)
    var segmentSeconds: Int get() = p.getInt("segmentSeconds", 60); set(v) = put("segmentSeconds", v)
    var quality: String get() = str("quality", "HD"); set(v) = put("quality", v)   // SD | HD | FHD
    var fps: Int get() = p.getInt("fps", 15); set(v) = put("fps", v)
    var bitrateKbps: Int get() = p.getInt("bitrateKbps", 2500); set(v) = put("bitrateKbps", v)
    var audio: Boolean get() = p.getBoolean("audio", false); set(v) = put("audio", v)
    var sensitivity: Int get() = p.getInt("sensitivity", 5); set(v) = put("sensitivity", v)  // 1..10
    var frontCamera: Boolean get() = p.getBoolean("frontCamera", false); set(v) = put("frontCamera", v)
    /** 0 portrait, 1 landscape (rotated left), 2 upside down, 3 landscape (rotated right) */
    var mount: Int get() = p.getInt("mount", 0); set(v) = put("mount", v)
    var maxLocalGb: Int get() = p.getInt("maxLocalGb", 8); set(v) = put("maxLocalGb", v)

    val surfaceRotation: Int
        get() = when (mount) {
            1 -> Surface.ROTATION_90; 2 -> Surface.ROTATION_180; 3 -> Surface.ROTATION_270; else -> Surface.ROTATION_0
        }

    // ---- viewer mode ----
    var presenceEnabled: Boolean get() = p.getBoolean("presenceEnabled", false); set(v) = put("presenceEnabled", v)
    var homeLat: Double get() = java.lang.Double.longBitsToDouble(p.getLong("homeLat", 0)); set(v) = put("homeLat", v)
    var homeLng: Double get() = java.lang.Double.longBitsToDouble(p.getLong("homeLng", 0)); set(v) = put("homeLng", v)
    var homeRadius: Int get() = p.getInt("homeRadius", 150); set(v) = put("homeRadius", v)
    var hasHome: Boolean get() = p.getBoolean("hasHome", false); set(v) = put("hasHome", v)
    var lastPresence: String? get() = p.getString("lastPresence", null); set(v) = put("lastPresence", v)
    var lastPresenceTs: Long get() = p.getLong("lastPresenceTs", 0); set(v) = put("lastPresenceTs", v)
    var lastPresenceVia: String get() = str("lastPresenceVia", ""); set(v) = put("lastPresenceVia", v)
}
