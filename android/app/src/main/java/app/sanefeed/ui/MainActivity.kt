package app.sanefeed.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.sanefeed.AppGraph
import app.sanefeed.SaneFeedApp
import app.sanefeed.data.BuzzerSyncWorker
import app.sanefeed.health.PermissionState
import app.sanefeed.health.Permissions

class MainActivity : ComponentActivity() {
    private lateinit var graph: AppGraph
    private var permissions by mutableStateOf<PermissionState?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        graph = (application as SaneFeedApp).graph
        if (savedInstanceState == null) BuzzerSyncWorker.syncNow(this, force = false)
        permissions = Permissions.read(this)
        setContent {
            SaneFeedTheme {
                permissions?.let { App(graph, it, ::openAccessibilitySettings) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Everything here can change in Settings while SaneFeed is in the background.
        permissions = Permissions.read(this)
        graph.counter.refresh()
    }

    private fun openAccessibilitySettings() {
        startActivity(Permissions.accessibilitySettings())
    }
}

private enum class Screen { SETTINGS, DISCLOSURE, REPORT, REVIEW }

@Composable
private fun App(graph: AppGraph, permissions: PermissionState, openAccessibilitySettings: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf(Screen.SETTINGS) }
    val back = { screen = Screen.SETTINGS }
    BackHandler(enabled = screen != Screen.SETTINGS, onBack = back)
    when (screen) {
        Screen.SETTINGS -> SettingsScreen(
            graph = graph,
            permissions = permissions,
            onTurnOn = {
                if (graph.settings.state.value.disclosureAccepted) openAccessibilitySettings() else screen = Screen.DISCLOSURE
            },
            onReport = { screen = Screen.REPORT },
            onReview = { screen = Screen.REVIEW },
        )
        Screen.DISCLOSURE -> DisclosureScreen(
            onAgree = {
                graph.settings.acceptDisclosure()
                screen = Screen.SETTINGS
                openAccessibilitySettings()
            },
            onDecline = back,
        )
        Screen.REPORT -> ReportScreen(graph, onBack = back)
        Screen.REVIEW -> ReviewScreen(graph, onBack = back)
    }
}

/** Opens [url] in the browser; false when no app can handle it. */
fun Context.openLink(url: String): Boolean = try {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
    true
} catch (_: ActivityNotFoundException) {
    false
}
