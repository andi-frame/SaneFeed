package app.sanefeed.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import app.sanefeed.AppGraph
import app.sanefeed.R
import app.sanefeed.core.Handles
import app.sanefeed.core.Platform
import app.sanefeed.data.BuzzerSyncWorker
import app.sanefeed.data.Settings
import app.sanefeed.health.PermissionState

@Composable
fun SettingsScreen(
    graph: AppGraph,
    permissions: PermissionState,
    onTurnOn: () -> Unit,
    onReport: () -> Unit,
    onReview: () -> Unit,
) {
    val settings by graph.settings.state.collectAsState()
    val counts by graph.counter.counts.collectAsState()
    var editingLimit by rememberSaveable { mutableStateOf<Platform?>(null) }
    var editingServer by rememberSaveable { mutableStateOf(false) }

    Page(stringResource(R.string.app_name), onBack = null) {
        SetupSection(graph, permissions, onTurnOn)

        SectionHeader(stringResource(R.string.section_today))
        Group {
            Platform.entries.forEachIndexed { i, platform ->
                if (i > 0) GroupDivider()
                val limit = settings.limits.getValue(platform)
                SettingRow(
                    title = platform.label,
                    summary = stringResource(R.string.today_count, counts[platform] ?: 0, limit),
                    onClick = { editingLimit = platform },
                    trailing = { Text(stringResource(R.string.today_limit, limit), color = MaterialTheme.colorScheme.primary) },
                )
            }
        }
        BodyText(stringResource(R.string.today_footer))

        SectionHeader(stringResource(R.string.section_feed))
        Group {
            SwitchRow(
                stringResource(R.string.block_reels_title),
                stringResource(R.string.block_reels_summary),
                settings.blockReels,
                graph.settings::setBlockReels,
            )
            GroupDivider()
            SwitchRow(
                stringResource(R.string.heuristic_title),
                stringResource(R.string.heuristic_summary),
                settings.heuristic,
                graph.settings::setHeuristic,
            )
            GroupDivider()
            SwitchRow(
                stringResource(R.string.chip_title),
                stringResource(R.string.chip_summary),
                settings.counterChip,
                graph.settings::setCounterChip,
            )
        }

        SectionHeader(stringResource(R.string.section_buzzers))
        Group {
            ServerListRow(graph)
            GroupDivider()
            SettingRow(
                title = stringResource(R.string.server_title),
                summary = settings.serverUrl.ifEmpty { stringResource(R.string.server_unset) },
                onClick = { editingServer = true },
            )
            GroupDivider()
            SettingRow(stringResource(R.string.report_title), stringResource(R.string.report_summary), onClick = onReport)
            GroupDivider()
            SettingRow(stringResource(R.string.review_title), stringResource(R.string.review_summary), onClick = onReview)
        }

        SectionHeader(stringResource(R.string.section_manual))
        ManualBlocklist(graph, settings.manual)
    }

    editingLimit?.let { platform ->
        LimitDialog(
            platform = platform,
            current = settings.limits.getValue(platform),
            onDismiss = { editingLimit = null },
            onSave = {
                graph.settings.setLimit(platform, it)
                editingLimit = null
            },
        )
    }
    if (editingServer) {
        ServerDialog(
            current = settings.serverUrl,
            onDismiss = { editingServer = false },
            onSave = {
                graph.settings.setServerUrl(it)
                editingServer = false
            },
        )
    }
}

@Composable
private fun ServerListRow(graph: AppGraph) {
    val context = LocalContext.current
    val list by graph.blocklist.list.collectAsState()
    val status by graph.blocklist.status.collectAsState()
    val work by remember { WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(BuzzerSyncWorker.ONE_OFF) }
        .collectAsState(emptyList())
    val state = work.firstOrNull()?.state

    val summary = when {
        state == WorkInfo.State.RUNNING -> stringResource(R.string.sync_running)
        state == WorkInfo.State.ENQUEUED -> stringResource(R.string.sync_waiting)
        status.lastSyncAt == 0L -> stringResource(R.string.sync_never)
        else -> pluralStringResource(
            R.plurals.sync_summary,
            list.size,
            list.size,
            DateUtils.formatDateTime(context, status.lastSyncAt, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME),
        )
    }
    SettingRow(
        title = stringResource(R.string.sync_title),
        summary = status.lastError?.let { stringResource(R.string.sync_failed, summary, it) } ?: summary,
        summaryColor = if (status.lastError != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        trailing = {
            TextButton(
                onClick = { BuzzerSyncWorker.syncNow(context, force = true) },
                enabled = state != WorkInfo.State.RUNNING,
            ) { Text(stringResource(R.string.sync_now)) }
        },
    )
}

@Composable
private fun ManualBlocklist(graph: AppGraph, manual: Map<Platform, Set<String>>) {
    var platform by rememberSaveable { mutableStateOf(Platform.X) }
    var input by rememberSaveable { mutableStateOf("") }
    var showError by rememberSaveable { mutableStateOf(false) }
    val handle = Handles.parse(platform, input)
    val add = {
        if (handle == null) {
            showError = true
        } else {
            graph.settings.addManual(platform, handle)
            input = ""
            showError = false
        }
    }

    Group {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PlatformPicker(platform, { platform = it }, Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        showError = false
                    },
                    modifier = Modifier.weight(1f),
                    label = { Text(stringResource(R.string.manual_handle_label)) },
                    singleLine = true,
                    isError = showError,
                    supportingText = if (showError) {
                        { Text(stringResource(handleErrorRes(platform))) }
                    } else null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { add() }),
                )
                TextButton(onClick = add, modifier = Modifier.padding(start = 8.dp)) {
                    Text(stringResource(R.string.manual_add))
                }
            }
        }
        val entries = Platform.entries.flatMap { p -> manual[p].orEmpty().sorted().map { p to it } }
        if (entries.isEmpty()) {
            GroupDivider()
            SettingRow(stringResource(R.string.manual_empty))
        }
        entries.forEach { (p, h) ->
            GroupDivider()
            SettingRow(
                title = stringResource(R.string.handle_format, h),
                summary = p.label,
                trailing = {
                    TextButton(onClick = { graph.settings.removeManual(p, h) }) {
                        Text(stringResource(R.string.manual_remove))
                    }
                },
            )
        }
    }
}

fun handleErrorRes(platform: Platform): Int = when (platform) {
    Platform.X -> R.string.handle_invalid_x
    Platform.INSTAGRAM -> R.string.handle_invalid_instagram
}

@Composable
private fun LimitDialog(platform: Platform, current: Int, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current.toString()) }
    val value = text.toIntOrNull()?.takeIf { it in Settings.MIN_LIMIT..Settings.MAX_LIMIT }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.limit_dialog_title, platform.label)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter(Char::isDigit).take(3) },
                label = { Text(stringResource(R.string.limit_dialog_label)) },
                singleLine = true,
                isError = value == null,
                supportingText = {
                    Text(stringResource(R.string.limit_dialog_range, Settings.MIN_LIMIT, Settings.MAX_LIMIT))
                },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        },
        confirmButton = {
            TextButton(onClick = { value?.let(onSave) }, enabled = value != null) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ServerDialog(current: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(current) }
    val valid = text.trim().let { it.startsWith("https://") || it.startsWith("http://") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.server_title)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(stringResource(R.string.server_label)) },
                singleLine = true,
                isError = !valid,
                supportingText = { Text(stringResource(R.string.server_help)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = valid) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
