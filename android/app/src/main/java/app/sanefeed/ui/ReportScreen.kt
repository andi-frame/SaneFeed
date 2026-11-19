package app.sanefeed.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.sanefeed.AppGraph
import app.sanefeed.R
import app.sanefeed.core.Handles
import app.sanefeed.core.Platform
import app.sanefeed.core.ReportRules
import app.sanefeed.core.ReportRules.ProofError
import app.sanefeed.core.ReportRules.ReasonError
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException

@Composable
fun ReportScreen(graph: AppGraph, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var platform by rememberSaveable { mutableStateOf(Platform.X) }
    var handle by rememberSaveable { mutableStateOf("") }
    var reason by rememberSaveable { mutableStateOf("") }
    var proofs by rememberSaveable { mutableStateOf(listOf("")) }
    // Errors appear for fields the user filled in, and for every field after a send attempt.
    var attempted by rememberSaveable { mutableStateOf(false) }
    var sending by rememberSaveable { mutableStateOf(false) }
    var result by rememberSaveable { mutableStateOf<String?>(null) }
    var failure by rememberSaveable { mutableStateOf<String?>(null) }

    val handleError = (attempted || handle.isNotBlank()) && Handles.parse(platform, handle) == null
    val reasonError = ReportRules.reasonError(reason).takeIf { attempted || reason.isNotBlank() }
    val proofErrors = ReportRules.proofErrors(proofs).mapIndexed { i, e -> e.takeIf { attempted || proofs[i].isNotBlank() } }

    val resources = LocalContext.current.resources
    val send = send@{
        attempted = true
        failure = null
        result = null
        val draft = ReportRules.draftOrNull(platform, handle, reason, proofs) ?: return@send
        sending = true
        scope.launch {
            try {
                val receipt = withContext(Dispatchers.IO) { graph.api().submitReport(draft) }
                graph.settings.addReportedCase(receipt.caseId)
                val message = if (receipt.appended) R.string.report_appended else R.string.report_sent
                result = resources.getString(message, receipt.caseId)
                handle = ""
                reason = ""
                proofs = listOf("")
                attempted = false
            } catch (e: IOException) {
                failure = e.message ?: resources.getString(R.string.error_network)
            } catch (_: JSONException) {
                failure = resources.getString(R.string.error_bad_response)
            } finally {
                sending = false
            }
        }
        Unit
    }

    Page(stringResource(R.string.report_title), onBack) {
        BodyText(stringResource(R.string.report_intro))
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PlatformPicker(platform, { platform = it }, Modifier.fillMaxWidth())
            OutlinedTextField(
                value = handle,
                onValueChange = { handle = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text(stringResource(R.string.manual_handle_label)) },
                singleLine = true,
                isError = handleError,
                supportingText = if (handleError) {
                    { Text(stringResource(handleErrorRes(platform))) }
                } else null,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = reason,
                onValueChange = { reason = it.take(ReportRules.REASON_MAX + 100) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp),
                label = { Text(stringResource(R.string.report_reason_label)) },
                isError = reasonError != null,
                supportingText = {
                    Row {
                        Text(
                            when (reasonError) {
                                ReasonError.TOO_SHORT -> stringResource(R.string.report_reason_short, ReportRules.REASON_MIN)
                                ReasonError.TOO_LONG -> stringResource(R.string.report_reason_long, ReportRules.REASON_MAX)
                                null -> stringResource(R.string.report_reason_help)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        Text(stringResource(R.string.report_reason_count, reason.trim().length, ReportRules.REASON_MAX))
                    }
                },
            )
            Text(stringResource(R.string.report_proofs_title), style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(R.string.report_proofs_help, ReportRules.PROOFS_MAX),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            proofs.forEachIndexed { i, url ->
                Row(verticalAlignment = Alignment.Top) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { value -> proofs = proofs.toMutableList().also { it[i] = value } },
                        modifier = Modifier.weight(1f),
                        label = { Text(stringResource(R.string.report_proof_label, i + 1)) },
                        placeholder = { Text("https://") },
                        singleLine = true,
                        isError = proofErrors[i] != null,
                        supportingText = proofErrors[i]?.let { error -> { Text(proofErrorText(error)) } },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    )
                    if (proofs.size > 1) {
                        TextButton(
                            onClick = { proofs = proofs.toMutableList().also { it.removeAt(i) } },
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp),
                        ) { Text(stringResource(R.string.manual_remove)) }
                    }
                }
            }
            if (proofs.size < ReportRules.PROOFS_MAX) {
                TextButton(onClick = { proofs = proofs + "" }) { Text(stringResource(R.string.report_add_proof)) }
            }
            failure?.let {
                Text(stringResource(R.string.report_failed, it), color = MaterialTheme.colorScheme.error)
            }
            result?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
            Button(onClick = send, enabled = !sending, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(if (sending) R.string.report_sending else R.string.report_send))
            }
        }
    }
}

@Composable
private fun proofErrorText(error: ProofError): String = when (error) {
    ProofError.EMPTY -> stringResource(R.string.proof_empty)
    ProofError.TOO_LONG -> stringResource(R.string.proof_too_long, ReportRules.PROOF_URL_MAX)
    ProofError.NOT_HTTPS -> stringResource(R.string.proof_not_https)
    ProofError.DUPLICATE -> stringResource(R.string.proof_duplicate)
}
