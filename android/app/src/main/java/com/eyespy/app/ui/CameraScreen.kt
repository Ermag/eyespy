package com.eyespy.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.eyespy.app.Prefs
import com.eyespy.app.camera.CameraService

private fun granted(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

@SuppressLint("BatteryLife")
@Composable
fun CameraScreen(prefs: Prefs, onReset: () -> Unit) {
    val ctx = LocalContext.current
    val st by CameraService.state.collectAsState()
    val frame by CameraService.preview.collectAsState()
    var showPreview by remember { mutableStateOf(false) }
    var refresh by remember { mutableIntStateOf(0) } // re-check permissions after returning from settings

    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        refresh++
        if (res[Manifest.permission.CAMERA] == true && !CameraService.state.value.running) CameraService.start(ctx)
    }
    fun requestPerms() {
        val list = mutableListOf(Manifest.permission.CAMERA)
        if (prefs.audio) list += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33) list += Manifest.permission.POST_NOTIFICATIONS
        permLauncher.launch(list.toTypedArray())
    }
    LaunchedEffect(Unit) {
        when {
            !granted(ctx, Manifest.permission.CAMERA) -> requestPerms() // starts the service once granted
            !st.running && (prefs.cameraRunning || !prefs.cameraEverStarted) -> CameraService.start(ctx)
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    DisposableEffect(showPreview) {
        CameraService.previewRequested = showPreview
        onDispose { CameraService.previewRequested = false }
    }

    Scaffold(topBar = { Bar("eyeSpy camera") }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Status") {
                Dot(if (st.running) Online else MaterialTheme.colorScheme.outline, if (st.running) "Service running" else "Service stopped")
                Dot(if (st.connected) Online else MaterialTheme.colorScheme.outline,
                    if (st.connected) "Connected to server" else "Not connected", prefs.serverUrl)
                Dot(if (st.armed) Recording else MaterialTheme.colorScheme.outline, if (st.armed) "Armed" else "Disarmed", st.reason)
                Dot(if (st.recording) Recording else MaterialTheme.colorScheme.outline, if (st.recording) "Recording" else "Not recording")
                if (st.live) Dot(Online, "Someone is watching live")
                Text("Pending uploads: ${st.pendingUploads}" + if (st.lastMotion > 0) " · last motion ${fmtTime(st.lastMotion)}" else "",
                    style = MaterialTheme.typography.bodyMedium)
                st.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (st.running) {
                    OutlinedButton(onClick = { CameraService.stop(ctx) }, modifier = Modifier.fillMaxWidth()) { Text("Stop camera service") }
                } else {
                    Button(
                        onClick = { if (granted(ctx, Manifest.permission.CAMERA)) CameraService.start(ctx) else requestPerms() },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Start camera service") }
                }
                Text("Leave this running and plugged in. The screen can turn off. Arming is controlled by the server (presence or manual mode).",
                    style = MaterialTheme.typography.bodySmall)
            }

            if (st.running) Section("Aim the camera") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Show preview", Modifier.weight(1f))
                    Switch(showPreview, { showPreview = it })
                }
                if (showPreview) frame?.let {
                    Image(it.asImageBitmap(), "Preview", Modifier.fillMaxWidth().aspectRatio(it.width.toFloat() / it.height),
                        contentScale = ContentScale.Fit)
                }
            }

            CameraSettings(prefs)
            Checklist(prefs, refresh, ::requestPerms)

            TextButton(onClick = { CameraService.stop(ctx); prefs.role = null; onReset() }) { Text("Change role / server") }
        }
    }
}

@Composable
private fun CameraSettings(prefs: Prefs) {
    var quality by remember { mutableStateOf(prefs.quality) }
    var fps by remember { mutableIntStateOf(prefs.fps) }
    var seg by remember { mutableIntStateOf(prefs.segmentSeconds) }
    var bitrate by remember { mutableIntStateOf(prefs.bitrateKbps) }
    var sens by remember { mutableIntStateOf(prefs.sensitivity) }
    var audio by remember { mutableStateOf(prefs.audio) }
    var front by remember { mutableStateOf(prefs.frontCamera) }
    var mount by remember { mutableIntStateOf(prefs.mount) }

    fun save() {
        prefs.quality = quality; prefs.fps = fps; prefs.segmentSeconds = seg; prefs.bitrateKbps = bitrate
        prefs.sensitivity = sens; prefs.audio = audio; prefs.frontCamera = front; prefs.mount = mount
        CameraService.applySettings()
    }

    Section("Recording settings") {
        Text("Quality")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("SD" to "480p", "HD" to "720p", "FHD" to "1080p").forEach { (k, l) ->
                FilterChip(quality == k, { quality = k; save() }, { Text(l) })
            }
        }
        Text("Frame rate")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10, 15, 24, 30).forEach { f -> FilterChip(fps == f, { fps = f; save() }, { Text("$f fps") }) }
        }
        Text("Bitrate: %.2f Mbps (≈ %.1f GB per hour)".format(bitrate / 1000.0, bitrate * 0.00045))
        Slider(bitrate.toFloat(), { bitrate = (it / 250).toInt() * 250 }, valueRange = 500f..8000f, onValueChangeFinished = ::save)
        Text("Segment length: $seg s")
        Slider(seg.toFloat(), { seg = (it / 15).toInt() * 15 }, valueRange = 15f..300f, onValueChangeFinished = ::save)
        Text("Motion sensitivity: $sens / 10")
        Slider(sens.toFloat(), { sens = it.toInt() }, valueRange = 1f..10f, steps = 8, onValueChangeFinished = ::save)
        Text("Mounted as")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(0 to "Portrait", 1 to "Landscape", 3 to "Landscape ⟲", 2 to "Upside down").forEach { (k, l) ->
                FilterChip(mount == k, { mount = k; save() }, { Text(l) })
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Use front camera", Modifier.weight(1f)); Switch(front, { front = it; save() })
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Record audio (restart service after changing)", Modifier.weight(1f)); Switch(audio, { audio = it; save() })
        }
    }
}

@SuppressLint("BatteryLife")
@Composable
private fun Checklist(prefs: Prefs, refresh: Int, requestPerms: () -> Unit) {
    val ctx = LocalContext.current
    val pm = ctx.getSystemService(PowerManager::class.java)
    // `refresh` is read so this recomposes after permission results
    val cam = refresh >= 0 && granted(ctx, Manifest.permission.CAMERA)
    val batteryOk = pm.isIgnoringBatteryOptimizations(ctx.packageName)
    val overlay = Settings.canDrawOverlays(ctx)
    Section("Reliability checklist") {
        CheckRow("Camera permission", cam) { requestPerms() }
        if (prefs.audio) CheckRow("Microphone permission", granted(ctx, Manifest.permission.RECORD_AUDIO)) { requestPerms() }
        CheckRow("Unrestricted battery (no Doze kills)", batteryOk) {
            ctx.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}")))
        }
        CheckRow("Display over other apps (auto-start after reboot)", overlay) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${ctx.packageName}")))
        }
        Text("Samsung tips: Settings › Battery › Background usage limits › add eyeSpy to \"Never sleeping apps\". " +
            "Turn on Battery › Protect battery (85% limit) since the phone stays plugged in.",
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun CheckRow(label: String, ok: Boolean, fix: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Dot(if (ok) Online else Recording, label)
        if (!ok) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                TextButton(onClick = fix, colors = ButtonDefaults.textButtonColors()) { Text("Fix") }
            }
        }
    }
}
