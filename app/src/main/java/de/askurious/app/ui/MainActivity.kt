package de.askurious.app.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.ui.text.style.TextOverflow
import java.time.LocalDate
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableLongStateOf
import android.text.format.DateUtils
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import de.askurious.app.R
import de.askurious.app.data.AnswerQueue
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import de.askurious.app.BuildConfig
import de.askurious.app.data.Api
import de.askurious.app.data.ApiError
import de.askurious.app.data.Quiet
import de.askurious.app.data.Study
import de.askurious.app.data.StudyStore
import de.askurious.app.data.Sync
import de.askurious.app.data.Trigger
import de.askurious.app.schedule.Alarms
import de.askurious.app.schedule.Schedule
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
fun DiaryTheme(content: @Composable () -> Unit) {
    val blue = Color(0xFF1F4E79)
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF9CC3E6)) else lightColorScheme(primary = blue)
    MaterialTheme(colorScheme = scheme, content = content)
}

class MainActivity : ComponentActivity() {
    private var linkCode by mutableStateOf<String?>(null)

    /** The code of the study whose details are shown, null on the start page. */
    private var detail by mutableStateOf<String?>(null)
    private var studies by mutableStateOf<List<Study>>(emptyList())

    /** Answers waiting to be sent, per study code (AnswerQueue); read again with [studies]. */
    private var waiting by mutableStateOf<Map<String, Int>>(emptyMap())

    /** Studies with an answer the server refused for today's limit, not yet seen by the participant (AnswerQueue). */
    private var refused by mutableStateOf<Set<String>>(emptySet())

    /** The phone's clock is off and answers given without a connection wait for it (AnswerQueue.Fate.CLOCK). */
    private var clockOff by mutableStateOf(false)

    private fun reload() {
        studies = StudyStore(this).all()
        val queue = AnswerQueue(this)
        val items = queue.all()
        waiting = items.groupingBy { it.study }.eachCount()
        refused = queue.refusedStudies()
        clockOff = items.isNotEmpty() && Api.clockSkew?.let { Math.abs(it) > AnswerQueue.MAX_SKEW_MS } == true
    }

    /** Opens [intent] of another app; a phone without an app for it simply gets nothing. */
    private fun openOther(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // nothing to open it with
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // the whole link: JoinScreen reads the code and the server from it (joinCode, joinServer)
        linkCode = intent?.data?.toString()?.takeIf { joinCode(it) != null }
        // not again when the activity is recreated (turning the phone): the entry was opened already
        if (savedInstanceState == null) intent?.let { report(it) }
        setContent { DiaryTheme { App() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.toString()?.takeIf { joinCode(it) != null }?.let { linkCode = it }
        report(intent)
    }

    /**
     * A home-screen shortcut (Shortcuts): opens the entry of its module while the study runs and the module may be
     * started today; otherwise the app just opens, on the details of the study when it is still on the phone.
     */
    private fun report(intent: Intent) {
        if (intent.action != Shortcuts.ACTION_REPORT) return
        val code = intent.getStringExtra(Shortcuts.EXTRA_STUDY)
        val target = Shortcuts.target(this, code, intent.getStringExtra(Shortcuts.EXTRA_MODULE))
        if (target != null) startActivity(EntryActivity.intent(this, target.first, target.second))
        else if (code != null && StudyStore(this).get(code) != null) detail = code
    }

    override fun onResume() {
        super.onResume()
        reload()
        // not while a sync runs, nor again within a few minutes (every return from an entry or a dialog resumes)
        lifecycleScope.launch {
            if (Sync.allIfDue(this@MainActivity) != null) reload()
        }
    }

    @Composable
    private fun App() {
        val code = linkCode
        var joining by remember { mutableStateOf(false) }
        var about by remember { mutableStateOf(false) }
        val shown = detail?.let { c -> studies.firstOrNull { it.code == c } }
        // the study went (answers deleted, removed from the list): back to the start page for good
        if (detail != null && shown == null) LaunchedEffect(detail) { detail = null }
        when {
            code != null -> JoinScreen(initial = code, onDone = { linkCode = null; studies = StudyStore(this).all() })
            joining -> JoinScreen(initial = "", onDone = { joining = false; studies = StudyStore(this).all() })
            about -> {
                BackHandler { about = false }
                AboutScreen(onBack = { about = false })
            }
            shown != null -> {
                BackHandler { detail = null }
                DetailScreen(shown, onBack = { detail = null })
            }
            else -> HomeScreen(onJoin = { joining = true }, onAbout = { about = true })
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun HomeScreen(onJoin: () -> Unit, onAbout: () -> Unit) {
        val context = LocalContext.current
        var askNotify by remember { mutableStateOf(false) }
        val notifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { askNotify = false }
        LaunchedEffect(studies.size) {
            askNotify = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && studies.isNotEmpty() &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        }
        Scaffold(topBar = {
            TopAppBar(title = { Logo() }, actions = {
                // an icon: the word mark of the logo needs the room (and the empty start page has the button with text)
                IconButton(onClick = onJoin) { Icon(Icons.Outlined.Add, contentDescription = stringResource(R.string.join_study)) }
                IconButton(onClick = onAbout) { Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.about)) }
            })
        }) { pad ->
            LazyColumn(
                Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item {
                    Text(
                        stringResource(R.string.my_studies), style = MaterialTheme.typography.titleLarge,
                        modifier = Modifier.padding(top = 4.dp).semantics { heading() },
                    )
                }
                if (askNotify) item {
                    Notice(stringResource(R.string.allow_notifications), Icons.Outlined.Notifications) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }
                if (studies.any { it.running } && !Alarms.canExact(context)) item {
                    Notice(stringResource(R.string.allow_exact), Icons.Outlined.Warning) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            openOther(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:$packageName".toUri()))
                        }
                    }
                }
                if (clockOff) item {
                    Card(Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.clock_off), Modifier.padding(16.dp))
                    }
                }
                if (studies.isEmpty()) item {
                    Column(Modifier.padding(top = 32.dp)) {
                        Text(stringResource(R.string.no_studies))
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onJoin) { Text(stringResource(R.string.join_study)) }
                    }
                }
                items(studies, key = { it.code }) { StudyCard(it) }
                item {
                    // which version runs on this phone (version, build time; server of a debug build)
                    Text(
                        buildInfo { v, t -> getString(R.string.build_info, v, t) },
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    /**
     * A permission the app needs (notifications, exact alarms): in the accent colour with an icon and a filled button,
     * at the top of the start page, so that it is not overlooked: without it no prompt arrives (on time).
     */
    @Composable
    private fun Notice(text: String, icon: ImageVector, onAllow: () -> Unit) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ),
            border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
        ) {
            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                    Button(onClick = onAllow) { Text(stringResource(R.string.allow)) }
                }
            }
        }
    }

    /**
     * Now, read again every 15 s for a fast-forward person (a study day passes in minutes there): the day and the next
     * question move while the study is shown.
     */
    @Composable
    private fun rememberTick(study: Study): Long {
        var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
        if (study.clock != null) LaunchedEffect(study.code) {
            while (true) {
                delay(15_000)
                tick = System.currentTimeMillis()
            }
        }
        return tick
    }

    /** "Day 3 of 14", "Day 3" of an open-ended study, or "Starts on ..." before the first day; null when unknown. */
    @Composable
    private fun dayText(study: Study, today: LocalDate): String? {
        val first = study.firstDay ?: return null
        val day = study.dayIndex(today) ?: return null
        val total = study.periodDays
        return when {
            today.isBefore(first) -> stringResource(R.string.starts_on, first.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
            total != null -> stringResource(R.string.day_of, day, total)
            study.openEnd -> stringResource(R.string.day_n, day)
            else -> null
        }
    }

    /** The next question of [study] at the real [tick], in the study's time, and for a fast-forward person the real one. */
    @Composable
    private fun NextPrompt(study: Study, tick: Long) {
        val fmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        Schedule.next(listOf(study), Instant.ofEpochMilli(tick)).firstOrNull()?.let {
            // shown in the study's time, like the day
            Text(stringResource(R.string.next_prompt, Instant.ofEpochMilli(it.due).atZone(ZoneId.systemDefault()).format(fmt)))
            // a fast-forward test person: when it really comes, small below
            if (study.clock != null) {
                val real = Instant.ofEpochMilli(it.ring).atZone(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
                val wait = DateUtils.getRelativeTimeSpanString(it.ring, tick, DateUtils.MINUTE_IN_MILLIS).toString()
                Text(
                    stringResource(R.string.next_prompt_real, real, wait),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    /** The state of a study that is not running or pending, as one sentence; null for active and pending. */
    @Composable
    private fun statusText(study: Study): String? = when (study.status) {
        "pending", "active" -> null
        "paused" -> stringResource(R.string.paused)
        "finished" -> stringResource(R.string.finished)
        "left" -> stringResource(if (study.leavePending) R.string.left_pending else R.string.left)
        else -> stringResource(R.string.ended)
    }

    /**
     * One study on the start page: title, the researchers' summary, the day, the next question and the buttons to answer
     * (the first questionnaire, reports the participant starts, the exit questionnaire). Tapping it opens the details
     * with the period, the settings and leaving (DetailScreen).
     */
    @Composable
    private fun StudyCard(study: Study) {
        val context = LocalContext.current
        val tick = rememberTick(study)
        // the study's date and time: virtual for a fast-forward person
        val today = study.today(real = tick)
        Card(onClick = { detail = study.code }, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(study.title, style = MaterialTheme.typography.titleMedium)
                if (study.summary.isNotBlank()) {
                    Text(study.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                FastForward(study)
                when (study.status) {
                    "pending" -> Button(onClick = { startActivity(EntryActivity.intent(context, study.code, "entry")) }) {
                        Text(stringResource(R.string.continue_entry))
                    }
                    "active" -> {
                        dayText(study, today)?.let { Text(it) }
                        NextPrompt(study, tick)
                        for (m in study.selfModules(study.dayIndex(today) ?: 0)) {
                            Button(onClick = { startActivity(EntryActivity.intent(context, study.code, m.key)) }) {
                                Text(m.selfTrigger?.label?.ifBlank { null } ?: m.name)
                            }
                        }
                    }
                    else -> statusText(study)?.let { Text(it) }
                }
                ExitButton(study)
                Waiting(study)
                TextButton(onClick = { detail = study.code }, contentPadding = PaddingValues(0.dp)) {
                    Text(stringResource(R.string.details))
                }
            }
        }
    }

    /** The mark of a fast-forward test person. */
    @Composable
    private fun FastForward(study: Study) {
        val clock = study.clock ?: return
        Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
            Text(
                stringResource(R.string.fast_forward, clock.factor),
                Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
        }
    }

    /** The exit questionnaire while it is open, with its last day. */
    @Composable
    private fun ExitButton(study: Study) {
        val context = LocalContext.current
        val exit = study.exit?.takeIf { it.open } ?: return
        Button(onClick = { startActivity(EntryActivity.intent(context, study.code, EntryActivity.EXIT)) }) {
            Text(exit.name.ifBlank { stringResource(R.string.exit_default) })
        }
        exit.until?.let {
            Text(
                stringResource(R.string.exit_until, it.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    /** Answers still waiting to be sent, and an answer refused for today's limit (with a button to close the note). */
    @Composable
    private fun Waiting(study: Study) {
        val context = LocalContext.current
        val n = waiting[study.code] ?: 0
        if (n > 0) Text(pluralStringResource(R.plurals.answers_waiting, n, n), style = MaterialTheme.typography.bodySmall)
        if (study.code in refused) {
            Text(stringResource(R.string.limit_refused), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = {
                AnswerQueue(context).clearRefused(study.code)
                reload()
            }) { Text(stringResource(R.string.close)) }
        }
    }

    /** What is asked in [study], one line per module, in the words of the join screen. */
    @Composable
    private fun moduleLines(study: Study): List<String> = study.modules.flatMap { m ->
        val lines = mutableListOf<String>()
        when (val t = m.trigger) {
            is Trigger.Self -> lines += stringResource(R.string.self_module, m.name)
            is Trigger.Fixed -> lines += timedLine(m.name, random = false, perDay = t.times.size * t.weekdays.size / 7.0)
            is Trigger.Random -> lines += timedLine(m.name, random = true, perDay = t.perDay * t.weekdays.size / 7.0)
        }
        if (m.selfStart != null) lines += stringResource(R.string.also_self_module, m.name)
        lines
    }

    /** "Mood: 3× a day (at random times)", or per week when less than once a day. */
    @Composable
    private fun timedLine(name: String, random: Boolean, perDay: Double): String =
        if (perDay >= 1) {
            val n = Math.round(perDay).toInt()
            pluralStringResource(if (random) R.plurals.random_per_day else R.plurals.fixed_per_day, n, name, n)
        } else {
            val n = Math.round(perDay * 7).toInt()
            pluralStringResource(if (random) R.plurals.random_per_week else R.plurals.fixed_per_week, n, name, n)
        }

    /**
     * The details of one study: the summary, its period (start, end or open-ended), what is asked, the next question,
     * the quiet hours, the notification sound, a home-screen shortcut for reports, and leaving or deleting the answers.
     * Leaving asks first, and then whether the answers should go too, with a kind word for keeping them.
     */
    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable
    private fun DetailScreen(study: Study, onBack: () -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var confirmLeave by remember { mutableStateOf(false) }
        var askErase by remember { mutableStateOf(false) }
        var confirmErase by remember { mutableStateOf(false) }
        var eraseError by remember { mutableStateOf<String?>(null) }
        var editQuiet by remember { mutableStateOf(false) }
        var shortcutFor by remember { mutableStateOf<de.askurious.app.data.Module?>(null) }
        val tick = rememberTick(study)
        val today = study.today(real = tick)
        val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

        fun erase() {
            eraseError = null
            scope.launch {
                try {
                    Sync.erase(context, study)
                } catch (e: ApiError) {
                    // the study stays: its secret is the only way to try again
                    eraseError = getString(if (e.key == "offline") R.string.offline else R.string.error)
                }
                reload()
            }
        }

        Scaffold(topBar = {
            TopAppBar(title = { Text(study.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, navigationIcon = {
                TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            })
        }) { pad ->
            Column(
                Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(study.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                if (study.summary.isNotBlank()) Text(study.summary)
                FastForward(study)
                statusText(study)?.let { Text(it, fontWeight = FontWeight.SemiBold) }

                Section(stringResource(R.string.period_title))
                val first = study.firstDay
                if (study.status == "pending" || first == null) {
                    Text(stringResource(R.string.period_after_entry))
                } else {
                    Text(stringResource(R.string.period_start, first.format(date)))
                    val last = study.lastDay
                    Text(
                        if (study.openEnd || last == null) stringResource(R.string.period_open_end)
                        else stringResource(R.string.period_end, last.format(date))
                    )
                    if (study.running) dayText(study, today)?.let { Text(it) }
                }
                if (study.running) NextPrompt(study, tick)

                val lines = moduleLines(study)
                if (lines.isNotEmpty()) {
                    Section(stringResource(R.string.study_modules))
                    lines.forEach { Text(it) }
                }

                if (study.hasRandom && study.running) {
                    Section(stringResource(R.string.quiet_title))
                    val quietNow = study.quietOn(today)
                    val quietNext = study.quietOn(today.plusDays(1))
                    Text(stringResource(R.string.quiet_now, quietText(quietNow)))
                    if (quietNext != quietNow) Text(stringResource(R.string.quiet_from_tomorrow, quietText(quietNext)))
                    OutlinedButton(onClick = { editQuiet = true }) { Text(stringResource(R.string.quiet_change)) }
                }

                // while questions or the exit questionnaire can still be notified
                if (Shortcuts.alive(study) || study.exit?.open == true) {
                    Section(stringResource(R.string.notifications_title))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(R.string.sound), Modifier.weight(1f))
                        Switch(checked = study.sound, onCheckedChange = { on ->
                            StudyStore(context).get(study.code)?.let { StudyStore(context).update(it.copy(sound = on)) }
                            reload()
                        })
                    }
                    Text(
                        stringResource(if (study.sound) R.string.sound_on_hint else R.string.sound_off_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                val reports = study.modules.filter { it.selfTrigger != null }
                if (study.running && reports.isNotEmpty() && Shortcuts.supported(context)) {
                    Section(stringResource(R.string.shortcut_title))
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

                Waiting(study)

                Section(stringResource(R.string.your_participation))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (study.status == "active" || study.status == "pending" || study.status == "paused") {
                        OutlinedButton(onClick = { confirmLeave = true }) { Text(stringResource(R.string.leave_study)) }
                    } else if (!study.leavePending) {
                        // a leave not yet told to the server keeps the study, so that the next sync can send it
                        OutlinedButton(onClick = {
                            // with its queued answers and kept pages: the participant takes the study off the phone
                            scope.launch {
                                Sync.forget(context, study.code)
                                reload()
                            }
                        }) { Text(stringResource(R.string.remove_from_list)) }
                    }
                    // every study still holds its secret, so its answers can be deleted, also after the end
                    TextButton(onClick = { eraseError = null; confirmErase = true }) {
                        Text(stringResource(R.string.erase_answers), color = MaterialTheme.colorScheme.error)
                    }
                }
                eraseError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Text(
                    stringResource(R.string.join_server, study.server.substringAfter("://")),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (editQuiet) {
            QuietDialog(
                // the dialog starts from the quiet hours that will count from tomorrow, the first day a change reaches
                initial = study.quietOn(today.plusDays(1)),
                onDismiss = { editQuiet = false },
                onSave = { quiet ->
                    editQuiet = false
                    scope.launch {
                        Sync.setQuiet(context, study, quiet)
                        reload()
                    }
                },
            )
        }
        if (confirmLeave) {
            AlertDialog(
                onDismissRequest = { confirmLeave = false },
                title = { Text(stringResource(R.string.leave_study)) },
                text = { Text(stringResource(R.string.leave_confirm)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmLeave = false
                        // shown as left at once, also while a sync runs; the server hears of it as soon as it can
                        Sync.markLeft(context, study)
                        reload()
                        scope.launch {
                            Sync.leave(context, study)
                            reload()
                        }
                        askErase = true
                    }) { Text(stringResource(R.string.leave_study)) }
                },
                dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
        if (askErase) {
            // after leaving: the answers stay unless the participant wants them gone; keeping them is the first choice
            AlertDialog(
                onDismissRequest = { askErase = false },
                title = { Text(stringResource(R.string.after_leave_title)) },
                text = { Text(stringResource(R.string.after_leave_text)) },
                confirmButton = { Button(onClick = { askErase = false }) { Text(stringResource(R.string.keep_answers)) } },
                dismissButton = {
                    TextButton(onClick = {
                        askErase = false
                        erase()
                    }) { Text(stringResource(R.string.erase_answers), color = MaterialTheme.colorScheme.error) }
                },
            )
        }
        if (confirmErase) {
            AlertDialog(
                onDismissRequest = { confirmErase = false },
                title = { Text(stringResource(R.string.erase_answers)) },
                text = { Text(stringResource(R.string.erase_confirm)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmErase = false
                        erase()
                    }) { Text(stringResource(R.string.erase), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirmErase = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
        shortcutFor?.let { m ->
            ShortcutDialog(study, m, onDone = { shortcutFor = null })
        }
    }

    /** A heading inside the details. */
    @Composable
    private fun Section(text: String) {
        Text(
            text, style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
        )
    }

    /**
     * The label of a new home-screen shortcut to report [module]: the report's label to begin with, never the study's
     * title, and the participant may change it, since anyone who sees the home screen reads it.
     */
    @Composable
    private fun ShortcutDialog(study: Study, module: de.askurious.app.data.Module, onDone: () -> Unit) {
        val context = LocalContext.current
        var label by remember { mutableStateOf(module.selfTrigger?.label?.ifBlank { null } ?: module.name.ifBlank { getString(R.string.shortcut_default) }) }
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

    /** "22:00–07:00" in the phone's time format, or "none". */
    @Composable
    private fun quietText(quiet: Quiet?): String =
        if (quiet == null) stringResource(R.string.quiet_none) else "${timeText(quiet.start)}–${timeText(quiet.end)}"

    private fun timeText(hhmm: String): String =
        LocalTime.parse(hhmm).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

    /**
     * The quiet hours of a study: from and to (the phone's time pickers) or none. They keep the random prompts out
     * of that time from tomorrow on; the fixed times of the researchers stay as they are.
     */
    @Composable
    private fun QuietDialog(initial: Quiet?, onDismiss: () -> Unit, onSave: (Quiet?) -> Unit) {
        val context = LocalContext.current
        var start by remember { mutableStateOf(initial?.start ?: "22:00") }
        var end by remember { mutableStateOf(initial?.end ?: "07:00") }

        fun pick(value: String, set: (String) -> Unit) {
            val t = LocalTime.parse(value)
            TimePickerDialog(
                context, { _, h, m -> set("%02d:%02d".format(Locale.ROOT, h, m)) }, t.hour, t.minute,
                android.text.format.DateFormat.is24HourFormat(context),
            ).show()
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.quiet_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.quiet_hint))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.quiet_from))
                        OutlinedButton(onClick = { pick(start) { start = it } }) { Text(timeText(start)) }
                        Text(stringResource(R.string.quiet_to))
                        OutlinedButton(onClick = { pick(end) { end = it } }) { Text(timeText(end)) }
                    }
                }
            },
            // the same time twice is no quiet time at all (the server takes it so): stored as none
            confirmButton = { TextButton(onClick = { onSave(if (start == end) null else Quiet(start, end)) }) { Text(stringResource(R.string.save)) } },
            dismissButton = {
                Row {
                    TextButton(onClick = { onSave(null) }) { Text(stringResource(R.string.quiet_none_button)) }
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                }
            },
        )
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun JoinScreen(initial: String, onDone: () -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var code by remember { mutableStateOf(joinCode(initial) ?: "") }
        // the server of the study: from its join link (QR code, App Link), else the app's own for a typed code
        var server by remember { mutableStateOf(Api.defaultServer) }
        var info by remember { mutableStateOf<JSONObject?>(null) }
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }
        var confirmRejoin by remember { mutableStateOf(false) }

        fun errorText(e: ApiError) = when (e.key) {
            "unknown" -> getString(R.string.not_found)
            "closed" -> getString(R.string.closed)
            "offline" -> getString(R.string.offline)
            // with the HTTP status: a 404 of a web server in between is told apart from an error of Askurious
            else -> getString(R.string.error) + if (e.status > 0) " (${e.status})" else ""
        }

        fun lookUp(c: String) {
            val clean = joinCode(c) ?: return
            val at = joinServer(c, BuildConfig.DEBUG) ?: Api.defaultServer
            code = clean
            server = at
            busy = true
            message = null
            scope.launch {
                try {
                    info = Api.join(at, clean)
                } catch (e: ApiError) {
                    message = errorText(e)
                }
                busy = false
            }
        }

        /**
         * Enrols with the join code of [info]. When the same study is on the phone already (the participant agreed to
         * join again), the earlier participation is closed first with its own secret (Sync.rejoin): its waiting answers
         * never go to the new person.
         */
        fun enrol(info: JSONObject) {
            busy = true
            message = null
            scope.launch {
                try {
                    val r = Api.enrol(server, code)
                    val secret = r.optString("secret").ifBlank { throw ApiError("error") }
                    val study = Study.fromJson(JSONObject(info.toString()).put("status", "pending").put("modules", org.json.JSONArray()), secret, server)
                    val old = StudyStore(context).get(study.code)
                    if (old != null) Sync.rejoin(context, old, study) else StudyStore(context).put(study)
                    startActivity(EntryActivity.intent(context, study.code, "entry"))
                    onDone()
                } catch (e: ApiError) {
                    message = errorText(e)
                } catch (e: org.json.JSONException) {
                    // the server's answer lacks something this app needs
                    message = getString(R.string.error)
                }
                busy = false
            }
        }

        val scanner = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let { lookUp(it) } }
        BackHandler(onBack = onDone)
        LaunchedEffect(initial) { if (initial.isNotBlank()) lookUp(initial) }

        Scaffold(topBar = {
            TopAppBar(title = { Text(stringResource(R.string.join_study)) }, navigationIcon = {
                TextButton(onClick = onDone) { Text(stringResource(R.string.back)) }
            })
        }) { pad ->
            Column(Modifier.padding(pad).padding(16.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val i = info
                if (i == null) {
                    OutlinedButton(onClick = {
                        scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false).setPrompt(""))
                    }) { Text(stringResource(R.string.scan_qr)) }
                    OutlinedTextField(
                        // lowercase only; the hyphen of a test code (k7qm2x-t3f9a) stays, joinCode removes spaces
                        value = code, onValueChange = { code = it.lowercase() },
                        label = { Text(stringResource(R.string.join_code)) }, singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            capitalization = KeyboardCapitalization.None,
                            autoCorrectEnabled = false,
                            keyboardType = KeyboardType.Ascii,
                        ),
                    )
                    Button(onClick = { lookUp(code) }, enabled = !busy && code.isNotBlank()) { Text(stringResource(R.string.look_up)) }
                } else {
                    Text(i.optString("title"), style = MaterialTheme.typography.titleLarge)
                    i.optString("description").takeIf { it.isNotBlank() }?.let { Text(it) }
                    val days = i.optInt("period_days")
                    // an open-ended study runs until the researchers end it
                    if (i.optBoolean("open_end", false) || days <= 0) Text(stringResource(R.string.duration_open_end))
                    else Text(pluralStringResource(R.plurals.study_days, days, days))
                    val modules = i.optJSONArray("modules")
                    if (modules != null && modules.length() > 0) {
                        Text(stringResource(R.string.study_modules), style = MaterialTheme.typography.titleSmall)
                        for (k in 0 until modules.length()) {
                            val m = modules.getJSONObject(k)
                            val name = m.optString("name")
                            Text(
                                when {
                                    m.optString("trigger") == "self" -> stringResource(R.string.self_module, name)
                                    m.optString("trigger") == "random" && m.optDouble("per_day", 0.0) >= 1 ->
                                        Math.round(m.optDouble("per_day")).toInt().let { n -> pluralStringResource(R.plurals.random_per_day, n, name, n) }
                                    m.optString("trigger") == "random" ->
                                        m.optInt("per_week").let { n -> pluralStringResource(R.plurals.random_per_week, n, name, n) }
                                    m.optDouble("per_day", 0.0) >= 1 -> m.optDouble("per_day").toInt().let { n ->
                                        pluralStringResource(R.plurals.fixed_per_day, n, name, n)
                                    }
                                    else -> m.optInt("per_week").let { n -> pluralStringResource(R.plurals.fixed_per_week, n, name, n) }
                                }
                            )
                            // a prompted module the participant may also start themselves
                            if (m.optString("trigger") != "self" && (m.optBoolean("self", false) || m.optJSONObject("self") != null)) {
                                Text(stringResource(R.string.also_self_module, name))
                            }
                        }
                    }
                    val minutes = i.optInt("minutes")
                    if (minutes > 0) Text(pluralStringResource(R.plurals.about_minutes, minutes, minutes))
                    Text(stringResource(R.string.join_privacy), style = MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.join_server, server.substringAfter("://")), style = MaterialTheme.typography.bodySmall)
                    Button(enabled = !busy, onClick = {
                        // the same study on this phone already: joining again replaces its secret, so ask first
                        if (StudyStore(context).get(i.optString("code")) != null) confirmRejoin = true else enrol(i)
                    }) { Text(stringResource(R.string.join)) }
                    TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) }
                }
                if (busy) CircularProgressIndicator()
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        val pendingInfo = info
        if (confirmRejoin && pendingInfo != null) {
            AlertDialog(
                onDismissRequest = { confirmRejoin = false },
                text = { Text(stringResource(R.string.rejoin_confirm)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmRejoin = false
                        enrol(pendingInfo)
                    }) { Text(stringResource(R.string.rejoin)) }
                },
                dismissButton = { TextButton(onClick = { confirmRejoin = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }
}
