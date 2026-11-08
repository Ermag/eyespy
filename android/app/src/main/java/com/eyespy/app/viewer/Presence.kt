package com.eyespy.app.viewer

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.eyespy.app.Api
import com.eyespy.app.Net
import com.eyespy.app.Prefs
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import com.google.android.gms.location.GeofencingRequest
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Presence = is this phone at home? A geofence around home flips home/away; each change is sent
 * to the server directly (works on home Wi-Fi or VPN) or, failing that, as an HMAC-signed message
 * through the ntfy presence topic, which the server subscribes to. So it works on any network.
 */
object Presence {
    private const val TAG = "eyespy.presence"
    private const val FENCE_ID = "home"

    fun hasPermissions(ctx: Context): Boolean {
        fun ok(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED
        return ok(Manifest.permission.ACCESS_FINE_LOCATION) &&
            (Build.VERSION.SDK_INT < 29 || ok(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
    }

    private fun pendingIntent(ctx: Context): PendingIntent = PendingIntent.getBroadcast(
        ctx, 0, Intent(ctx, GeofenceReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
    )

    @SuppressLint("MissingPermission")
    fun register(ctx: Context, onResult: (String?) -> Unit = {}) {
        val p = Prefs(ctx)
        if (!p.hasHome) return onResult("Set your home location first")
        if (!hasPermissions(ctx)) return onResult("Location permission \"Allow all the time\" is required")
        val fence = Geofence.Builder()
            .setRequestId(FENCE_ID)
            .setCircularRegion(p.homeLat, p.homeLng, p.homeRadius.toFloat())
            .setExpirationDuration(Geofence.NEVER_EXPIRE)
            .setTransitionTypes(Geofence.GEOFENCE_TRANSITION_ENTER or Geofence.GEOFENCE_TRANSITION_EXIT)
            .setNotificationResponsiveness(30_000)
            .build()
        val req = GeofencingRequest.Builder()
            .setInitialTrigger(GeofencingRequest.INITIAL_TRIGGER_ENTER or GeofencingRequest.INITIAL_TRIGGER_EXIT)
            .addGeofence(fence)
            .build()
        LocationServices.getGeofencingClient(ctx).addGeofences(req, pendingIntent(ctx))
            .addOnSuccessListener { Log.i(TAG, "geofence registered"); onResult(null) }
            .addOnFailureListener { Log.w(TAG, "geofence failed", it); onResult("Geofence failed: ${it.message}") }
    }

    fun unregister(ctx: Context) {
        LocationServices.getGeofencingClient(ctx).removeGeofences(pendingIntent(ctx))
    }

    /**
     * End-to-end encrypted relay message (AES-256-GCM, key derived from the API token), so ntfy and
     * anyone who knows the topic learn nothing. Format: "v1." + base64(nonce[12] + ciphertext + tag).
     * Must match server/eyespy/relay.py.
     */
    fun encrypt(token: String, device: String, state: String, ts: Long, nonce: ByteArray = ByteArray(12).also { SecureRandom().nextBytes(it) }): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(token.toByteArray(), "HmacSHA256"))
        val key = SecretKeySpec(mac.doFinal("eyespy-presence-v1".toByteArray()), "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        cipher.updateAAD("eyespy-presence".toByteArray())
        val pt = JSONObject().put("d", device).put("s", state).put("t", ts).toString().toByteArray()
        return "v1." + java.util.Base64.getEncoder().encodeToString(nonce + cipher.doFinal(pt))
    }

    /** Try to deliver a presence report. Returns how it got there, or null if it failed. */
    suspend fun deliver(ctx: Context, state: String, ts: Long): String? = withContext(Dispatchers.IO) {
        val p = Prefs(ctx)
        try {
            Api(p).post("/api/presence", JSONObject().put("device", p.deviceName).put("state", state).put("ts", ts), Net.quick)
            return@withContext "direct"
        } catch (e: Exception) {
            Log.i(TAG, "direct presence failed (${e.message}), using relay")
        }
        if (p.presenceTopic.isEmpty()) return@withContext null
        val body = encrypt(p.token, p.deviceName, state, ts)
        val req = Request.Builder().url("${p.ntfyServer.trimEnd('/')}/${p.presenceTopic}")
            .post(body.toRequestBody("text/plain".toMediaType()))
            .apply { if (p.ntfyToken.isNotEmpty()) header("Authorization", "Bearer ${p.ntfyToken}") }
            .build()
        try {
            Net.http.newCall(req).execute().use { if (it.isSuccessful) "relay" else null }
        } catch (e: Exception) {
            Log.w(TAG, "relay presence failed: ${e.message}")
            null
        }
    }

    /** Record a new state locally and send it; falls back to WorkManager retries when offline. */
    suspend fun report(ctx: Context, state: String) {
        val p = Prefs(ctx)
        val ts = System.currentTimeMillis()
        p.lastPresence = state
        p.lastPresenceTs = ts
        p.lastPresenceVia = "pending"
        val via = deliver(ctx, state, ts)
        if (via != null) p.lastPresenceVia = via else enqueueRetry(ctx, state, ts)
    }

    private fun enqueueRetry(ctx: Context, state: String, ts: Long) {
        val work = OneTimeWorkRequestBuilder<PresenceWorker>()
            .setInputData(workDataOf("state" to state, "ts" to ts))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork("presence", ExistingWorkPolicy.REPLACE, work)
    }
}

class PresenceWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val state = inputData.getString("state") ?: return Result.failure()
        val ts = inputData.getLong("ts", 0)
        // server rejects reports older than 15 min; resend the current state with a fresh timestamp
        val sendTs = if (System.currentTimeMillis() - ts > 10 * 60_000) System.currentTimeMillis() else ts
        val via = Presence.deliver(applicationContext, state, sendTs) ?: return Result.retry()
        Prefs(applicationContext).lastPresenceVia = via
        return Result.success()
    }
}

class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val ev = GeofencingEvent.fromIntent(intent) ?: return
        if (ev.hasError()) {
            Log.w("eyespy.presence", "geofence error ${ev.errorCode}")
            return
        }
        val state = when (ev.geofenceTransition) {
            Geofence.GEOFENCE_TRANSITION_ENTER -> "home"
            Geofence.GEOFENCE_TRANSITION_EXIT -> "away"
            else -> return
        }
        if (Prefs(ctx).lastPresence == state && Prefs(ctx).lastPresenceVia != "pending" &&
            System.currentTimeMillis() - Prefs(ctx).lastPresenceTs < 60_000
        ) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Presence.report(ctx, state)
            } finally {
                pending.finish()
            }
        }
    }
}
