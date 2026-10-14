package de.askurious.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.CircularProgressIndicator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import de.askurious.app.R
import de.askurious.app.data.AnswerQueue
import de.askurious.app.data.Study
import de.askurious.app.data.TransmissionLog
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun Message(text: String, error: Boolean = false) {
    Text(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
}

@Composable
fun StudyInformation(study: Study, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.study_info)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(study.title, Modifier.semantics { heading() })
            if (study.summary.isNotBlank()) Text(study.summary)
            if (study.contact.isNotBlank()) Text(stringResource(R.string.study_contact, study.contact))
            if (study.openEnd) Text(stringResource(R.string.open_end))
            study.lastDay?.let { Text(stringResource(R.string.exit_until, it.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))) }
            for (module in study.modules) Text(module.name)
            Text(stringResource(R.string.join_privacy))
        }
    }, confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.close)) } })
}

@Composable
fun StoredTransmissions(study: Study, busy: Boolean, retry: () -> Unit, close: () -> Unit) {
    val context = LocalContext.current
    var data by remember(study.code) { mutableStateOf<Pair<List<AnswerQueue.Item>, List<JSONObject>>?>(null) }
    var failed by remember(study.code) { mutableStateOf(false) }
    LaunchedEffect(study.code, busy) {
        try {
            data = withContext(Dispatchers.IO) { Pair(AnswerQueue(context).of(study.code), TransmissionLog(context).of(study.code)) }
            failed = false
        } catch (e: java.io.IOException) { failed = true }
    }
    val loaded = data
    if (loaded != null && !failed) TransmissionDetails(study, loaded.first, loaded.second, busy, retry, close)
    else AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.transmissions)) },
        text = { if (failed) Text(stringResource(R.string.queue_unreadable)) else CircularProgressIndicator() },
        confirmButton = { TextButton(onClick = close) { Text(stringResource(R.string.close)) } })
}

@Composable
fun hasStoredReceipts(code: String, refresh: Any?): Boolean {
    val context = LocalContext.current
    var found by remember(code) { mutableStateOf(false) }
    LaunchedEffect(code, refresh) { found = withContext(Dispatchers.IO) { TransmissionLog(context).of(code).isNotEmpty() } }
    return found
}

@Composable
fun TransmissionDetails(study: Study, waiting: List<AnswerQueue.Item>, receipts: List<JSONObject>, busy: Boolean, retry: () -> Unit, close: () -> Unit) {
    fun date(ms: Long): String = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT))
    AlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.transmissions)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (waiting.isEmpty()) Message(stringResource(R.string.no_waiting))
            for (item in waiting) {
                Text(study.modules.firstOrNull { it.key == item.module }?.name ?: item.moduleName.ifBlank { item.module }, Modifier.semantics { heading() })
                Text(date(study.realAt(item.completed)))
                val label = when (item.error ?: item.refusal) {
                    "changed", "invalid", "expired", "limit", "daily_limit", "unknown", "clock", "paused", "closed", "unauthorised" -> R.string.transfer_review
                    "rate_limit" -> R.string.try_later
                    null -> R.string.transfer_waiting
                    else -> R.string.transfer_retry
                }
                Message(stringResource(label))
                item.attempted?.let { Text(stringResource(R.string.last_attempt, date(it))) }
            }
            for (receipt in receipts.reversed()) {
                Text(receipt.optString("label").ifBlank { study.modules.firstOrNull { it.key == receipt.optString("module") }?.name ?: receipt.optString("module") })
                Text(date(receipt.getLong("at")))
                Text(stringResource(if (receipt.optBoolean("review")) R.string.transfer_preserved else R.string.transfer_confirmed))
            }
        }
    }, confirmButton = { Button(onClick = retry, enabled = !busy) { Text(stringResource(R.string.try_transfer)) } },
        dismissButton = { TextButton(onClick = close) { Text(stringResource(R.string.close)) } })
}
