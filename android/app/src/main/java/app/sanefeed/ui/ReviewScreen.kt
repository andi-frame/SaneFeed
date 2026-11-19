package app.sanefeed.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.sanefeed.AppGraph
import app.sanefeed.R
import app.sanefeed.core.ReportCase
import app.sanefeed.core.Vote
import app.sanefeed.data.ApiException
import java.io.IOException
import java.net.HttpURLConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONException

@Composable
fun ReviewScreen(graph: AppGraph, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val resources = LocalContext.current.resources
    val settings by graph.settings.state.collectAsState()
    val cases = remember { mutableStateListOf<ReportCase>() }
    var cursor by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf<String?>(null) }
    val caseErrors = remember { mutableStateMapOf<Long, String>() }
    val voting = remember { mutableStateMapOf<Long, Vote>() }

    fun errorText(e: Exception): String = when (e) {
        is IOException -> e.message ?: resources.getString(R.string.error_network)
        else -> resources.getString(R.string.error_bad_response)
    }

    fun load(reset: Boolean) {
        if (loading) return
        loading = true
        loadError = null
        scope.launch {
            try {
                val page = withContext(Dispatchers.IO) { graph.api().pendingCases(if (reset) null else cursor) }
                if (reset) {
                    cases.clear()
                    caseErrors.clear()
                }
                cases += page.cases.filter { new -> cases.none { it.id == new.id } }
                cursor = page.nextCursor
            } catch (e: IOException) {
                loadError = errorText(e)
            } catch (e: JSONException) {
                loadError = errorText(e)
            } finally {
                loading = false
            }
        }
    }

    fun vote(case: ReportCase, vote: Vote) {
        voting[case.id] = vote
        caseErrors -= case.id
        scope.launch {
            try {
                val tally = withContext(Dispatchers.IO) { graph.api().vote(case.id, vote) }
                val index = cases.indexOfFirst { it.id == case.id }
                if (index >= 0) cases[index] = cases[index].copy(agree = tally.agree, disagree = tally.disagree, myVote = tally.myVote)
            } catch (e: ApiException) {
                when (e.status) {
                    HttpURLConnection.HTTP_FORBIDDEN -> graph.settings.addReportedCase(case.id)
                    HttpURLConnection.HTTP_CONFLICT -> caseErrors[case.id] = resources.getString(R.string.review_closed)
                    else -> caseErrors[case.id] = errorText(e)
                }
            } catch (e: IOException) {
                caseErrors[case.id] = errorText(e)
            } catch (e: JSONException) {
                caseErrors[case.id] = errorText(e)
            } finally {
                voting -= case.id
            }
        }
    }

    LaunchedEffect(Unit) { load(reset = true) }

    Page(stringResource(R.string.review_title), onBack) {
        BodyText(stringResource(R.string.review_intro))
        Row(Modifier.padding(horizontal = 8.dp)) {
            TextButton(onClick = { load(reset = true) }, enabled = !loading) { Text(stringResource(R.string.review_refresh)) }
        }
        loadError?.let {
            Text(
                stringResource(R.string.review_load_failed, it),
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                color = MaterialTheme.colorScheme.error,
            )
        }
        when {
            cases.isEmpty() && loading -> BodyText(stringResource(R.string.review_loading))
            cases.isEmpty() && loadError == null -> BodyText(stringResource(R.string.review_empty))
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            cases.forEach { case ->
                CaseCard(
                    case = case,
                    ownReport = case.id in settings.reportedCases,
                    pendingVote = voting[case.id],
                    error = caseErrors[case.id],
                    onVote = { vote(case, it) },
                )
            }
        }
        if (cursor != null) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = { load(reset = false) }, enabled = !loading) {
                    Text(stringResource(if (loading) R.string.review_loading else R.string.review_more))
                }
            }
        }
    }
}

@Composable
private fun CaseCard(case: ReportCase, ownReport: Boolean, pendingVote: Vote?, error: String?, onVote: (Vote) -> Unit) {
    val context = LocalContext.current
    var linkError by remember { mutableStateOf(false) }
    Group {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.handle_format, case.handle),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(case.platform.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                pluralStringResource(R.plurals.review_stats, case.reporters, case.reporters, case.agree, case.disagree),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            case.proofs.groupBy { it.reason }.forEach { (reason, proofs) ->
                Text(reason, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 4.dp))
                proofs.forEach { proof ->
                    Text(
                        proof.url,
                        modifier = Modifier.fillMaxWidth().clickable { linkError = !context.openLink(proof.url) }.padding(vertical = 4.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        textDecoration = TextDecoration.Underline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (linkError) Text(stringResource(R.string.review_no_browser), color = MaterialTheme.colorScheme.error)
            if (ownReport) {
                Text(
                    stringResource(R.string.review_own),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                VoteButton(R.string.review_agree, Vote.AGREE, case.myVote, pendingVote, enabled = !ownReport, onVote)
                VoteButton(R.string.review_disagree, Vote.DISAGREE, case.myVote, pendingVote, enabled = !ownReport, onVote)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun VoteButton(label: Int, vote: Vote, myVote: Vote?, pending: Vote?, enabled: Boolean, onVote: (Vote) -> Unit) {
    val text = stringResource(if (pending == vote) R.string.review_sending else label)
    val active = enabled && pending == null
    if (myVote == vote) {
        Button(onClick = { onVote(vote) }, enabled = active) { Text(text) }
    } else {
        OutlinedButton(onClick = { onVote(vote) }, enabled = active) { Text(text) }
    }
}
