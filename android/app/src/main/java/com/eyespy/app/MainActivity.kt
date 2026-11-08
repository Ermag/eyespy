package com.eyespy.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.eyespy.app.camera.CameraService
import com.eyespy.app.ui.CameraScreen
import com.eyespy.app.ui.EventScreen
import com.eyespy.app.ui.EventsScreen
import com.eyespy.app.ui.EyeSpyTheme
import com.eyespy.app.ui.LiveScreen
import com.eyespy.app.ui.PlayerScreen
import com.eyespy.app.ui.RecordingsScreen
import com.eyespy.app.ui.Route
import com.eyespy.app.ui.SetupScreen
import com.eyespy.app.ui.StatusScreen
import com.eyespy.app.ui.ViewerSettings

class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_AUTOSTART = "autostart"
    }

    private var deepLink by mutableStateOf<Route?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val prefs = Prefs(this)
        if (intent.getBooleanExtra(EXTRA_AUTOSTART, false) && prefs.role == "camera") {
            CameraService.start(this) // we're in the foreground now, so the camera service may start
            moveTaskToBack(true)
        }
        deepLink = parse(intent)
        setContent {
            EyeSpyTheme {
                var role by remember { mutableStateOf(prefs.role) }
                when (role) {
                    null -> SetupScreen(prefs) { role = prefs.role }
                    "camera" -> CameraScreen(prefs) { role = null }
                    else -> ViewerApp(prefs, deepLink, { deepLink = null }) { role = null }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        parse(intent)?.let { deepLink = it }
    }

    /** eyespy://event/123, eyespy://live, eyespy://status */
    private fun parse(i: Intent?): Route? {
        val uri = i?.data ?: return null
        if (uri.scheme != "eyespy") return null
        return when (uri.host) {
            "event" -> uri.lastPathSegment?.toLongOrNull()?.let { Route.Event(it) }
            "live" -> Route.Live
            "status" -> Route.Status
            else -> null
        }
    }
}

@Composable
private fun ViewerApp(prefs: Prefs, deepLink: Route?, consumed: () -> Unit, onReset: () -> Unit) {
    val api = remember(prefs.serverUrl, prefs.token) { Api(prefs) }
    val stack = remember { mutableStateListOf<Route>(Route.Status) }
    val nav: (Route) -> Unit = { stack.add(it) }
    val back: () -> Unit = { if (stack.size > 1) stack.removeAt(stack.lastIndex) }

    LaunchedEffect(deepLink) {
        if (deepLink != null) {
            while (stack.size > 1) stack.removeAt(stack.lastIndex)
            if (deepLink != Route.Status) stack.add(deepLink)
            consumed()
        }
    }
    BackHandler(enabled = stack.size > 1, onBack = back)

    when (val r = stack.last()) {
        Route.Status -> StatusScreen(api, prefs, nav)
        Route.Live -> LiveScreen(api, back)
        Route.Recordings -> RecordingsScreen(api, nav, back)
        Route.Events -> EventsScreen(api, nav, back)
        Route.Settings -> ViewerSettings(api, prefs, onReset, back)
        is Route.Event -> EventScreen(api, r.id, nav, back)
        is Route.Play -> PlayerScreen(api, r, back)
    }
}
