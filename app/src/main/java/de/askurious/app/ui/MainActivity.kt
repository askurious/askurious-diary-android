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
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
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
    private var studies by mutableStateOf<List<Study>>(emptyList())

    /** Answers waiting to be sent, per study code (AnswerQueue); read again with [studies]. */
    private var waiting by mutableStateOf<Map<String, Int>>(emptyMap())

    /** Studies with an answer the server refused for today's limit, not yet seen by the participant (AnswerQueue). */
    private var refused by mutableStateOf<Set<String>>(emptySet())

    /** The phone's clock is off and answers given without a connection wait for it (AnswerQueue.Fate.CLOCK). */
    private var clockOff by mutableStateOf(false)

    /** The notice for notifications (none, the system dialog, the app's settings); read again in [onResume]. */
    private var notifyState by mutableStateOf(NotifyNotice.NONE)

    /** Exact alarms are allowed ("Alarms & reminders"); read again in [onResume], also after the system settings. */
    private var exactOn by mutableStateOf(true)

    private val uiPrefs get() = getSharedPreferences(UI_PREFS, MODE_PRIVATE)

    /**
     * Reads the permissions again: on every return to the app (from the system settings, the permission dialog, an
     * entry), so that a notice goes as soon as the permission is there and comes back when it was taken away.
     */
    private fun readPermissions() {
        val manager = NotificationManagerCompat.from(this)
        // the channel of the prompts switched off counts like notifications off
        val channelOn = manager.getNotificationChannelCompat(Alarms.CHANNEL)?.importance != NotificationManagerCompat.IMPORTANCE_NONE
        val canAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        notifyState = notifyNotice(
            enabled = manager.areNotificationsEnabled() && channelOn,
            canAsk = canAsk,
            granted = !canAsk || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            asked = uiPrefs.getBoolean(NOTIFY_ASKED, false),
            rationale = canAsk && shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS),
        )
        exactOn = Alarms.canExact(this)
    }

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
        setContent { DiaryTheme { App() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.toString()?.takeIf { joinCode(it) != null }?.let { linkCode = it }
    }

    override fun onResume() {
        super.onResume()
        reload()
        readPermissions()
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
        when {
            code != null -> JoinScreen(initial = code, onDone = { linkCode = null; studies = StudyStore(this).all() })
            joining -> JoinScreen(initial = "", onDone = { joining = false; studies = StudyStore(this).all() })
            about -> {
                BackHandler { about = false }
                AboutScreen(onBack = { about = false })
            }
            else -> HomeScreen(onJoin = { joining = true }, onAbout = { about = true })
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun HomeScreen(onJoin: () -> Unit, onAbout: () -> Unit) {
        // the notice stays after a denial: readPermissions() decides whether the dialog can still come (audit A2)
        val notifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { readPermissions() }
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
                // right below the heading (the first item), so that it is in view after a cold start (audit A1)
                if (studies.isNotEmpty() && notifyState != NotifyNotice.NONE) item(key = "notice-notify") {
                    val settings = notifyState == NotifyNotice.SETTINGS
                    Notice(
                        stringResource(R.string.allow_notifications), Icons.Outlined.Notifications,
                        stringResource(if (settings) R.string.allow_in_settings else R.string.allow),
                    ) {
                        if (settings) {
                            // denied for good (or switched off): the system shows no dialog any more
                            openOther(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
                        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            uiPrefs.edit { putBoolean(NOTIFY_ASKED, true) }
                            notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }
                if (studies.any { it.running } && !exactOn) item(key = "notice-exact") {
                    Notice(stringResource(R.string.allow_exact), Icons.Outlined.Warning, stringResource(R.string.allow)) {
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
    private fun Notice(text: String, icon: ImageVector, button: String, onAllow: () -> Unit) {
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
                    Button(onClick = onAllow) { Text(button) }
                }
            }
        }
    }

    @OptIn(ExperimentalLayoutApi::class)
    @Composable
    private fun StudyCard(study: Study) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var confirmLeave by remember { mutableStateOf(false) }
        var confirmErase by remember { mutableStateOf(false) }
        var confirmRemove by remember { mutableStateOf(false) }
        var eraseError by remember { mutableStateOf<String?>(null) }
        var editQuiet by remember { mutableStateOf(false) }
        // now, read again every 15 s for a fast-forward person (a study day passes in minutes there): the day and the
        // next question move while the card is shown
        var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
        if (study.clock != null) LaunchedEffect(study.code) {
            while (true) {
                delay(15_000)
                tick = System.currentTimeMillis()
            }
        }
        // the study's date and time: virtual for a fast-forward person
        val today = study.today(real = tick)
        val fmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(study.title, style = MaterialTheme.typography.titleMedium)
                study.clock?.let { clock ->
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = MaterialTheme.shapes.small) {
                        Text(
                            stringResource(R.string.fast_forward, clock.factor),
                            Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }
                when (study.status) {
                    "pending" -> Button(onClick = { startActivity(EntryActivity.intent(context, study.code, "entry")) }) {
                        Text(stringResource(R.string.continue_entry))
                    }
                    "active" -> {
                        val first = study.firstDay
                        val day = study.dayIndex(today)
                        val total = study.periodDays
                        if (first != null && today.isBefore(first)) {
                            Text(stringResource(R.string.starts_on, first.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))))
                        } else if (day != null && total != null) {
                            Text(stringResource(R.string.day_of, day, total))
                        }
                        if (notifyState != NotifyNotice.NONE) {
                            // without notifications no prompt reaches the participant: no time that would not come (audit A1)
                            Text(stringResource(R.string.notifications_off), color = MaterialTheme.colorScheme.error)
                        } else Schedule.next(listOf(study), Instant.ofEpochMilli(tick)).firstOrNull()?.let {
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
                        if (study.hasRandom) {
                            val quietNow = study.quietOn(today)
                            val quietNext = study.quietOn(today.plusDays(1))
                            Text(stringResource(R.string.quiet_now, quietText(quietNow)), style = MaterialTheme.typography.bodySmall)
                            if (quietNext != quietNow) {
                                Text(stringResource(R.string.quiet_from_tomorrow, quietText(quietNext)), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        val dayNow = day ?: 0
                        for (m in study.modules) {
                            val t = m.trigger as? Trigger.Self ?: continue
                            if (dayNow < m.dayFrom || dayNow > m.dayTo) continue
                            Button(onClick = { startActivity(EntryActivity.intent(context, study.code, m.key)) }) {
                                Text(t.label.ifBlank { m.name })
                            }
                        }
                    }
                    "paused" -> Text(stringResource(R.string.paused))
                    "finished" -> Text(stringResource(R.string.finished))
                    "left" -> Text(stringResource(if (study.leavePending) R.string.left_pending else R.string.left))
                    else -> Text(stringResource(R.string.ended))
                }
                study.exit?.takeIf { it.open }?.let { exit ->
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
                val n = waiting[study.code] ?: 0
                if (n > 0) Text(pluralStringResource(R.plurals.answers_waiting, n, n), style = MaterialTheme.typography.bodySmall)
                if (study.code in refused) {
                    Text(stringResource(R.string.limit_refused), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        AnswerQueue(context).clearRefused(study.code)
                        reload()
                    }) { Text(stringResource(R.string.close)) }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (study.running && study.hasRandom) {
                        TextButton(onClick = { editQuiet = true }) { Text(stringResource(R.string.quiet_change)) }
                    }
                    if (study.status == "active" || study.status == "pending" || study.status == "paused") {
                        TextButton(onClick = { confirmLeave = true }) { Text(stringResource(R.string.leave_study)) }
                    } else if (!study.leavePending) {
                        // a leave not yet told to the server keeps the study, so that the next sync can send it
                        TextButton(onClick = { confirmRemove = true }) { Text(stringResource(R.string.remove_from_list)) }
                    }
                    // every study still holds its secret, so its answers can be deleted, also after the end
                    TextButton(onClick = { eraseError = null; confirmErase = true }) {
                        Text(stringResource(R.string.erase_answers), color = MaterialTheme.colorScheme.error)
                    }
                }
                eraseError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
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
                    }) { Text(stringResource(R.string.leave_study)) }
                },
                dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
        if (confirmRemove) {
            // the secret goes with the study: afterwards its answers can no longer be deleted from the phone (audit A4)
            AlertDialog(
                onDismissRequest = { confirmRemove = false },
                title = { Text(stringResource(R.string.remove_title, study.title)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.remove_confirm))
                        if (study.exit?.open == true) {
                            Text(stringResource(R.string.remove_exit_open), color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        confirmRemove = false
                        // with its queued answers and kept pages: the participant takes the study off the phone
                        scope.launch {
                            Sync.forget(context, study.code)
                            reload()
                        }
                    }) { Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel)) } },
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
                        scope.launch {
                            try {
                                Sync.erase(context, study)
                            } catch (e: ApiError) {
                                // the study stays: its secret is the only way to try again
                                eraseError = getString(if (e.key == "offline") R.string.offline else R.string.error)
                            }
                            reload()
                        }
                    }) { Text(stringResource(R.string.erase), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirmErase = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
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
                    Text(pluralStringResource(R.plurals.study_days, days, days))
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

    private companion object {
        /** Preferences of the screens (not of the studies). */
        const val UI_PREFS = "ui"

        /** The permission dialog for notifications was shown once: tells "never asked" from "denied for good". */
        const val NOTIFY_ASKED = "notify_asked"
    }
}
