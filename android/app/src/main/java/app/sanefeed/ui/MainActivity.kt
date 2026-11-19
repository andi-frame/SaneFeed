package app.sanefeed.ui

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
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
import app.sanefeed.accessibility.SaneFeedAccessibilityService
import app.sanefeed.data.BuzzerSyncWorker

class MainActivity : ComponentActivity() {
    private lateinit var graph: AppGraph
    private var serviceEnabled by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        graph = (application as SaneFeedApp).graph
        if (savedInstanceState == null) BuzzerSyncWorker.syncNow(this, force = false)
        setContent {
            SaneFeedTheme {
                App(graph, serviceEnabled, ::openAccessibilitySettings)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        serviceEnabled = isServiceEnabled()
        graph.counter.refresh()
    }

    private fun isServiceEnabled(): Boolean {
        val manager = getSystemService(AccessibilityManager::class.java)
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
            val info = it.resolveInfo.serviceInfo
            info.packageName == packageName && info.name == SaneFeedAccessibilityService::class.java.name
        }
    }

    private fun openAccessibilitySettings() {
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}

private enum class Screen { SETTINGS, DISCLOSURE, REPORT, REVIEW }

@Composable
private fun App(graph: AppGraph, serviceEnabled: Boolean, openAccessibilitySettings: () -> Unit) {
    var screen by rememberSaveable { mutableStateOf(Screen.SETTINGS) }
    val back = { screen = Screen.SETTINGS }
    BackHandler(enabled = screen != Screen.SETTINGS, onBack = back)
    when (screen) {
        Screen.SETTINGS -> SettingsScreen(
            graph = graph,
            serviceEnabled = serviceEnabled,
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
