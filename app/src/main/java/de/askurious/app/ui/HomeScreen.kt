package de.askurious.app.ui

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import de.askurious.app.BuildConfig
import de.askurious.app.R
import de.askurious.app.data.Study

/**
 * The start page: the studies that still ask something, the ended ones below, and above them what keeps the prompts
 * from arriving. [selected]: the study whose page stands beside the list (window size class "expanded", UX audit
 * 2026-10-16, B34); its card is marked, and the snackbar of [say] shows on that page ([snackbar] null).
 */
@Composable
internal fun MainActivity.HomeScreen(
    onJoin: () -> Unit, onAbout: () -> Unit, onStudy: (Study) -> Unit, allowNotifications: () -> Unit, snackbar: SnackbarHostState?,
    say: (String) -> Unit, selected: String? = null,
) {
    val studies = model.studies
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        topBar = {
            // the heading takes the focus when the list shows (again), so TalkBack names the screen (B32); not while a
            // study's page beside it has it
            PageHeader(stringResource(R.string.app_name), onAbout = onAbout, announce = selected == null)
        },
        // the main action of the start page with its words, not a "+" in the bar (audit A21); the empty page has
        // its own button
        floatingActionButton = {
            if (studies.isNotEmpty()) ExtendedFloatingActionButton(
                onClick = onJoin,
                icon = { Icon(Icons.Outlined.Add, contentDescription = null) },
                text = { Text(stringResource(R.string.join_study)) },
            )
        },
        snackbarHost = { snackbar?.let { MessageHost(it) } },
    ) { pad ->
        val (now, ended) = grouped(studies) { it.today() }
        LazyColumn(
            // one column of at most 640 dp, in the middle of a wide screen (B34)
            Modifier.padding(pad).consumeWindowInsets(pad).fillMaxSize().pageWidth().padding(horizontal = 16.dp),
            // room below the last card for the button that floats over the list
            contentPadding = PaddingValues(bottom = 88.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.my_studies), style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 4.dp).semantics { heading() },
                )
            }
            // what keeps the prompts from arriving (on time), right below the heading and before the cards, in
            // view after a cold start also with many studies (audit A1, SA3; UX audit 2026-10-16, B1); nothing while
            // all is well
            if (studies.isNotEmpty()) item(key = "readiness") { Readiness(studies, allowNotifications) }
            if (studies.isNotEmpty()) item(key = "sync-status") {
                val success = model.syncState.first
                if (success > 0) Text(stringResource(R.string.sync_time, java.time.Instant.ofEpochMilli(success).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.SHORT))))
                if (model.syncState.second) Message(stringResource(R.string.sync_failed), error = true)
                TextButton(enabled = !model.refreshing, onClick = model::refresh) { Text(stringResource(R.string.refresh_studies)) }
            }
            // the wrong clock with the way to set it (B29)
            if (model.clockOff) item(key = "notice-clock") {
                Notice(stringResource(R.string.clock_off), Icons.Outlined.Warning, stringResource(R.string.date_settings_open)) { openDateSettings() }
            }
            // the queue of all studies cannot be read: said once here, not on every card (B10)
            if (model.queueUnreadable) item(key = "notice-queue") {
                Notice(stringResource(R.string.queue_unreadable), Icons.Outlined.Warning, stringResource(R.string.queue_discard), primary = false) {
                    confirmDiscard = true
                }
            }
            // a study's server needs a newer app (config DIARY_MIN_APP_VERSION): the app keeps working, so a
            // neutral notice below the ones that stop the prompts (B1)
            if (studies.any { it.needsUpdate(BuildConfig.VERSION_CODE) }) item(key = "notice-update") {
                Notice(stringResource(R.string.update_app), Icons.Outlined.Info, stringResource(R.string.update_button), error = false) {
                    // the Play Store opens this link itself; without it the browser shows the page
                    openOther(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$packageName".toUri()))
                }
            }
            if (studies.isEmpty() && model.loaded) item {
                Column(Modifier.padding(top = 32.dp)) {
                    Text(stringResource(R.string.no_studies))
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onJoin) { Text(stringResource(R.string.join_study)) }
                }
            }
            // running and not yet over on top, the ended ones under a heading of their own (audit A20); the version of
            // the app is no longer at the end of the list but under "About the app" (B30)
            items(now, key = { it.code }) { ListTheme(it.look) { StudyCard(it, it.code == selected, say) { onStudy(it) } } }
            if (ended.isNotEmpty()) item(key = "ended") {
                Text(
                    stringResource(R.string.ended_group), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 12.dp).semantics { heading() },
                )
            }
            items(ended, key = { it.code }) { ListTheme(it.look) { StudyCard(it, it.code == selected, say) { onStudy(it) } } }
        }
    }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.queue_discard)) },
            text = { GuardAgainstOverlays(); DialogText { Text(stringResource(R.string.queue_discard_confirm)) } },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    // a failure as a snackbar, read out like every other message of the page (B8)
                    model.discardQueue { ok -> if (!ok) say(getString(R.string.error)) }
                }) { Text(stringResource(R.string.queue_discard), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * The readiness of the phone for the prompts (audit SA3; UX audit 2026-10-16, B1): a notice in the error colours
 * right below the heading, with a button per missing permission, the first one filled; invisible while all is
 * well. Notifications count for every study, exact alarms only while one runs. The battery optimisation is
 * offered too while the notice shows, as an optional third point (B11); alone it raises no notice.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MainActivity.Readiness(studies: List<Study>, allowNotifications: () -> Unit) {
    val notify = notifyState != NotifyNotice.NONE
    val running = studies.any { it.running }
    val exact = running && !exactOn
    if (!notify && !exact) return
    NoticeCard {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.Notifications, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(R.string.reminders_setup), style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { heading() },
                )
                Text(stringResource(if (notify) R.string.ready_notify_text else R.string.ready_exact_text), fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.reminders_optional), style = MaterialTheme.typography.bodyMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // the permission that matters most is the filled button
                    if (notify) Button(onClick = allowNotifications) { Text(stringResource(R.string.notifications_open)) }
                    if (exact) {
                        if (notify) OutlinedButton(onClick = { openExactSettings() }) { Text(stringResource(R.string.alarms_open)) }
                        else Button(onClick = { openExactSettings() }) { Text(stringResource(R.string.alarms_open)) }
                    }
                    if (running && !batteryOn) TextButton(onClick = { openBatterySettings() }) { Text(stringResource(R.string.battery_open)) }
                }
            }
        }
    }
}

/**
 * A notice of the start page, unlike a study card (audit A20): in the error colours when the prompts or the
 * answers are at stake, else neutral with a border (an update the app can wait for; UX audit 2026-10-16, B1).
 */
@Composable
private fun NoticeCard(error: Boolean = true, content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface,
            contentColor = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurface,
        ),
        // the neutral one with the border of the cards, as the web pages draw it (B25)
        border = BorderStroke(1.dp, if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.outlineVariant),
    ) { Column { content() } }
}

@Composable
private fun NoticeRow(text: String, icon: ImageVector, button: String?, error: Boolean, primary: Boolean, onClick: () -> Unit) {
    Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(
            icon, contentDescription = null, modifier = Modifier.size(24.dp),
            tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = if (error) FontWeight.SemiBold else FontWeight.Normal)
            if (button != null) {
                if (error && primary) Button(onClick = onClick) { Text(button) } else OutlinedButton(onClick = onClick) { Text(button) }
            }
        }
    }
}

@Composable
private fun Notice(
    text: String, icon: ImageVector, button: String? = null, error: Boolean = true, primary: Boolean = true, onClick: () -> Unit = {},
) {
    NoticeCard(error) { NoticeRow(text, icon, button, error, primary, onClick) }
}

/**
 * A study on the start page (audit SA2): title, state with an icon, the next question and what to do now; the
 * rest (study period, modules, quiet hours, waiting answers, leaving and deleting) on its detail page, which the
 * card opens. TalkBack reads the card as one item, its buttons apart (audit A22), and names what a tap does: "Open
 * study" (UX audit 2026-10-16, B32). [selected]: its page stands beside the list (B34).
 */
@Composable
private fun MainActivity.StudyCard(study: Study, selected: Boolean, say: (String) -> Unit, onOpen: () -> Unit) {
    var transmissions by rememberSaveable { mutableStateOf(false) }
    val shape = CardDefaults.shape
    val open = stringResource(R.string.open_study)
    Card(
        // clickable with its label instead of Card(onClick), which takes none; clipped, so the ripple keeps the corners
        modifier = Modifier.fillMaxWidth().clip(shape).clickable(onClickLabel = open, onClick = onOpen),
        shape = shape,
        // in the study's look (StudyTheme): the page colour with its border, as the participant pages show it (B25)
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(study.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            // the researchers' sentence about the study (server: "summary")
            if (study.summary.isNotBlank()) {
                Text(study.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            StudyNow(study)
            // the transfers only while answers wait; the receipts of those sent are on the study's page (B26)
            val n = model.waiting[study.code] ?: 0
            if (n > 0) {
                Message(pluralStringResource(R.plurals.answers_waiting, n, n))
                // one such button per card: its name says whose transfers it opens (A08)
                val name = stringResource(R.string.transmissions_for, study.title)
                TextButton(onClick = { transmissions = true }, modifier = Modifier.semantics { contentDescription = name }) {
                    Text(stringResource(R.string.transmissions))
                }
            }
            if (study.code in model.refused) {
                Text(stringResource(R.string.limit_refused), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { model.clearRefused(study.code) }) { Text(stringResource(R.string.close)) }
            }
        }
    }
    if (transmissions) StoredTransmissions(study, model.transferring, { transmissions = false; transfer(study, say) }, { transmissions = false }, model::discardAnswer)
}
