package com.eyespy.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import com.eyespy.app.Api
import com.eyespy.app.Day
import com.eyespy.app.EventItem
import com.eyespy.app.Prefs
import com.eyespy.app.Segment
import com.eyespy.app.Status
import com.eyespy.app.viewer.Presence
import com.eyespy.app.viewer.PresenceCheck
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

sealed interface Route {
    data object Status : Route
    data object Live : Route
    data object Recordings : Route
    data object Events : Route
    data object Settings : Route
    data class Event(val id: Long) : Route
    data class Play(val segments: List<Segment>, val index: Int, val offsetMs: Long = 0) : Route
}

private val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)

/** Plays the whole day continuously starting from [seg]. */
suspend fun playRoute(api: Api, seg: Segment, offsetMs: Long = 0): Route.Play {
    val day = runCatching { api.segments(dayFmt.format(Date(seg.startedAt))) }.getOrDefault(emptyList()).sortedBy { it.startedAt }
    val idx = day.indexOfFirst { it.id == seg.id }
    return if (idx >= 0) Route.Play(day, idx, offsetMs) else Route.Play(listOf(seg), 0, offsetMs)
}

// ------------------------------------------------------------------------------------ status
@Composable
fun StatusScreen(api: Api, prefs: Prefs, nav: (Route) -> Unit) {
    var status by remember { mutableStateOf<Status?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    var active by remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { active = true; tick++ }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { active = false }
    LaunchedEffect(tick, active) {
        while (active) {
            try { status = api.status(); error = null } catch (e: Exception) { error = e.message }
            delay(5000)
        }
    }

    Scaffold(topBar = {
        Bar("eyeSpy") {
            IconButton(onClick = { tick++ }) { Icon(Icons.Default.Refresh, "Refresh") }
            IconButton(onClick = { nav(Route.Settings) }) { Icon(Icons.Default.Settings, "Settings") }
        }
    }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (error != null) Section {
                Text("Server unreachable", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                Text("Connect to your home Wi-Fi or VPN. Push notifications still arrive via ntfy.\n($error)",
                    style = MaterialTheme.typography.bodySmall)
            }
            val s = status
            if (s != null) {
                Section {
                    Text(if (s.armed) "ARMED" else "Disarmed", style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold, color = if (s.armed) Recording else MaterialTheme.colorScheme.onSurface)
                    Text(s.armReason.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyMedium)
                    Dot(if (s.recording) Recording else MaterialTheme.colorScheme.outline, if (s.recording) "Recording" else "Not recording")
                    Text("Mode", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("auto" to "Auto (presence)", "armed" to "Armed", "disarmed" to "Off").forEach { (m, l) ->
                            FilterChip(s.mode == m, {
                                scope.launch {
                                    try { status = Status.from(api.setMode(m)) } catch (e: Exception) { error = e.message }
                                }
                            }, { Text(l) })
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { nav(Route.Live) }, Modifier.weight(1f)) { Text("Live") }
                    OutlinedButton(onClick = { nav(Route.Recordings) }, Modifier.weight(1f)) { Text("Footage") }
                    OutlinedButton(onClick = { nav(Route.Events) }, Modifier.weight(1f)) { Text("Events") }
                }
                Section("Camera") {
                    val i = s.cameraInfo
                    Dot(if (s.cameraOnline) Online else Recording, if (s.cameraOnline) "Online" else "Offline",
                        if (!s.cameraOnline && s.cameraLastSeen > 0) "last seen ${fmtAgo(s.cameraLastSeen)}" else i.optString("model"))
                    if (i.has("battery")) Text(
                        "Battery ${i.optInt("battery")}%" + (if (i.optBoolean("charging")) " (charging)" else " (NOT charging)") +
                            " · ${i.optDouble("temp_c")}°C · pending uploads ${i.optInt("pending_uploads")}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text("Server storage: ${fmtBytes(s.storageBytes)} in ${s.segments} segments · ${fmtBytes(s.diskFree)} free",
                        style = MaterialTheme.typography.bodySmall)
                }
                Section("Presence") {
                    if (s.presence.isEmpty()) Text("No phones report presence yet. Enable it in Settings so the camera arms when everyone leaves.")
                    s.presence.forEach {
                        Dot(if (it.state == "home") Online else MaterialTheme.colorScheme.outline,
                            "${it.device}: ${it.state}", "${fmtAgo(it.ts)} via ${it.via}")
                    }
                    if (!prefs.presenceEnabled) TextButton(onClick = { nav(Route.Settings) }) { Text("Set up presence for this phone") }
                }
                if (!s.pushEnabled) Text("Push notifications are disabled on the server (NTFY_TOPIC not set).",
                    color = MaterialTheme.colorScheme.error)
            } else if (error == null) Text("Loading…")
        }
    }
}

// ------------------------------------------------------------------------------------ live
@Composable
fun LiveScreen(api: Api, onBack: () -> Unit) {
    Scaffold(topBar = { Bar("Live", onBack) }) { pad ->
        MjpegView(api, Modifier.padding(pad).fillMaxSize())
    }
}

// ------------------------------------------------------------------------------------ recordings
@Composable
fun RecordingsScreen(api: Api, nav: (Route) -> Unit, onBack: () -> Unit) {
    var days by remember { mutableStateOf<List<Day>>(emptyList()) }
    var day by remember { mutableStateOf<String?>(null) }
    var motionOnly by remember { mutableStateOf(false) }
    var segs by remember { mutableStateOf<List<Segment>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        try { days = api.days(); day = days.firstOrNull()?.day } catch (e: Exception) { error = e.message }
    }
    LaunchedEffect(day, motionOnly) {
        val d = day ?: return@LaunchedEffect
        segs = null
        try { segs = api.segments(d, motionOnly) } catch (e: Exception) { error = e.message }
    }
    Scaffold(topBar = { Bar("Footage", onBack) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(motionOnly, { motionOnly = !motionOnly }, { Text("Motion only") }) }
                items(days) { d -> FilterChip(day == d.day, { day = d.day }, { Text("${d.day.substring(5)} (${d.motion}/${d.segments})") }) }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            val list = segs
            when {
                days.isEmpty() && error == null -> Text("No footage yet.", Modifier.padding(16.dp))
                list == null -> Text("Loading…", Modifier.padding(16.dp))
                else -> LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.id }) { seg ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                // play the day in chronological order from this segment
                                nav(Route.Play(list.sortedBy { it.startedAt }, list.sortedBy { it.startedAt }.indexOf(seg)))
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            AuthImage(api, seg.thumb, Modifier.width(128.dp).aspectRatio(16f / 9f))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(fmtTime(seg.startedAt), style = MaterialTheme.typography.titleMedium)
                                Text("${seg.durationMs / 1000}s · ${fmtBytes(seg.size)}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (seg.hasMotion) Text("MOTION", color = Recording, style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.background(Recording.copy(alpha = 0.12f), RoundedCornerShape(4.dp)).padding(4.dp))
                        }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------------------------ events
private val eventLabels = mapOf(
    "motion" to "Motion detected", "recording_started" to "Recording started", "recording_stopped" to "Recording stopped",
    "armed" to "Armed", "disarmed" to "Disarmed", "presence" to "Presence", "camera_offline" to "Camera offline",
    "camera_connected" to "Camera connected", "camera_disconnected" to "Camera disconnected", "mode" to "Mode changed",
)

@Composable
fun EventsScreen(api: Api, nav: (Route) -> Unit, onBack: () -> Unit) {
    var all by remember { mutableStateOf(false) }
    var events by remember { mutableStateOf<List<EventItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(all) {
        events = null
        try {
            events = api.events(if (all) null else "motion,recording_started,recording_stopped,camera_offline,armed,disarmed")
        } catch (e: Exception) { error = e.message }
    }
    Scaffold(topBar = { Bar("Events", onBack) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(!all, { all = false }, { Text("Important") })
                FilterChip(all, { all = true }, { Text("Everything") })
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
            val list = events ?: return@Column Text("Loading…", Modifier.padding(16.dp))
            LazyColumn(contentPadding = PaddingValues(12.dp)) {
                items(list, key = { it.id }) { ev ->
                    Row(
                        Modifier.fillMaxWidth().clickable(enabled = ev.type == "motion") { nav(Route.Event(ev.id)) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (ev.snapshot != null) AuthImage(api, ev.snapshot, Modifier.size(72.dp, 54.dp))
                        else Box(Modifier.size(72.dp, 54.dp), contentAlignment = Alignment.Center) {
                            Box(Modifier.size(10.dp).background(if (ev.type == "motion" || ev.type == "camera_offline") Recording else Color.Gray, RoundedCornerShape(5.dp)))
                        }
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(eventLabels[ev.type] ?: ev.type, style = MaterialTheme.typography.titleSmall)
                            val d = ev.detail
                            val sub = when {
                                d.has("device") -> "${d.optString("device")} ${d.optString("state")} (${d.optString("via")})"
                                d.has("reason") -> d.optString("reason")
                                d.has("mode") -> d.optString("mode")
                                else -> ""
                            }
                            Text(fmtDateTime(ev.ts) + if (sub.isNotEmpty()) " · $sub" else "", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
fun EventScreen(api: Api, id: Long, nav: (Route) -> Unit, onBack: () -> Unit) {
    var ev by remember { mutableStateOf<EventItem?>(null) }
    var seg by remember { mutableStateOf<Segment?>(null) }
    var offset by remember { mutableStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(attempt) {
        try {
            val o = api.event(id)
            ev = EventItem.from(o)
            seg = o.optJSONObject("segment")?.let(Segment::from)
            offset = o.optLong("offset_ms")
            error = null
        } catch (e: Exception) { error = e.message }
    }
    Scaffold(topBar = { Bar("Motion", onBack) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            error?.let {
                Text("Can't reach the server: $it\nConnect to home Wi-Fi / VPN.", color = MaterialTheme.colorScheme.error)
                Button(onClick = { attempt++ }) { Text("Retry") }
            }
            ev?.let { e ->
                Text(fmtDateTime(e.ts), style = MaterialTheme.typography.titleMedium)
                if (e.snapshot != null) AuthImage(api, e.snapshot, Modifier.fillMaxWidth().aspectRatio(4f / 3f), crop = false)
                val s = seg
                if (s != null) Button(onClick = { scope.launch { nav(playRoute(api, s, offset)) } }, Modifier.fillMaxWidth()) {
                    Text("Play footage")
                } else {
                    Text("Footage is uploaded when the current segment ends (about a minute).", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = { attempt++ }, Modifier.fillMaxWidth()) { Text("Check again") }
                }
                OutlinedButton(onClick = { nav(Route.Live) }, Modifier.fillMaxWidth()) { Text("Live view") }
            }
        }
    }
}

// ------------------------------------------------------------------------------------ player
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(api: Api, route: Route.Play, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var current by remember { mutableIntStateOf(route.index) }
    val player = remember {
        val http = DefaultHttpDataSource.Factory().setDefaultRequestProperties(mapOf("Authorization" to "Bearer ${api.token}"))
        ExoPlayer.Builder(ctx).setMediaSourceFactory(DefaultMediaSourceFactory(http)).build().apply {
            setMediaItems(route.segments.map { MediaItem.Builder().setUri(api.url(it.video)).setMediaId(it.id.toString()).build() },
                route.index, route.offsetMs)
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { current = player.currentMediaItemIndex }
        }
        player.addListener(l)
        onDispose { player.removeListener(l); player.release() }
    }
    val seg = route.segments.getOrNull(current)
    Scaffold(topBar = { Bar(seg?.let { fmtDateTime(it.startedAt) } ?: "Footage", onBack) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            AndroidView(factory = { PlayerView(it).apply { this.player = player; setShowNextButton(true); setShowPreviousButton(true) } },
                modifier = Modifier.fillMaxWidth().weight(1f))
            Text(
                "Segment ${current + 1} of ${route.segments.size}" + if (seg?.hasMotion == true) " · motion" else "",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

// ------------------------------------------------------------------------------------ settings
@SuppressLint("MissingPermission")
@Composable
fun ViewerSettings(api: Api, prefs: Prefs, onReset: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf(prefs.presenceEnabled) }
    var hasHome by remember { mutableStateOf(prefs.hasHome) }
    var radius by remember { mutableIntStateOf(prefs.homeRadius) }
    var msg by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }
    val permsOk = refresh >= 0 && Presence.hasPermissions(ctx)

    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val fgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        refresh++
        if (res[Manifest.permission.ACCESS_FINE_LOCATION] == true && Build.VERSION.SDK_INT >= 29) {
            msg = "Now choose \"Allow all the time\" so presence works in the background."
            bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
    }
    fun requestPerms() {
        val l = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) l += Manifest.permission.POST_NOTIFICATIONS
        fgLauncher.launch(l.toTypedArray())
    }
    fun applyPresence(on: Boolean) {
        if (on) Presence.register(ctx) { err ->
            if (err == null) { prefs.presenceEnabled = true; enabled = true; msg = "Presence active." } else { msg = err; enabled = false }
        } else {
            Presence.unregister(ctx)
            prefs.presenceEnabled = false; enabled = false
            scope.launch { runCatching { api.delete("/api/presence/${Uri.encode(prefs.deviceName)}") } }
            msg = "Presence off. This phone no longer affects arming."
        }
    }

    Scaffold(topBar = { Bar("Settings", onBack) }) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section("Presence (auto arm/disarm)") {
                Text("The camera arms when every phone with presence enabled has left home, and disarms when one comes back.",
                    style = MaterialTheme.typography.bodySmall)
                if (!permsOk) Button(onClick = ::requestPerms, modifier = Modifier.fillMaxWidth()) { Text("Grant location (Allow all the time)") }
                OutlinedButton(enabled = permsOk || Presence.hasPermissions(ctx) ||
                    androidx.core.content.ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == 0,
                    modifier = Modifier.fillMaxWidth(),
                    onClick = {
                        msg = "Getting location…"
                        LocationServices.getFusedLocationProviderClient(ctx)
                            .getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, CancellationTokenSource().token)
                            .addOnSuccessListener { loc ->
                                if (loc == null) { msg = "No location fix, try again outside/near a window."; return@addOnSuccessListener }
                                prefs.homeLat = loc.latitude; prefs.homeLng = loc.longitude; prefs.hasHome = true; hasHome = true
                                PresenceCheck.learnHomeWifi(ctx, reset = true)
                                msg = "Home set (±${loc.accuracy.toInt()} m)."
                                if (enabled) applyPresence(true)
                            }
                            .addOnFailureListener { msg = "Location failed: ${it.message}" }
                    }) { Text(if (hasHome) "Reset home to current location" else "I'm at home: set home location") }
                Text("Radius: $radius m (100–200 m is reliable)")
                Slider(radius.toFloat(), { radius = (it / 25).toInt() * 25 }, valueRange = 100f..500f, onValueChangeFinished = {
                    prefs.homeRadius = radius; if (enabled) applyPresence(true)
                })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Use this phone (${prefs.deviceName})", Modifier.weight(1f))
                    Switch(enabled, { applyPresence(it) }, enabled = hasHome && permsOk)
                }
                prefs.lastPresence?.let {
                    Text("Last reported: $it, ${fmtAgo(prefs.lastPresenceTs)} (${prefs.lastPresenceVia})", style = MaterialTheme.typography.bodySmall)
                }
                if (enabled) Text(
                    "Checked every few hours" + (if (prefs.lastPresenceCheckTs > 0) ", last ${fmtAgo(prefs.lastPresenceCheckTs)}" else "") +
                        ". Home Wi-Fi access points learned: ${prefs.homeBssids.size}.",
                    style = MaterialTheme.typography.bodySmall,
                )
                prefs.presenceLog.take(3).forEach { line ->
                    Text(line.substringAfter('|'), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { scope.launch { Presence.report(ctx, "home"); msg = "Sent home (${prefs.lastPresenceVia})" } }) { Text("Report home") }
                    OutlinedButton(onClick = { scope.launch { Presence.report(ctx, "away"); msg = "Sent away (${prefs.lastPresenceVia})" } }) { Text("Report away") }
                }
                msg?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }

            Section("Notifications") {
                Text("Pushes are delivered by the free ntfy app, on any network. Subscribe once:", style = MaterialTheme.typography.bodySmall)
                Text("${prefs.ntfyServer}/${prefs.ntfyTopic.ifEmpty { "(not configured on server)" }}", style = MaterialTheme.typography.bodyMedium)
                Button(enabled = prefs.ntfyTopic.isNotEmpty(), modifier = Modifier.fillMaxWidth(), onClick = {
                    val host = Uri.parse(prefs.ntfyServer).host ?: "ntfy.sh"
                    try {
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("ntfy://$host/${prefs.ntfyTopic}")))
                    } catch (_: ActivityNotFoundException) {
                        Toast.makeText(ctx, "Install the ntfy app first", Toast.LENGTH_LONG).show()
                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=io.heckel.ntfy")))
                    }
                }) { Text("Subscribe in ntfy app") }
                OutlinedButton(modifier = Modifier.fillMaxWidth(), onClick = {
                    scope.launch {
                        msg = try { api.post("/api/test-notification"); "Test notification sent" } catch (e: Exception) { e.message }
                    }
                }) { Text("Send test notification") }
                Text("In ntfy, open the subscription's settings to pick sounds; tapping a notification opens eyeSpy.",
                    style = MaterialTheme.typography.bodySmall)
            }

            Section("Server") {
                Text(prefs.serverUrl)
                TextButton(onClick = { Presence.unregister(ctx); prefs.presenceEnabled = false; prefs.role = null; onReset() }) {
                    Text("Change role / server")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
