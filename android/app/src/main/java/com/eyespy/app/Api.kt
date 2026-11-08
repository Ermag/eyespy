package com.eyespy.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object Net {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    /** quick check used for presence: fail fast when the server isn't reachable (no VPN) */
    val quick: OkHttpClient = http.newBuilder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()
    val upload: OkHttpClient = http.newBuilder().writeTimeout(3, TimeUnit.MINUTES).readTimeout(2, TimeUnit.MINUTES).build()
    val ws: OkHttpClient = http.newBuilder().readTimeout(0, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    val stream: OkHttpClient = http.newBuilder().readTimeout(20, TimeUnit.SECONDS).build()
    val JSON = "application/json".toMediaType()
}

class ApiException(val code: Int, msg: String) : IOException(msg)

class Api(base: String, val token: String) {
    val base = base.trimEnd('/')

    constructor(p: Prefs) : this(p.serverUrl, p.token)

    fun url(path: String) = if (path.startsWith("http")) path else base + path
    fun req(path: String): Request.Builder = Request.Builder().url(url(path)).header("Authorization", "Bearer $token")

    private fun exec(r: Request, client: OkHttpClient = Net.http): String =
        client.newCall(r).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val detail = runCatching { JSONObject(body).optString("detail") }.getOrNull().orEmpty()
                throw ApiException(resp.code, "HTTP ${resp.code} ${detail.ifEmpty { resp.message }}")
            }
            body
        }

    suspend fun obj(path: String): JSONObject = withContext(Dispatchers.IO) { JSONObject(exec(req(path).build())) }
    suspend fun arr(path: String): JSONArray = withContext(Dispatchers.IO) { JSONArray(exec(req(path).build())) }
    suspend fun bytes(path: String): ByteArray = withContext(Dispatchers.IO) {
        Net.http.newCall(req(path).build()).execute().use { r ->
            if (!r.isSuccessful) throw ApiException(r.code, "HTTP ${r.code}")
            r.body!!.bytes()
        }
    }

    suspend fun post(path: String, body: JSONObject = JSONObject(), client: OkHttpClient = Net.http): JSONObject =
        withContext(Dispatchers.IO) { JSONObject(exec(req(path).post(body.toString().toRequestBody(Net.JSON)).build(), client)) }

    suspend fun delete(path: String): JSONObject = withContext(Dispatchers.IO) { JSONObject(exec(req(path).delete().build())) }

    // ---- typed helpers ----
    suspend fun status() = Status.from(obj("/api/status"))
    suspend fun days(): List<Day> = arr("/api/days").map { Day(it.getString("day"), it.getInt("segments"), it.optInt("motion_segments")) }
    suspend fun segments(day: String, motionOnly: Boolean = false): List<Segment> =
        arr("/api/segments?day=$day&motion_only=$motionOnly").map(Segment::from)
    suspend fun events(types: String? = null, limit: Int = 200): List<EventItem> =
        arr("/api/events?limit=$limit" + (types?.let { "&types=$it" } ?: "")).map(EventItem::from)
    suspend fun event(id: Long) = obj("/api/events/$id")
    suspend fun setMode(mode: String) = post("/api/mode", JSONObject().put("mode", mode))
}

fun <T> JSONArray.map(f: (JSONObject) -> T): List<T> = (0 until length()).map { f(getJSONObject(it)) }

data class Day(val day: String, val segments: Int, val motion: Int)

data class Segment(val id: Long, val startedAt: Long, val durationMs: Long, val size: Long, val hasMotion: Boolean,
                   val video: String, val thumb: String?) {
    companion object {
        fun from(o: JSONObject) = Segment(
            o.getLong("id"), o.getLong("started_at"), o.getLong("duration_ms"), o.getLong("size"),
            o.getBoolean("has_motion"), o.getString("video"), o.optString("thumb").takeIf { it.isNotEmpty() && it != "null" },
        )
    }
}

data class EventItem(val id: Long, val ts: Long, val type: String, val detail: JSONObject, val snapshot: String?) {
    companion object {
        fun from(o: JSONObject) = EventItem(
            o.getLong("id"), o.getLong("ts"), o.getString("type"), o.optJSONObject("detail") ?: JSONObject(),
            o.optString("snapshot").takeIf { it.isNotEmpty() && it != "null" },
        )
    }
}

data class PresenceRow(val device: String, val state: String, val ts: Long, val via: String)

data class Status(
    val mode: String, val armed: Boolean, val armReason: String, val recording: Boolean,
    val cameraOnline: Boolean, val cameraLastSeen: Long, val cameraInfo: JSONObject,
    val presence: List<PresenceRow>, val pushEnabled: Boolean, val storageBytes: Long, val diskFree: Long, val segments: Int,
) {
    companion object {
        fun from(o: JSONObject): Status {
            val cam = o.getJSONObject("camera")
            val st = o.optJSONObject("storage") ?: JSONObject()
            return Status(
                o.getString("mode"), o.getBoolean("armed"), o.optString("arm_reason"), o.getBoolean("recording"),
                cam.getBoolean("online"), cam.optLong("last_seen"), cam.optJSONObject("info") ?: JSONObject(),
                o.getJSONArray("presence").map { PresenceRow(it.getString("device"), it.getString("state"), it.getLong("ts"), it.optString("via")) },
                o.optBoolean("push_enabled"), st.optLong("bytes"), st.optLong("disk_free"), st.optInt("segments"),
            )
        }
    }
}
