package app.sanefeed.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.sanefeed.R

/** Prominent disclosure required before the user enables the accessibility service. */
@Composable
fun DisclosureScreen(onAgree: () -> Unit, onDecline: () -> Unit) {
    Page(stringResource(R.string.disclosure_title), onBack = onDecline) {
        BodyText(stringResource(R.string.disclosure_intro))
        DisclosureItem(R.string.disclosure_reads_title, R.string.disclosure_reads_body)
        DisclosureItem(R.string.disclosure_does_title, R.string.disclosure_does_body)
        DisclosureItem(R.string.disclosure_network_title, R.string.disclosure_network_body)
        DisclosureItem(R.string.disclosure_control_title, R.string.disclosure_control_body)
        Gap(24)
        Column(Modifier.padding(horizontal = 20.dp)) {
            Button(onClick = onAgree, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.disclosure_agree))
            }
            OutlinedButton(onClick = onDecline, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.disclosure_decline))
            }
        }
    }
}

@Composable
private fun DisclosureItem(title: Int, body: Int) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
        Text(
            stringResource(body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}
