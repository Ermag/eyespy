package com.eyespy.app.ui

import android.graphics.BitmapFactory
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eyespy.app.Api
import com.eyespy.app.Net
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okio.BufferedSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.coroutineContext

val Recording = Color(0xFFE5483D)
val Online = Color(0xFF2F9E44)

@Composable
fun EyeSpyTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun Bar(title: String, onBack: (() -> Unit)? = null, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            if (onBack != null) IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        },
        actions = { actions() },
    )
}

@Composable
fun Section(title: String? = null, content: @Composable () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth(), colors = CardDefaults.elevatedCardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun Dot(color: Color, label: String, sub: String? = null) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
        if (sub != null) {
            Spacer(Modifier.width(6.dp))
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private val dateTimeFmt = SimpleDateFormat("EEE d MMM, HH:mm:ss", Locale.getDefault())
fun fmtTime(ms: Long): String = timeFmt.format(Date(ms))
fun fmtDateTime(ms: Long): String = dateTimeFmt.format(Date(ms))
fun fmtAgo(ms: Long): String {
    val s = (System.currentTimeMillis() - ms) / 1000
    return when {
        s < 60 -> "just now"; s < 3600 -> "${s / 60} min ago"; s < 86400 -> "${s / 3600} h ago"; else -> "${s / 86400} d ago"
    }
}
fun fmtBytes(b: Long): String = when {
    b >= 1L shl 30 -> "%.1f GB".format(b / 1073741824.0)
    b >= 1L shl 20 -> "%.0f MB".format(b / 1048576.0)
    else -> "${b / 1024} KB"
}

private val imageCache = LruCache<String, ImageBitmap>(80)

/** Image from the server with the auth header (thumbnails, snapshots). */
@Composable
fun AuthImage(api: Api, path: String?, modifier: Modifier = Modifier, crop: Boolean = true) {
    val img by produceState(path?.let { imageCache.get(it) }, path) {
        if (value == null && path != null) {
            value = withContext(Dispatchers.IO) {
                runCatching { api.bytes(path) }.getOrNull()
                    ?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
                    ?.also { imageCache.put(path, it) }
            }
        }
    }
    Box(modifier.background(Color.Black)) {
        img?.let {
            Image(it, null, Modifier.fillMaxSize(), contentScale = if (crop) ContentScale.Crop else ContentScale.Fit)
        }
    }
}

/** Live view: reads the server's MJPEG stream. */
@Composable
fun MjpegView(api: Api, modifier: Modifier = Modifier) {
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var status by remember { mutableStateOf("Connecting…") }
    LaunchedEffect(api) {
        withContext(Dispatchers.IO) {
            while (coroutineContext.isActive) {
                val call = Net.stream.newCall(api.req("/api/live.mjpeg").build())
                val job = coroutineContext[kotlinx.coroutines.Job]
                val handle = job?.invokeOnCompletion { call.cancel() }
                try {
                    call.execute().use { resp ->
                        if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                        status = "Waiting for camera…"
                        readMjpeg(resp.body!!.source()) { bytes ->
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { frame = it.asImageBitmap(); status = "" }
                        }
                    }
                } catch (e: Exception) {
                    if (!coroutineContext.isActive) break
                    status = "No live video (${e.message ?: "error"}) – retrying"
                    delay(2000)
                } finally {
                    handle?.dispose()
                }
            }
        }
    }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        frame?.let { Image(it, "Live", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
        if (status.isNotEmpty()) Text(status, color = Color.White, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
    }
}

private fun readMjpeg(src: BufferedSource, onFrame: (ByteArray) -> Unit) {
    while (true) {
        var len = -1
        while (true) {
            val line = src.readUtf8LineStrict()
            if (line.isEmpty()) {
                if (len >= 0) break else continue
            }
            if (line.startsWith("Content-Length", ignoreCase = true)) len = line.substringAfter(':').trim().toInt()
        }
        onFrame(src.readByteArray(len.toLong()))
    }
}
