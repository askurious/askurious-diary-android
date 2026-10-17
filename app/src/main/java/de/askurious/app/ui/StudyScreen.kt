package de.askurious.app.ui

import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.text.BidiFormatter
import de.askurious.app.R
import de.askurious.app.data.AnswerQueue
import de.askurious.app.data.Module
import de.askurious.app.data.PromptLog
import de.askurious.app.data.Quiet
import de.askurious.app.data.Study
import de.askurious.app.data.Trigger
import de.askurious.app.schedule.Prompt
import de.askurious.app.schedule.Schedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale

/**
 * The detail page of a study (audit SA2): what the card shows, the researchers' summary, the study period (start,
 * end or open-ended), what is asked, the quiet hours, the notification sound, a home-screen shortcut for reports,
 * the waiting answers, and the final actions apart from the rest (leaving or removing, deleting the answers), each
 * asking first (A14, A15). After leaving the page says what becomes of the answers and offers keeping and
 * deleting them alike, without pressing either (UX audit 2026-10-16, B12). The title stands once, in the top bar
 * (B23).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MainActivity.StudyScreen(study: Study, onBack: () -> Unit, snackbar: SnackbarHostState, say: (String) -> Unit) {
    val context = LocalContext.current
    var askErase by remember { mutableStateOf(false) }
    var shortcutFor by remember { mutableStateOf<Module?>(null) }
    var confirmLeave by remember { mutableStateOf(false) }
    var confirmErase by remember { mutableStateOf(false) }
    var confirmRemove by rememberSaveable { mutableStateOf(false) }
    var transmissions by rememberSaveable { mutableStateOf(false) }
    var erasing by remember { mutableStateOf(false) }
    var eraseError by remember { mutableStateOf<String?>(null) }
    var editQuiet by remember { mutableStateOf(false) }
    val today = study.today(real = studyNow(study))
    BackScaffold(study.title, onBack, snackbar, titleLines = 3) { pad ->
        Column(
            pad.fillMaxSize().verticalScroll(rememberScrollState()).pageWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (study.summary.isNotBlank()) Text(study.summary)
            if (study.contact.isNotBlank()) Text(stringResource(R.string.study_contact, study.contact))
            StudyNow(study)
            if (study.code in model.refused) {
                Text(stringResource(R.string.limit_refused), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { model.clearRefused(study.code) }) { Text(stringResource(R.string.close)) }
            }
            val n = model.waiting[study.code] ?: 0
            if (n > 0) Message(pluralStringResource(R.plurals.answers_waiting, n, n))
            if (n > 0 || hasStoredReceipts(study.code, model.waiting)) TextButton(onClick = { transmissions = true }) {
                Text(stringResource(R.string.transmissions))
            }

            val first = study.firstDay
            val last = study.lastDay
            SectionTitle(stringResource(R.string.period_title))
            when {
                first == null -> Text(stringResource(R.string.period_after_entry))
                last != null && !study.openEnd -> Text(stringResource(R.string.period_from_to, dateText(first), dateText(last)))
                else -> {
                    // open-ended: it runs until the researchers end it
                    Text(stringResource(R.string.period_start, dateText(first)))
                    Text(stringResource(R.string.period_open_end))
                }
            }
            if (study.modules.isNotEmpty()) {
                SectionTitle(stringResource(R.string.study_modules))
                for (m in study.modules) {
                    BulletText(moduleText(m))
                    // a timed module the participant may also start themselves
                    if (m.selfStart != null) BulletText(stringResource(R.string.also_self_module, m.name))
                }
            }
            if (study.hasRandom) {
                SectionTitle(stringResource(R.string.quiet_title))
                val quietNow = study.quietOn(today)
                val quietNext = study.quietOn(today.plusDays(1))
                Text(stringResource(R.string.quiet_now, quietText(quietNow)))
                if (quietNext != quietNow) Text(stringResource(R.string.quiet_from_tomorrow, quietText(quietNext)))
                if (study.quietPending != null) Message(stringResource(R.string.quiet_pending))
                if (study.running) OutlinedButton(onClick = { editQuiet = true }) { Text(stringResource(R.string.quiet_change)) }
            }

            // the sound, while questions or the exit questionnaire can still be notified; kept only on the phone
            if (Shortcuts.alive(study) || study.exit?.open == true) {
                SectionTitle(stringResource(R.string.notifications_title))
                SwitchRow(stringResource(R.string.sound), study.sound) { model.setSound(study, it) }
                Text(
                    stringResource(if (study.sound) R.string.sound_on_hint else R.string.sound_off_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // a home-screen shortcut per module the participant starts themselves
            val reports = study.modules.filter { it.selfTrigger != null }
            if (study.running && reports.isNotEmpty() && Shortcuts.supported(context)) {
                SectionTitle(stringResource(R.string.shortcut_title))
                Text(stringResource(R.string.shortcut_intro), style = MaterialTheme.typography.bodySmall)
                for (m in reports) {
                    OutlinedButton(onClick = { shortcutFor = m }) {
                        Text(
                            if (reports.size == 1) stringResource(R.string.shortcut_add)
                            else stringResource(R.string.shortcut_add_for, m.selfTrigger?.label?.ifBlank { null } ?: m.name)
                        )
                    }
                }
            }

            SectionTitle(stringResource(R.string.participation_title))
            if (study.status == "active" || study.status == "pending" || study.status == "paused") {
                OutlinedButton(onClick = { confirmLeave = true }) { Text(stringResource(R.string.leave_study)) }
            } else if (!study.leavePending) {
                // a leave not yet told to the server keeps the study, so that the next sync can send it
                OutlinedButton(onClick = { confirmRemove = true }) { Text(stringResource(R.string.remove_from_list)) }
            }
            // every study still holds its secret, so its answers can be deleted, also after the end
            OutlinedButton(
                onClick = { eraseError = null; confirmErase = true },
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
            ) { Text(stringResource(R.string.erase_answers), color = MaterialTheme.colorScheme.error) }
            eraseError?.let { Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (editQuiet) {
        QuietDialog(
            // the dialog starts from the quiet hours that will count from tomorrow, the first day a change reaches
            initial = study.quietOn(today.plusDays(1)),
            onDismiss = { editQuiet = false },
            onSave = { quiet ->
                editQuiet = false
                model.setQuiet(study, quiet)
            },
        )
    }
    if (confirmLeave) {
        // the study in the title, the final button in the error colour, a word afterwards (audit A15)
        AlertDialog(
            onDismissRequest = { confirmLeave = false },
            title = { Text(stringResource(R.string.leave_title, study.title)) },
            text = {
                GuardAgainstOverlays()
                Text(stringResource(R.string.leave_confirm))
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmLeave = false
                    model.leave(study)
                    say(getString(R.string.left_done, study.title))
                    // then what becomes of the answers, both choices alike (B12)
                    askErase = true
                }) { Text(stringResource(R.string.leave_study), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    if (askErase) {
        AlertDialog(
            // not closed by a tap beside it while the server deletes
            onDismissRequest = { if (!erasing) askErase = false },
            title = { Text(stringResource(R.string.after_leave_title)) },
            text = {
                GuardAgainstOverlays()
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.after_leave_text))
                    if (erasing) Erasing()
                }
            },
            // keeping and deleting as equal choices, neither one pressed on the participant (B12)
            confirmButton = {
                TextButton(enabled = !erasing, onClick = {
                    erasing = true
                    // the question itself was the confirmation; the study stays after an error (its secret)
                    eraseError = null
                    model.erase(study) { e ->
                        erasing = false
                        askErase = false
                        if (e == null) {
                            say(getString(R.string.erased_done, study.title))
                            onBack()
                        } else {
                            eraseError = getString(if (e.key == "offline") R.string.offline else R.string.error)
                            say(eraseError!!)
                        }
                    }
                }) { Text(stringResource(R.string.erase_answers), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(enabled = !erasing, onClick = { askErase = false }) { Text(stringResource(R.string.keep_answers)) } },
        )
    }
    shortcutFor?.let { m -> ShortcutDialog(study, m, onDone = { shortcutFor = null }) }
    // "Transfer answers" closes the dialog; the result comes as a snackbar (B26)
    if (transmissions) StoredTransmissions(study, model.transferring, { transmissions = false; transfer(study, say) }, { transmissions = false }, model::discardAnswer)
    if (confirmRemove) {
        // the secret goes with the study: afterwards its answers can no longer be deleted from the phone (audit A4)
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(R.string.remove_title, study.title)) },
            text = {
                GuardAgainstOverlays()
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.remove_confirm))
                    val waiting = model.waiting[study.code] ?: 0
                    if (waiting > 0) Message(stringResource(R.string.remove_waiting, waiting), error = true)
                    if (study.exit?.open == true) {
                        Text(stringResource(R.string.remove_exit_open), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    // with its queued answers and kept pages: the participant takes the study off the phone
                    model.remove(study.code) { error ->
                        if (error != null) say(getString(R.string.error)) else {
                            say(getString(R.string.removed_done, study.title))
                            onBack()
                        }
                    }
                }) { Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { FlowRow {
                TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel)) }
                if ((model.waiting[study.code] ?: 0) > 0) TextButton(onClick = {
                    confirmRemove = false; transmissions = true; transfer(study, say)
                }) { Text(stringResource(R.string.try_transfer)) }
            } },
        )
    }
    if (confirmErase) {
        AlertDialog(
            // not closed by a tap beside it while the server deletes
            onDismissRequest = { if (!erasing) confirmErase = false },
            title = { Text(stringResource(R.string.erase_title, study.title)) },
            text = {
                GuardAgainstOverlays()
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.erase_confirm))
                    if (erasing) Erasing()
                }
            },
            confirmButton = {
                TextButton(enabled = !erasing, onClick = {
                    erasing = true
                    // the study stays after an error: its secret is the only way to try again
                    model.erase(study) { e ->
                        erasing = false
                        confirmErase = false
                        if (e == null) {
                            say(getString(R.string.erased_done, study.title))
                            onBack()
                        } else {
                            eraseError = getString(if (e.key == "offline") R.string.offline else R.string.error)
                            say(eraseError!!)
                        }
                    }
                }) { Text(stringResource(R.string.erase), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(enabled = !erasing, onClick = { confirmErase = false }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

/**
 * Sends the waiting answers now and says how it went as a snackbar (UX audit 2026-10-16, B26): how many of [study]'s
 * went, and how many still wait.
 */
internal fun MainActivity.transfer(study: Study, say: (String) -> Unit) = model.transfer(study.code) { sent, left ->
    val parts = mutableListOf<String>()
    if (sent > 0) parts += resources.getQuantityString(R.plurals.transfer_sent, sent, sent)
    if (left > 0) parts += if (sent == 0) getString(R.string.transfer_none) else resources.getQuantityString(R.plurals.answers_waiting, left, left)
    if (parts.isNotEmpty()) say(parts.joinToString(" "))
}

/** The study's date and time now, read again every 15 s for a fast-forward person (a day passes in minutes). */
@Composable
internal fun studyNow(study: Study): Long {
    var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(study.code) {
        while (true) {
            delay(15_000)
            tick = System.currentTimeMillis()
        }
    }
    return tick
}

/**
 * What the card and the detail page say alike: the fast-forward badge, the state with its icon, the next short
 * survey and the buttons for what is to do now (the entry questionnaire, a report, the exit questionnaire). Only
 * the most urgent of them is a filled button, further prompts are tonal and reports outlined (UX audit 2026-10-16,
 * B14).
 */
@Composable
internal fun MainActivity.StudyNow(study: Study) {
    val context = LocalContext.current
    val tick = studyNow(study)
    val today = study.today(real = tick)
    val state = studyState(study, today)
    study.clock?.let { clock ->
        // neutral, not a warning; read as "fast-forward, 144 times" (audit A19, A22)
        val spoken = stringResource(R.string.fast_forward_spoken, clock.factor)
        Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
            Text(
                stringResource(R.string.fast_forward, clock.factor),
                Modifier.padding(horizontal = 8.dp, vertical = 4.dp).clearAndSetSemantics { contentDescription = spoken },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    StateLine(study, state, today)
    if (state == StudyState.RUNNING) {
        var prompts by remember(study.code) { mutableStateOf<List<Pair<Prompt, Long?>>>(emptyList()) }
        var queueFailed by remember(study.code) { mutableStateOf(false) }
        LaunchedEffect(study, tick, model.waiting) {
            try {
                prompts = withContext(Dispatchers.IO) {
                    val log = PromptLog(context)
                    val queue = AnswerQueue(context)
                    Schedule.available(study, Instant.ofEpochMilli(tick)).filter {
                        !log.isCompleted(study.code, it.module, it.due) && !queue.has(study.code, it.module, it.due)
                    }.map { it to log.notifiedAt(study.code, it.module, it.due) }
                }
                queueFailed = false
            } catch (e: java.io.IOException) { prompts = emptyList(); queueFailed = true }
        }
        // the start page says it once for all studies (B10)
        if (queueFailed && !model.queueUnreadable) Text(stringResource(R.string.queue_unreadable))
        // the one that closes first on top; one without an end last
        val open = prompts.sortedBy { (p, _) -> if (p.expires > 0) p.due + p.expires * 60_000L else Long.MAX_VALUE }
        for ((k, pair) in open.withIndex()) {
            val (prompt, notified) = pair
            val module = study.modules.firstOrNull { it.key == prompt.module } ?: continue
            SectionTitle(module.name)
            if (prompt.expires > 0) Text(stringResource(R.string.prompt_until,
                whenText(Instant.ofEpochMilli(prompt.due + prompt.expires * 60_000L).atZone(ZoneId.systemDefault()), today, false)))
            val start = { startActivity(EntryActivity.intent(context, study.code, prompt.module, prompt.due, notified)) }
            if (k == 0) Button(onClick = start) { Text(stringResource(R.string.answer_prompt)) }
            else FilledTonalButton(onClick = start) { Text(stringResource(R.string.answer_prompt)) }
        }
        if (notifyState != NotifyNotice.NONE) {
            // without notifications no prompt reaches the participant: no time that would not come (audit A1);
            // so no line "Next short survey" either (B14)
            Text(stringResource(R.string.notifications_off), color = MaterialTheme.colorScheme.error)
        } else Schedule.next(listOf(study), Instant.ofEpochMilli(tick)).firstOrNull()?.let {
            // shown in the study's time, like the day; "about" while the alarm may ring minutes late (A11), "today",
            // "tomorrow" or the date (A25)
            val due = Instant.ofEpochMilli(it.due).atZone(ZoneId.systemDefault())
            Text(stringResource(R.string.next_prompt, whenText(due, today, approximate(exactOn))))
            // a fast-forward test person: when it really comes, small below
            if (study.clock != null) {
                val real = timeText(Instant.ofEpochMilli(it.ring).atZone(ZoneId.systemDefault()))
                val wait = DateUtils.getRelativeTimeSpanString(it.ring, tick, DateUtils.MINUTE_IN_MILLIS).toString()
                Text(
                    stringResource(R.string.next_prompt_real, real, wait),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        // the modules the participant starts themselves, also timed ones that may be started on an event
        for (m in study.selfModules(study.dayIndex(today) ?: 0)) {
            OutlinedButton(onClick = { startActivity(EntryActivity.intent(context, study.code, m.key)) }) {
                Text(m.selfTrigger?.label?.ifBlank { null } ?: m.name)
            }
        }
    }
    if (state == StudyState.PENDING) {
        Button(onClick = { startActivity(EntryActivity.intent(context, study.code, "entry")) }) { Text(stringResource(R.string.continue_entry)) }
    }
    study.exit?.takeIf { it.open }?.let { exit ->
        Button(onClick = { startActivity(EntryActivity.intent(context, study.code, EntryActivity.EXIT)) }) {
            Text(exit.name.ifBlank { stringResource(R.string.exit_default) })
        }
        // the state line names the last day already when the study period is over (EXIT_OPEN)
        if (state != StudyState.EXIT_OPEN) exit.until?.let {
            Text(stringResource(R.string.exit_until, dateText(it)), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The state of a study as one line with an icon, one item for TalkBack (audit A20, A22). */
@Composable
private fun StateLine(study: Study, state: StudyState, today: LocalDate) {
    val (icon, text) = when (state) {
        StudyState.PENDING -> Icons.Outlined.Edit to stringResource(R.string.state_pending)
        StudyState.BEFORE_START -> Icons.Outlined.DateRange to stringResource(R.string.starts_on, dateText(study.firstDay!!))
        StudyState.RUNNING -> Icons.Outlined.PlayArrow to
            (study.dayIndex(today)?.let { d ->
                // an open-ended study has no last day: "Day 12"
                study.periodDays?.let { stringResource(R.string.day_of, d, it) } ?: if (study.openEnd) stringResource(R.string.day_n, d) else null
            } ?: stringResource(R.string.state_running))
        StudyState.PERIOD_OVER -> Icons.Outlined.Refresh to stringResource(R.string.period_over)
        StudyState.PAUSED -> Icons.Outlined.Info to stringResource(R.string.paused)
        StudyState.EXIT_OPEN -> Icons.Outlined.Edit to (study.exit?.until?.let { stringResource(R.string.exit_open_until, dateText(it)) }
            ?: stringResource(R.string.exit_open))
        StudyState.FINISHED -> Icons.Outlined.CheckCircle to stringResource(R.string.finished)
        StudyState.LEFT -> Icons.AutoMirrored.Outlined.ExitToApp to stringResource(R.string.left)
        StudyState.LEFT_PENDING -> Icons.AutoMirrored.Outlined.ExitToApp to stringResource(R.string.left_pending)
        StudyState.ENDED -> Icons.Outlined.Done to stringResource(R.string.ended)
    }
    // the icon of 24 dp at the first line of the text (its line height is 24 sp): on the 4/8 dp grid (B24)
    Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * A point of a list (what is asked, the libraries): the bullet is drawn, not read out, and each point is an item of
 * its own for TalkBack (UX audit 2026-10-16, B31).
 */
@Composable
fun BulletText(text: String, style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("•", Modifier.clearAndSetSemantics {}, style = style)
        Text(text, style = style)
    }
}

/**
 * "today, 09:40", "tomorrow, 08:15" or "Wed, 14 Oct, 09:40", in the phone's time format (12 or 24 h; A25);
 * [about]: "today, about 09:40" (A11).
 */
internal fun Context.whenText(at: ZonedDateTime, today: LocalDate, about: Boolean): String {
    val time = timeText(at).let { if (about) getString(R.string.time_about, it) else it }
    return when (dayWord(at.toLocalDate(), today)) {
        DayWord.TODAY -> getString(R.string.today_at, time)
        DayWord.TOMORROW -> getString(R.string.tomorrow_at, time)
        DayWord.DATE -> getString(
            R.string.date_at,
            at.format(DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEdMMM"), Locale.getDefault())),
            time,
        )
    }
}

/** A time of day in the phone's own format: 24 h or 12 h as set on the phone, not by the language alone (A25). */
internal fun Context.timeText(at: ZonedDateTime): String = DateFormat.getTimeFormat(this).format(Date.from(at.toInstant()))

internal fun Context.timeText(hhmm: String): String = timeText(LocalDate.now().atTime(LocalTime.parse(hhmm)).atZone(ZoneId.systemDefault()))

internal fun dateText(day: LocalDate): String = day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

/**
 * "22:00–07:00" in the phone's time format, or "none". Each time isolated for the direction of the text (BidiFormatter),
 * so that a span with "PM" or Latin digits keeps its order in Arabic (UX audit 2026-10-16, B31).
 */
@Composable
private fun Context.quietText(quiet: Quiet?): String {
    if (quiet == null) return stringResource(R.string.quiet_none)
    val bidi = BidiFormatter.getInstance()
    return stringResource(R.string.time_span, bidi.unicodeWrap(timeText(quiet.start)), bidi.unicodeWrap(timeText(quiet.end)))
}

/** How often a module asks, as the join screen says it. */
@Composable
private fun moduleText(m: Module): String {
    val t = m.trigger
    if (t is Trigger.Self) return stringResource(R.string.self_module, t.label.ifBlank { m.name })
    val f = frequency(t) ?: return m.name
    val random = t is Trigger.Random
    return when {
        f.daily && random -> pluralStringResource(R.plurals.random_per_day, f.perDay, m.name, f.perDay)
        f.daily -> pluralStringResource(R.plurals.fixed_per_day, f.perDay, m.name, f.perDay)
        random -> pluralStringResource(R.plurals.random_per_week, f.perWeek, m.name, f.perWeek)
        else -> pluralStringResource(R.plurals.fixed_per_week, f.perWeek, m.name, f.perWeek)
    }
}

/** The progress of a deletion at the server, in the dialog that started it (B12). */
@Composable
private fun Erasing() {
    val busy = stringResource(R.string.erasing)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = busy })
        Text(busy)
    }
}

/**
 * A switch with its label as one row (UX audit 2026-10-16, B9): the whole row is the switch for touch and for
 * TalkBack ("Notification sound, switch, on"), at least 56 dp high.
 */
@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * The label of a new home-screen shortcut to report [module]: the report's label to begin with, never the study's
 * title, and the participant may change it, since anyone who sees the home screen reads it.
 */
@Composable
private fun ShortcutDialog(study: Study, module: Module, onDone: () -> Unit) {
    val context = LocalContext.current
    val fallback = stringResource(R.string.shortcut_default)
    var label by rememberSaveable { mutableStateOf(module.selfTrigger?.label?.ifBlank { null } ?: module.name.ifBlank { fallback }) }
    var failed by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(stringResource(R.string.shortcut_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.shortcut_hint))
                OutlinedTextField(
                    value = label, onValueChange = { label = it.take(40) }, singleLine = true,
                    label = { Text(stringResource(R.string.shortcut_label)) },
                )
                if (failed) Text(stringResource(R.string.shortcut_failed), color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            TextButton(enabled = label.isNotBlank(), onClick = {
                if (Shortcuts.request(context, study, module.key, label)) onDone() else failed = true
            }) { Text(stringResource(R.string.shortcut_create)) }
        },
        dismissButton = { TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) } },
    )
}

/**
 * The quiet hours of a study (audit A16): a switch "Use quiet hours" with from and to (the phone's time pickers),
 * saved only with "Save". They keep the random prompts out of that time from tomorrow on; the fixed times of the
 * researchers stay as they are.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuietDialog(initial: Quiet?, onDismiss: () -> Unit, onSave: (Quiet?) -> Unit) {
    val context = LocalContext.current
    var on by remember { mutableStateOf(initial != null) }
    var start by remember { mutableStateOf(initial?.start ?: "22:00") }
    var end by remember { mutableStateOf(initial?.end ?: "07:00") }

    fun pick(value: String, set: (String) -> Unit) {
        val t = LocalTime.parse(value)
        TimePickerDialog(
            context, { _, h, m -> set("%02d:%02d".format(Locale.ROOT, h, m)) }, t.hour, t.minute,
            DateFormat.is24HourFormat(context),
        ).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.quiet_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.quiet_hint))
                SwitchRow(stringResource(R.string.quiet_use), on) { on = it }
                if (on) {
                    val fromText = stringResource(R.string.quiet_from_cd, context.timeText(start))
                    val toText = stringResource(R.string.quiet_to_cd, context.timeText(end))
                    FlowRow(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.quiet_from))
                        // "Quiet hours from 23:00", not only "23:00" (audit A16)
                        OutlinedButton(onClick = { pick(start) { start = it } }, Modifier.semantics { contentDescription = fromText }) { Text(context.timeText(start)) }
                        Text(stringResource(R.string.quiet_to))
                        OutlinedButton(onClick = { pick(end) { end = it } }, Modifier.semantics { contentDescription = toText }) { Text(context.timeText(end)) }
                    }
                }
            }
        },
        // the same time twice is no quiet time at all (the server takes it so): stored as none
        confirmButton = {
            TextButton(onClick = { onSave(if (!on || start == end) null else Quiet(start, end)) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
