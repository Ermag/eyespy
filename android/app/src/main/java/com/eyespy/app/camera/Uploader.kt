package com.eyespy.app.camera

import android.util.Log
import com.eyespy.app.Net
import com.eyespy.app.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

/**
 * Uploads finished segments (seg_<startMs>_<durationMs>.mp4) one at a time, oldest first.
 * Files stay on the phone until the server confirms, so footage survives server/Wi-Fi outages.
 */
class Uploader(private val prefs: Prefs, private val dir: File, private val onPending: (Int) -> Unit) {
    private val wake = Channel<Unit>(Channel.CONFLATED)

    fun kick() {
        wake.trySend(Unit)
    }

    fun pending(): List<File> =
        dir.listFiles { f -> f.name.startsWith("seg_") && f.name.endsWith(".mp4") }?.sortedBy { it.name } ?: emptyList()

    fun start(scope: CoroutineScope): Job = scope.launch(Dispatchers.IO) {
        var backoff = 2_000L
        while (isActive) {
            enforceCap()
            val files = pending()
            onPending(files.size)
            if (files.isEmpty()) {
                withTimeoutOrNull(60_000) { wake.receive() }
                continue
            }
            if (upload(files.first())) {
                backoff = 2_000L
            } else {
                withTimeoutOrNull(backoff) { wake.receive() }
                backoff = (backoff * 2).coerceAtMost(60_000L)
            }
        }
    }

    private fun upload(f: File): Boolean {
        val parts = f.nameWithoutExtension.split("_")
        val startMs = parts.getOrNull(1)?.toLongOrNull()
        val durMs = parts.getOrNull(2)?.toLongOrNull() ?: 0
        if (startMs == null) {
            f.delete(); return true
        }
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("started_at", startMs.toString())
            .addFormDataPart("duration_ms", durMs.toString())
            .addFormDataPart("file", f.name, f.asRequestBody("video/mp4".toMediaType()))
            .build()
        val req = Request.Builder().url(prefs.serverUrl + "/api/segments")
            .header("Authorization", "Bearer ${prefs.token}").post(body).build()
        return try {
            Net.upload.newCall(req).execute().use { r ->
                when {
                    r.isSuccessful -> { f.delete(); true }
                    r.code == 400 || r.code == 422 -> { Log.w(TAG, "server rejected ${f.name}: ${r.code}"); f.delete(); true }
                    else -> { Log.w(TAG, "upload ${f.name} failed: HTTP ${r.code}"); false }
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "upload ${f.name} failed: ${e.message}")
            false
        }
    }

    /** Never fill the phone: drop the oldest pending footage beyond the cap. */
    private fun enforceCap() {
        val cap = prefs.maxLocalGb * 1024L * 1024 * 1024
        val files = pending()
        var total = files.sumOf { it.length() }
        for (f in files) {
            if (total <= cap && dir.usableSpace > 1024L * 1024 * 1024) break
            total -= f.length()
            Log.w(TAG, "local cap reached, dropping ${f.name}")
            f.delete()
        }
    }

    companion object {
        private const val TAG = "eyespy.upload"
    }
}
