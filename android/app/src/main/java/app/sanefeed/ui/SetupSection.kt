package app.sanefeed.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.DateUtils
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sanefeed.AppGraph
import app.sanefeed.BuildConfig
import app.sanefeed.R
import app.sanefeed.core.Platform
import app.sanefeed.health.DeviceInfo
import app.sanefeed.health.DiagnosticReport
import app.sanefeed.health.HealthSnapshot
import app.sanefeed.health.PermissionState
import app.sanefeed.health.Permissions
import app.sanefeed.health.Verdict
import kotlinx.coroutines.delay

private enum class CheckState { DONE, NEEDED, RECOMMENDED, MANUAL }

/** Checklist of what the phone must allow, plus live per-app health from the running service. */
@Composable
fun SetupSection(graph: AppGraph, permissions: PermissionState, onTurnOn: () -> Unit) {
    val context = LocalContext.current
    val health by graph.health.state.collectAsState()
    // Ticks so "12 s ago" stays current while the screen is visible.
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(1_000)
            value = System.currentTimeMillis()
        }
    }
    val verdicts = Platform.entries.associateWith { Verdict.of(permissions.accessibilityEnabled, health, it) }

    SectionHeader(stringResource(R.string.section_setup))
    Text(
        overallLine(permissions, health, verdicts),
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp),
        style = MaterialTheme.typography.bodyLarge,
    )
    Group {
        val running = permissions.accessibilityEnabled && health.connectedAt > 0
        CheckRow(
            title = stringResource(R.string.check_accessibility_title),
            state = if (running) CheckState.DONE else CheckState.NEEDED,
            detail = stringResource(
                when {
                    running -> R.string.check_accessibility_done
                    permissions.accessibilityEnabled -> R.string.check_accessibility_not_connected
                    else -> R.string.check_accessibility_required
                },
            ),
            action = stringResource(R.string.action_open_settings).takeUnless { running },
            onAction = onTurnOn,
        ) {
            if (!permissions.accessibilityEnabled && permissions.restrictedSettingsHint) {
                Detail(stringResource(R.string.check_restricted_hint))
                ActionButton(stringResource(R.string.action_app_info), primary = false) {
                    context.startFirst(Permissions.appDetails(context))
                }
            }
        }
        GroupDivider()
        CheckRow(
            title = stringResource(R.string.check_battery_title),
            state = if (permissions.batteryUnrestricted) CheckState.DONE else CheckState.RECOMMENDED,
            detail = stringResource(if (permissions.batteryUnrestricted) R.string.check_battery_done else R.string.check_battery_needed),
            action = stringResource(R.string.action_open_settings).takeUnless { permissions.batteryUnrestricted },
            onAction = { context.startFirst(Permissions.batterySettings(), Permissions.appDetails(context)) },
        )
        if (permissions.autostartStep) {
            GroupDivider()
            CheckRow(
                title = stringResource(R.string.check_autostart_title),
                state = CheckState.MANUAL,
                detail = stringResource(R.string.check_autostart_body),
                action = stringResource(R.string.action_open_settings),
                onAction = { context.startFirst(Permissions.autostartSettings(), Permissions.appDetails(context)) },
            )
        }
    }
    Gap(12)
    Group {
        Platform.entries.forEachIndexed { i, platform ->
            if (i > 0) GroupDivider()
            val version = permissions.appVersions[platform]
            SettingRow(
                title = version?.let { stringResource(R.string.app_version, platform.label, it) } ?: platform.label,
                summary = if (version == null) {
                    stringResource(R.string.app_not_installed)
                } else {
                    verdictText(verdicts.getValue(platform), platform, version, now)
                },
                summaryColor = when (verdicts.getValue(platform)) {
                    is Verdict.Working -> MaterialTheme.colorScheme.primary
                    is Verdict.Error, is Verdict.NoPostsRecognized -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        GroupDivider()
        var copied by remember { mutableStateOf(false) }
        SettingRow(
            title = stringResource(R.string.diagnostics_title),
            summary = stringResource(if (copied) R.string.diagnostics_copied else R.string.diagnostics_summary),
            onClick = {
                copyReport(context, graph, permissions, health)
                copied = true
            },
        )
    }
}

@Composable
private fun CheckRow(
    title: String,
    state: CheckState,
    detail: String,
    action: String?,
    onAction: () -> Unit,
    extra: @Composable () -> Unit = {},
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            StateLabel(state)
        }
        Detail(detail)
        if (action != null) ActionButton(action, primary = state == CheckState.NEEDED, onClick = onAction)
        extra()
    }
}

@Composable
private fun StateLabel(state: CheckState) {
    val (text, color) = when (state) {
        CheckState.DONE -> R.string.state_done to MaterialTheme.colorScheme.primary
        CheckState.NEEDED -> R.string.state_needed to MaterialTheme.colorScheme.error
        CheckState.RECOMMENDED -> R.string.state_recommended to MaterialTheme.colorScheme.onSurfaceVariant
        CheckState.MANUAL -> R.string.state_manual to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(stringResource(text), style = MaterialTheme.typography.labelLarge, color = color)
}

@Composable
private fun Detail(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun ActionButton(text: String, primary: Boolean, onClick: () -> Unit) {
    val modifier = Modifier.padding(top = 8.dp)
    if (primary) {
        Button(onClick = onClick, modifier = modifier) { Text(text) }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { Text(text) }
    }
}

@Composable
private fun overallLine(permissions: PermissionState, health: HealthSnapshot, verdicts: Map<Platform, Verdict>): String {
    if (!permissions.accessibilityEnabled) return stringResource(R.string.setup_not_ready_off)
    if (health.connectedAt == 0L) return stringResource(R.string.setup_not_ready_not_connected)
    val installed = Platform.entries.filter { it in permissions.appVersions }
    if (installed.isEmpty()) return stringResource(R.string.setup_ready_no_apps)
    val parts = installed.map { platform ->
        val part = when (verdicts.getValue(platform)) {
            is Verdict.Working -> R.string.setup_part_working
            is Verdict.NoPostsRecognized -> R.string.setup_part_no_posts
            is Verdict.Error -> R.string.setup_part_error
            else -> R.string.setup_part_not_seen
        }
        stringResource(part, platform.label)
    }
    return stringResource(R.string.setup_ready, parts.joinToString(stringResource(R.string.setup_separator)))
}

@Composable
private fun verdictText(verdict: Verdict, platform: Platform, version: String, now: Long): String = when (verdict) {
    Verdict.Off -> stringResource(R.string.verdict_off)
    Verdict.NotConnected -> stringResource(R.string.verdict_not_connected)
    Verdict.NotSeenYet -> stringResource(R.string.verdict_not_seen, platform.label)
    is Verdict.NoPostsRecognized -> stringResource(R.string.verdict_no_posts, verdict.scans, platform.label, version)
    is Verdict.Working -> pluralStringResource(R.plurals.verdict_working, verdict.posts, verdict.posts, relative(verdict.lastScanAt, now))
    is Verdict.Error -> stringResource(R.string.verdict_error, relative(verdict.at, now), verdict.message)
}

private fun relative(at: Long, now: Long): String =
    DateUtils.getRelativeTimeSpanString(minOf(at, now), now, DateUtils.SECOND_IN_MILLIS).toString()

private fun copyReport(context: Context, graph: AppGraph, permissions: PermissionState, health: HealthSnapshot) {
    val settings = graph.settings.state.value
    val sync = graph.blocklist.status.value
    val now = System.currentTimeMillis()
    val report = DiagnosticReport.build(
        device = DeviceInfo(
            appVersion = BuildConfig.VERSION_NAME,
            buildType = BuildConfig.BUILD_TYPE,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            androidRelease = Build.VERSION.RELEASE,
            sdk = Build.VERSION.SDK_INT,
        ),
        permissions = permissions,
        health = health,
        settings = listOf(
            "Block Reels and video" to settings.blockReels.toString(),
            "Buzzer heuristic" to settings.heuristic.toString(),
            "Counter chip" to settings.counterChip.toString(),
            "Daily limits" to Platform.entries.joinToString { "${it.label} ${settings.limits.getValue(it)}" },
            "Server" to settings.serverUrl.ifEmpty { "not set" },
            "Server list" to "${graph.blocklist.list.value.size} accounts, synced ${DiagnosticReport.ago(now, sync.lastSyncAt)}",
            "Last sync error" to (sync.lastError ?: "none"),
        ),
        now = now,
    )
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText(context.getString(R.string.diagnostics_title), report))
}

/** Starts the first intent that resolves; OEM screens like MIUI's autostart list may be missing or protected. */
private fun Context.startFirst(vararg intents: Intent) {
    for (intent in intents) {
        try {
            startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
        } catch (_: SecurityException) {
        }
    }
}
