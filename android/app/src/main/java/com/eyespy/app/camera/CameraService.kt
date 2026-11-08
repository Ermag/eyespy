package com.eyespy.app.camera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.eyespy.app.App
import com.eyespy.app.MainActivity
import com.eyespy.app.Net
import com.eyespy.app.Prefs
import com.eyespy.app.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class CamState(
    val running: Boolean = false,
    val connected: Boolean = false,
    val armed: Boolean = false,
    val reason: String = "",
    val recording: Boolean = false,
    val live: Boolean = false,
    val pendingUploads: Int = 0,
    val lastMotion: Long = 0,
    val lastError: String? = null,
)

/**
 * Camera-mode engine. Runs as a camera foreground service so it keeps working with the screen off.
 *  - keeps a WebSocket to the server, which tells it when to arm (record) and when someone watches live
 *  - while armed: records back-to-back MP4 segments and runs motion detection
 *  - finished segments go to [Uploader]
 */
class CameraService : LifecycleService() {

    companion object {
        private const val TAG = "eyespy.camera"
        private const val NOTIF_ID = 1
        private const val ACTION_START = "start"
        private const val ACTION_STOP = "stop"
        private const val ANALYSIS_INTERVAL_MS = 200L
        private const val LIVE_INTERVAL_MS = 200L
        private const val MOTION_REPORT_INTERVAL_MS = 10_000L

        val state = MutableStateFlow(CamState())
        val preview = MutableStateFlow<Bitmap?>(null)
        @Volatile var previewRequested = false
            set(v) { field = v; instance?.main?.post { instance?.reconcile() } }

        @Volatile private var instance: CameraService? = null

        /** Returns false (and does nothing) until the camera permission is granted. */
        fun start(ctx: Context): Boolean {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return false
            ContextCompat.startForegroundService(ctx, Intent(ctx, CameraService::class.java).setAction(ACTION_START))
            return true
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, CameraService::class.java).setAction(ACTION_STOP))
        }

        fun applySettings() {
            instance?.main?.post { instance?.rebind() }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private lateinit var uploader: Uploader
    private lateinit var pendingDir: File
    private val analysisExec: ExecutorService = Executors.newSingleThreadExecutor()
    private val motion = MotionDetector()

    private var initialized = false
    private var provider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var bound = false
    private var recording: Recording? = null
    private var reportedRecording = false
    private var segmentErrors = 0

    @Volatile private var armed = false
    @Volatile private var live = false
    private var reason = ""

    private var ws: WebSocket? = null
    private var wsBackoff = 1_000L
    private var stopping = false

    private var lastAnalysis = 0L
    private var analysedArmed = false
    private var lastLive = 0L
    private var lastMotionReport = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        prefs = Prefs(this)
        when (intent?.action) {
            ACTION_START -> {
                if (!initialized) init()
                return START_STICKY
            }
            ACTION_STOP -> {
                prefs.cameraRunning = false
                shutdown()
                return START_NOT_STICKY
            }
            else -> {
                // Restarted by the system after being killed. We may not open the camera from the
                // background, so bring the activity up (needs "display over other apps") and let it start us.
                if (!initialized) {
                    if (prefs.cameraRunning && Settings.canDrawOverlays(this)) {
                        startActivity(Intent(this, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).putExtra(MainActivity.EXTRA_AUTOSTART, true))
                    }
                    stopSelf()
                }
                return START_NOT_STICKY
            }
        }
    }

    // ---------------------------------------------------------------- lifecycle
    private fun init() {
        var types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (prefs.audio && hasPerm(Manifest.permission.RECORD_AUDIO)) types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        try {
            ServiceCompat.startForeground(this, NOTIF_ID, notification(), types)
        } catch (e: Exception) {
            // e.g. permission revoked, or started while the app wasn't in the foreground
            Log.e(TAG, "cannot start camera service", e)
            state.value = CamState(running = false, lastError = "Can't start camera service: ${e.message}")
            stopSelf()
            return
        }
        initialized = true
        instance = this
        prefs.cameraRunning = true

        pendingDir = File(filesDir, "pending").apply { mkdirs() }
        pendingDir.listFiles { f -> f.name.startsWith("rec_") }?.forEach { it.delete() } // unfinished from a crash

        acquireLocks()
        motion.sensitivity = prefs.sensitivity
        uploader = Uploader(prefs, pendingDir) { n -> state.update { it.copy(pendingUploads = n) } }
        uploader.start(lifecycleScope)
        state.value = CamState(running = true)

        ProcessCameraProvider.getInstance(this).addListener({
            provider = ProcessCameraProvider.getInstance(this).get()
            reconcile()
        }, ContextCompat.getMainExecutor(this))

        connectWs()
        lifecycleScope.launch {
            while (isActive) {
                delay(30_000)
                sendJson(JSONObject().put("type", "heartbeat").put("info", deviceInfo()))
            }
        }
    }

    private fun shutdown() {
        stopping = true
        armed = false; live = false
        recording?.stop()
        recording = null
        provider?.unbindAll()
        bound = false
        ws?.close(1000, "stopped")
        ws = null
        releaseLocks()
        state.value = CamState(running = false)
        preview.value = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        if (!stopping && initialized) {
            recording?.stop()
            ws?.close(1001, "destroyed")
            releaseLocks()
        }
        analysisExec.shutdown()
        state.update { it.copy(running = false, connected = false, recording = false) }
        super.onDestroy()
    }

    // ---------------------------------------------------------------- camera
    /** Bring camera / recording in line with what the server wants. Main thread only. */
    private fun reconcile() {
        if (stopping) return
        val wantCamera = armed || live || previewRequested
        if (wantCamera && !bound) bind()
        if (armed && bound && recording == null) startSegment()
        if (!armed && recording != null) recording?.stop() // finalize event calls reconcile again
        if (!wantCamera && bound && recording == null) {
            provider?.unbindAll(); bound = false; videoCapture = null
            preview.value = null
        }
        state.update { it.copy(armed = armed, live = live, reason = reason) }
        updateNotification()
    }

    /** Re-create use cases after settings change. */
    fun rebind() {
        motion.sensitivity = prefs.sensitivity
        if (!bound) return
        recording?.stop()
        recording = null
        provider?.unbindAll()
        bound = false
        main.postDelayed({ reconcile() }, 500)
    }

    private fun bind() {
        val p = provider ?: return
        val rotation = prefs.surfaceRotation
        val q = when (prefs.quality) { "SD" -> Quality.SD; "FHD" -> Quality.FHD; else -> Quality.HD }
        val recorder = Recorder.Builder()
            .setQualitySelector(QualitySelector.from(q, FallbackStrategy.lowerQualityOrHigherThan(q)))
            .setTargetVideoEncodingBitRate(prefs.bitrateKbps * 1000)
            .build()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                ).build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .setOutputImageRotationEnabled(true)
            .setTargetRotation(rotation)
            .build()
        analysis.setAnalyzer(analysisExec, ::analyze)
        val selector = if (prefs.frontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA

        fun videoCaptureOf(fixedFps: Boolean): VideoCapture<Recorder> = VideoCapture.Builder(recorder)
            .setTargetRotation(rotation)
            .apply { if (fixedFps) setTargetFrameRate(Range(prefs.fps, prefs.fps)) }
            .build()

        for (fixedFps in listOf(true, false)) {
            try {
                val vc = videoCaptureOf(fixedFps)
                p.unbindAll()
                p.bindToLifecycle(this, selector, vc, analysis)
                videoCapture = vc
                bound = true
                motion.restartWarmup()
                state.update { it.copy(lastError = null) }
                Log.i(TAG, "camera bound (fixed fps=$fixedFps)")
                return
            } catch (e: Exception) {
                Log.w(TAG, "bind failed (fixed fps=$fixedFps): ${e.message}")
                error("Camera bind failed: ${e.message}")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun startSegment() {
        val vc = videoCapture ?: return
        val startMs = System.currentTimeMillis()
        val file = File(pendingDir, "rec_$startMs.mp4")
        val opts = FileOutputOptions.Builder(file).setDurationLimitMillis(prefs.segmentSeconds * 1000L).build()
        var pending = vc.output.prepareRecording(this, opts)
        if (prefs.audio && hasPerm(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()
        recording = pending.start(ContextCompat.getMainExecutor(this)) { ev -> onRecordEvent(ev, file, startMs) }
    }

    private fun onRecordEvent(ev: VideoRecordEvent, file: File, startMs: Long) {
        when (ev) {
            is VideoRecordEvent.Start -> {
                if (!reportedRecording) {
                    reportedRecording = true
                    sendJson(JSONObject().put("type", "recording").put("state", "started"))
                }
                state.update { it.copy(recording = true, lastError = null) }
            }
            is VideoRecordEvent.Finalize -> {
                recording = null
                motion.requestReset() // restarting the recorder shifts exposure/colour for a moment
                val err = ev.error
                val cleanEnd = err == VideoRecordEvent.Finalize.ERROR_NONE ||
                    err == VideoRecordEvent.Finalize.ERROR_DURATION_LIMIT_REACHED
                val durMs = ev.recordingStats.recordedDurationNanos / 1_000_000
                if (file.exists() && file.length() > 0 && err != VideoRecordEvent.Finalize.ERROR_NO_VALID_DATA) {
                    file.renameTo(File(pendingDir, "seg_${startMs}_$durMs.mp4"))
                    uploader.kick()
                } else file.delete()

                if (cleanEnd) segmentErrors = 0 else {
                    segmentErrors++
                    error("Recording error $err: ${ev.cause?.message}")
                }
                when {
                    stopping -> {}
                    armed && segmentErrors == 0 -> startSegment()
                    armed -> { // back off; after repeated failures re-open the camera
                        if (segmentErrors >= 3) { provider?.unbindAll(); bound = false; videoCapture = null }
                        main.postDelayed({ reconcile() }, 2_000L * segmentErrors.coerceAtMost(15))
                    }
                    else -> {
                        if (reportedRecording) {
                            reportedRecording = false
                            sendJson(JSONObject().put("type", "recording").put("state", "stopped"))
                        }
                        state.update { it.copy(recording = false) }
                        reconcile()
                    }
                }
            }
        }
    }

    private fun analyze(img: ImageProxy) {
        try {
            val now = SystemClock.elapsedRealtime()
            if (now - lastAnalysis < ANALYSIS_INTERVAL_MS) return
            lastAnalysis = now
            var bmp: Bitmap? = null
            if (armed != analysedArmed) { analysedArmed = armed; motion.restartWarmup() }
            if (armed) {
                val score = motion.process(img)
                val wall = System.currentTimeMillis()
                if (motion.triggered(score) && wall - lastMotionReport >= MOTION_REPORT_INTERVAL_MS) {
                    lastMotionReport = wall
                    bmp = img.toBitmap()
                    val snap = Base64.encodeToString(jpeg(bmp, 70), Base64.NO_WRAP)
                    sendJson(JSONObject().put("type", "motion").put("ts", wall).put("score", score.toDouble()).put("snapshot", snap))
                    state.update { it.copy(lastMotion = wall) }
                }
            }
            val sock = ws
            val wantLive = live && sock != null && now - lastLive >= LIVE_INTERVAL_MS && sock.queueSize() < 256 * 1024
            if (wantLive || previewRequested) {
                if (bmp == null) bmp = img.toBitmap()
                if (wantLive) {
                    lastLive = now
                    sock?.send(jpeg(bmp, 60).toByteString())
                }
                if (previewRequested) preview.value = bmp
            }
        } catch (e: Exception) {
            Log.w(TAG, "analyze: ${e.message}")
        } finally {
            img.close()
        }
    }

    private fun jpeg(b: Bitmap, q: Int): ByteArray =
        ByteArrayOutputStream(64 * 1024).also { b.compress(Bitmap.CompressFormat.JPEG, q, it) }.toByteArray()

    // ---------------------------------------------------------------- server link
    private fun connectWs() {
        if (stopping) return
        val req = Request.Builder().url(prefs.serverUrl + "/api/camera/ws")
            .header("Authorization", "Bearer ${prefs.token}").build()
        ws = Net.ws.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                main.post {
                    wsBackoff = 1_000L
                    state.update { it.copy(connected = true) }
                    updateNotification()
                    webSocket.send(JSONObject().put("type", "hello").put("recording", recording != null)
                        .put("info", deviceInfo()).toString())
                    uploader.kick()
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val o = runCatching { JSONObject(text) }.getOrNull() ?: return
                if (o.optString("type") == "state") main.post {
                    if (ws !== webSocket) return@post
                    armed = o.optBoolean("armed")
                    live = o.optBoolean("live")
                    reason = o.optString("reason")
                    reconcile()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {}

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(webSocket, "closed $code $reason")

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                lost(webSocket, t.message ?: t.javaClass.simpleName)
        })
    }

    private fun lost(sock: WebSocket, why: String) {
        main.post {
            if (ws !== sock) return@post
            Log.i(TAG, "server link lost: $why")
            ws = null
            // Keep the armed state: if the server or Wi-Fi goes down we keep recording locally.
            live = false
            state.update { it.copy(connected = false, live = false) }
            reconcile()
            main.postDelayed({ connectWs() }, wsBackoff)
            wsBackoff = (wsBackoff * 2).coerceAtMost(30_000L)
        }
    }

    private fun sendJson(o: JSONObject) {
        ws?.send(o.toString())
    }

    private fun deviceInfo(): JSONObject {
        val bat = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bat?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bat?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = (bat?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val temp = (bat?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        return JSONObject()
            .put("model", Build.MODEL)
            .put("battery", if (level >= 0) level * 100 / scale else null)
            .put("charging", plugged)
            .put("temp_c", temp)
            .put("pending_uploads", state.value.pendingUploads)
            .put("free_gb", filesDir.usableSpace / 1_073_741_824.0)
            .put("quality", prefs.quality)
    }

    // ---------------------------------------------------------------- misc
    private fun error(msg: String) {
        Log.w(TAG, msg)
        state.update { it.copy(lastError = msg) }
    }

    private fun hasPerm(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "eyespy:camera").apply { setReferenceCounted(false); acquire() }
        val wm = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION")
        val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifiLock = wm.createWifiLock(mode, "eyespy:camera").apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.release() }
        runCatching { wifiLock?.release() }
    }

    private fun notification(): Notification {
        val s = state.value
        val text = when {
            !s.connected -> "Waiting for server" + if (armed) " - recording locally" else ""
            s.recording -> "Armed - recording"
            live -> "Live view active"
            else -> "Standby (disarmed)"
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, App.CHANNEL_CAMERA)
            .setSmallIcon(R.drawable.ic_stat_eye)
            .setContentTitle("eyeSpy camera")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(open)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotification() {
        if (!initialized || stopping) return
        getSystemService(android.app.NotificationManager::class.java).notify(NOTIF_ID, notification())
    }
}
