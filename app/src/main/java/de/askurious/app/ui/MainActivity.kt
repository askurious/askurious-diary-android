package de.askurious.app.ui

import android.Manifest
import android.app.TimePickerDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Done
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import de.askurious.app.BuildConfig
import de.askurious.app.R
import de.askurious.app.data.Api
import de.askurious.app.data.ApiError
import de.askurious.app.data.Look
import de.askurious.app.data.Module
import de.askurious.app.data.Quiet
import de.askurious.app.data.Study
import de.askurious.app.data.Trigger
import de.askurious.app.schedule.Alarms
import de.askurious.app.schedule.Schedule
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private var linkCode by mutableStateOf<String?>(null)

    /** The studies, the waiting answers and the calls to the server (MainModel); this activity only draws them. */
    private val model: MainModel by viewModels()

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

    /**
     * Whether the picture of the app in "Recents" (and on older Android a screenshot) is hidden (AN-7): from Android 13
     * only the picture in "Recents" (setRecentsScreenshotEnabled), screenshots for help stay possible; before that
     * FLAG_SECURE, the only way there.
     */
    private fun hideInRecents(hide: Boolean) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(!hide)
        } else if (hide) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /** Opens [intent] of another app; a phone without an app for it simply gets nothing. */
    private fun openOther(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // nothing to open it with
        }
    }

    /** The settings of "Alarms & reminders" for this app (Android 12 and later; before, exact alarms need nothing). */
    private fun openExactSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            openOther(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:$packageName".toUri()))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // the whole link: JoinScreen reads the code and the server from it (joinCode, joinServer); after turning the
        // phone (a new activity, the same intent) the screens come from the saved back stack, not again from the link
        linkCode = if (savedInstanceState == null) intent?.data?.toString()?.takeIf { joinCode(it) != null } else null
        setContent { DiaryTheme { App() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.toString()?.takeIf { joinCode(it) != null }?.let { linkCode = it }
    }

    override fun onResume() {
        super.onResume()
        model.reload()
        readPermissions()
        // not while a sync runs, nor again within a few minutes (every return from an entry or a dialog resumes)
        model.syncIfDue()
    }

    /**
     * The screens of the app on a back stack (audit SA1, A9): the list, a study's detail page, joining, the step for
     * the permissions, about the app and the licence texts. The stack and each screen's state are saved with the
     * activity, so turning the phone or a change of language or dark mode keeps them; the back gesture goes one screen
     * back.
     */
    @Composable
    private fun App() {
        val stack = rememberSaveable(saver = STACK_SAVER) { mutableStateListOf(HOME) }
        val states = rememberSaveableStateHolder()
        fun push(screen: String) = stack.add(screen)
        fun pop() {
            if (stack.size > 1) states.removeState("${stack.size - 1}|${stack.removeAt(stack.lastIndex)}")
        }
        val link = linkCode
        LaunchedEffect(link) {
            if (link != null) {
                push(JOIN + link)
                linkCode = null
            }
        }
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        val say: (String) -> Unit = { text -> scope.launch { snackbar.showSnackbar(text) } }
        // the notice stays after a denial: readPermissions() decides whether the dialog can still come (audit A2)
        val notifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { readPermissions() }
        val allowNotifications: () -> Unit = {
            if (notifyState == NotifyNotice.SETTINGS) {
                // denied for good (or switched off): the system shows no dialog any more
                openOther(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                uiPrefs.edit { putBoolean(NOTIFY_ASKED, true) }
                notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (stack.size > 1) BackHandler { pop() }
        val top = stack.last()
        // the list of studies, a study's page and a found study show titles ("Diary on drinking"): no picture of them
        // in "Recents" (audit 2026-10-13, AN-7); the other screens keep theirs
        val hidden = when {
            top == HOME -> model.studies.isNotEmpty() || !model.loaded
            top.startsWith(STUDY) || top.startsWith(JOIN) -> true
            else -> false
        }
        LaunchedEffect(hidden) { hideInRecents(hidden) }
        states.SaveableStateProvider("${stack.size - 1}|$top") {
            when {
                top.startsWith(JOIN) -> JoinScreen(
                    initial = top.removePrefix(JOIN),
                    onDone = { pop(); model.reload() },
                    onJoined = {
                        pop()
                        // after the first questionnaire: one step that explains both permissions (audit A27)
                        if ((notifyState != NotifyNotice.NONE || !exactOn) && !uiPrefs.getBoolean(READY_SHOWN, false)) {
                            uiPrefs.edit { putBoolean(READY_SHOWN, true) }
                            push(READY)
                        }
                        model.reload()
                    },
                )
                top.startsWith(STUDY) -> {
                    val study = model.studies.firstOrNull { it.code == top.removePrefix(STUDY) }
                    // gone from the phone; before the studies were read (they are read off the main thread) nothing yet
                    if (study == null) { if (model.loaded) LaunchedEffect(top) { pop() } }
                    else StudyTheme(study.look, bars = true) { StudyScreen(study, onBack = { pop() }, snackbar = snackbar, say = say) }
                }
                top == ABOUT -> AboutScreen(onBack = { pop() }, onLicence = { push(LICENCE) })
                top == LICENCE -> LicenceScreen(onBack = { pop() })
                top == READY -> ReadyScreen(onDone = { pop() }, allowNotifications = allowNotifications)
                else -> HomeScreen(
                    onJoin = { push(JOIN) }, onAbout = { push(ABOUT) }, onStudy = { push(STUDY + it.code) },
                    allowNotifications = allowNotifications, snackbar = snackbar,
                )
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun HomeScreen(
        onJoin: () -> Unit, onAbout: () -> Unit, onStudy: (Study) -> Unit, allowNotifications: () -> Unit, snackbar: SnackbarHostState,
    ) {
        val studies = model.studies
        Scaffold(
            topBar = {
                TopAppBar(title = { Logo() }, actions = {
                    IconButton(onClick = onAbout) { Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.about)) }
                })
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
            snackbarHost = { MessageHost(snackbar) },
        ) { pad ->
            val (now, ended) = grouped(studies) { it.today() }
            LazyColumn(
                Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp),
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
                // what keeps the prompts from arriving (on time), right below the heading, in view after a cold start
                // (audit A1, SA3); nothing while all is well
                if (studies.isNotEmpty()) item(key = "readiness") { Readiness(studies, allowNotifications) }
                // a study's server needs a newer app (config DIARY_MIN_APP_VERSION): the app keeps working, but asks
                if (studies.any { it.needsUpdate(BuildConfig.VERSION_CODE) }) item(key = "notice-update") {
                    Notice(stringResource(R.string.update_app), Icons.Outlined.Warning, stringResource(R.string.update_button)) {
                        // the Play Store opens this link itself; without it the browser shows the page
                        openOther(Intent(Intent.ACTION_VIEW, "https://play.google.com/store/apps/details?id=$packageName".toUri()))
                    }
                }
                if (model.clockOff) item(key = "notice-clock") { Notice(stringResource(R.string.clock_off), Icons.Outlined.Warning) }
                if (studies.isEmpty() && model.loaded) item {
                    Column(Modifier.padding(top = 32.dp)) {
                        Text(stringResource(R.string.no_studies))
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = onJoin) { Text(stringResource(R.string.join_study)) }
                    }
                }
                // running and not yet over on top, the ended ones under a heading of their own (audit A20)
                items(now, key = { it.code }) { StudyTheme(it.look) { StudyCard(it) { onStudy(it) } } }
                if (ended.isNotEmpty()) item(key = "ended") {
                    Text(
                        stringResource(R.string.ended_group), style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 12.dp).semantics { heading() },
                    )
                }
                items(ended, key = { it.code }) { StudyTheme(it.look) { StudyCard(it) { onStudy(it) } } }
                item {
                    // which version runs on this phone (version, build time; server of a debug build)
                    val format = stringResource(R.string.build_info)
                    Text(
                        buildInfo { v, t -> String.format(format, v, t) },
                        Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    /**
     * The readiness of the phone for the prompts (audit SA3): one block in the error colours, a line per missing
     * permission with the button that leads to it; invisible while all is well. Notifications count for every study,
     * exact alarms only while one runs.
     */
    @Composable
    private fun Readiness(studies: List<Study>, allowNotifications: () -> Unit) {
        val notify = notifyState != NotifyNotice.NONE
        val exact = studies.any { it.running } && !exactOn
        if (!notify && !exact) return
        NoticeCard {
            if (notify) NoticeRow(
                stringResource(R.string.allow_notifications), Icons.Outlined.Notifications,
                stringResource(if (notifyState == NotifyNotice.SETTINGS) R.string.allow_in_settings else R.string.allow),
                allowNotifications,
            )
            if (notify && exact) HorizontalDivider(color = MaterialTheme.colorScheme.error.copy(alpha = 0.3f))
            if (exact) NoticeRow(stringResource(R.string.allow_exact), Icons.Outlined.Warning, stringResource(R.string.allow)) { openExactSettings() }
        }
    }

    /** A notice of the start page: in the error colours with an icon, unlike a study card (audit A20). */
    @Composable
    private fun NoticeCard(content: @Composable () -> Unit) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.onErrorContainer,
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        ) { Column { content() } }
    }

    @Composable
    private fun NoticeRow(text: String, icon: ImageVector, button: String?, onClick: () -> Unit) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(text, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                if (button != null) Button(onClick = onClick) { Text(button) }
            }
        }
    }

    @Composable
    private fun Notice(text: String, icon: ImageVector, button: String? = null, onClick: () -> Unit = {}) {
        NoticeCard { NoticeRow(text, icon, button, onClick) }
    }

    /**
     * A study on the start page (audit SA2): title, state with an icon, the next question and what to do now; the
     * rest (study period, modules, quiet hours, waiting answers, leaving and deleting) on its detail page, which the
     * card opens. TalkBack reads the card as one item, its buttons apart (audit A22).
     */
    @Composable
    private fun StudyCard(study: Study, onOpen: () -> Unit) {
        Card(
            onClick = onOpen,
            modifier = Modifier.fillMaxWidth(),
            // in the study's look (StudyTheme): the page colour with its border, as the participant pages show it
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(study.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StudyNow(study)
                if (study.code in model.refused) {
                    Text(stringResource(R.string.limit_refused), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { model.clearRefused(study.code) }) { Text(stringResource(R.string.close)) }
                }
            }
        }
    }

    /** The study's date and time now, read again every 15 s for a fast-forward person (a day passes in minutes). */
    @Composable
    private fun studyNow(study: Study): Long {
        var tick by remember { mutableLongStateOf(System.currentTimeMillis()) }
        if (study.clock != null) LaunchedEffect(study.code) {
            while (true) {
                delay(15_000)
                tick = System.currentTimeMillis()
            }
        }
        return tick
    }

    /**
     * What the card and the detail page say alike: the fast-forward badge, the state with its icon, the next question
     * and the buttons for what is to do now (the first questionnaire, a report, the exit questionnaire).
     */
    @Composable
    private fun StudyNow(study: Study) {
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
                    Modifier.padding(horizontal = 8.dp, vertical = 2.dp).clearAndSetSemantics { contentDescription = spoken },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        StateLine(study, state, today)
        if (state == StudyState.RUNNING) {
            if (notifyState != NotifyNotice.NONE) {
                // without notifications no prompt reaches the participant: no time that would not come (audit A1)
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
            val dayNow = study.dayIndex(today) ?: 0
            for (m in study.modules) {
                val t = m.trigger as? Trigger.Self ?: continue
                if (dayNow < m.dayFrom || dayNow > m.dayTo) continue
                Button(onClick = { startActivity(EntryActivity.intent(context, study.code, m.key)) }) { Text(t.label.ifBlank { m.name }) }
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
                (study.dayIndex(today)?.let { d -> study.periodDays?.let { stringResource(R.string.day_of, d, it) } } ?: stringResource(R.string.state_running))
            StudyState.PERIOD_OVER -> Icons.Outlined.Refresh to stringResource(R.string.period_over)
            StudyState.PAUSED -> Icons.Outlined.Info to stringResource(R.string.paused)
            StudyState.EXIT_OPEN -> Icons.Outlined.Edit to (study.exit?.until?.let { stringResource(R.string.exit_open_until, dateText(it)) }
                ?: stringResource(R.string.exit_open))
            StudyState.FINISHED -> Icons.Outlined.CheckCircle to stringResource(R.string.finished)
            StudyState.LEFT -> Icons.AutoMirrored.Outlined.ExitToApp to stringResource(R.string.left)
            StudyState.LEFT_PENDING -> Icons.AutoMirrored.Outlined.ExitToApp to stringResource(R.string.left_pending)
            StudyState.ENDED -> Icons.Outlined.Done to stringResource(R.string.ended)
        }
        Row(Modifier.semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp).padding(top = 2.dp), tint = MaterialTheme.colorScheme.primary)
            Text(text, style = MaterialTheme.typography.bodyLarge)
        }
    }

    /**
     * "today, 09:40", "tomorrow, 08:15" or "Wed, 14 Oct, 09:40", in the phone's time format (12 or 24 h; A25);
     * [about]: "today, about 09:40" (A11).
     */
    private fun whenText(at: ZonedDateTime, today: LocalDate, about: Boolean): String {
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
    private fun timeText(at: ZonedDateTime): String = DateFormat.getTimeFormat(this).format(Date.from(at.toInstant()))

    private fun timeText(hhmm: String): String = timeText(LocalDate.now().atTime(LocalTime.parse(hhmm)).atZone(ZoneId.systemDefault()))

    private fun dateText(day: LocalDate): String = day.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))

    /** "22:00–07:00" in the phone's time format, or "none". */
    @Composable
    private fun quietText(quiet: Quiet?): String =
        if (quiet == null) stringResource(R.string.quiet_none) else "${timeText(quiet.start)}–${timeText(quiet.end)}"

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

    /**
     * The detail page of a study (audit SA2): what the card shows, the study period, what is asked, the quiet hours,
     * the waiting answers, and the final actions apart from the rest (leaving or removing, deleting the answers), each
     * asking first (A14, A15).
     */
    @Composable
    private fun StudyScreen(study: Study, onBack: () -> Unit, snackbar: SnackbarHostState, say: (String) -> Unit) {
        var confirmLeave by remember { mutableStateOf(false) }
        var confirmErase by remember { mutableStateOf(false) }
        var confirmRemove by remember { mutableStateOf(false) }
        var erasing by remember { mutableStateOf(false) }
        var eraseError by remember { mutableStateOf<String?>(null) }
        var editQuiet by remember { mutableStateOf(false) }
        val today = study.today(real = studyNow(study))
        BackScaffold(study.title, onBack, snackbar) { pad ->
            Column(
                pad.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(study.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                StudyNow(study)
                if (study.code in model.refused) {
                    Text(stringResource(R.string.limit_refused), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { model.clearRefused(study.code) }) { Text(stringResource(R.string.close)) }
                }
                val n = model.waiting[study.code] ?: 0
                if (n > 0) Text(pluralStringResource(R.plurals.answers_waiting, n, n))

                val first = study.firstDay
                val last = study.lastDay
                if (first != null && last != null) {
                    SectionTitle(stringResource(R.string.period_title))
                    Text(stringResource(R.string.period_from_to, dateText(first), dateText(last)))
                }
                if (study.modules.isNotEmpty()) {
                    SectionTitle(stringResource(R.string.study_modules))
                    for (m in study.modules) Text("• " + moduleText(m))
                }
                if (study.hasRandom) {
                    SectionTitle(stringResource(R.string.quiet_title))
                    val quietNow = study.quietOn(today)
                    val quietNext = study.quietOn(today.plusDays(1))
                    Text(stringResource(R.string.quiet_now, quietText(quietNow)))
                    if (quietNext != quietNow) Text(stringResource(R.string.quiet_from_tomorrow, quietText(quietNext)))
                    if (study.running) OutlinedButton(onClick = { editQuiet = true }) { Text(stringResource(R.string.quiet_change)) }
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
                    }) { Text(stringResource(R.string.leave_study), color = MaterialTheme.colorScheme.error) }
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
                    GuardAgainstOverlays()
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
                        model.remove(study.code)
                        say(getString(R.string.removed_done, study.title))
                        onBack()
                    }) { Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text(stringResource(R.string.cancel)) } },
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
                        if (erasing) {
                            val busy = stringResource(R.string.erasing)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = busy })
                                Text(busy)
                            }
                        }
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
                            }
                        }
                    }) { Text(stringResource(R.string.erase), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(enabled = !erasing, onClick = { confirmErase = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }

    /**
     * The quiet hours of a study (audit A16): a switch "Use quiet hours" with from and to (the phone's time pickers),
     * saved only with "Save". They keep the random prompts out of that time from tomorrow on; the fixed times of the
     * researchers stay as they are.
     */
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
                    val label = stringResource(R.string.quiet_use)
                    Row(
                        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {},
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(label, Modifier.weight(1f))
                        Switch(checked = on, onCheckedChange = { on = it })
                    }
                    if (on) {
                        val fromText = stringResource(R.string.quiet_from_cd, timeText(start))
                        val toText = stringResource(R.string.quiet_to_cd, timeText(end))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(stringResource(R.string.quiet_from))
                            // "Quiet hours from 23:00", not only "23:00" (audit A16)
                            OutlinedButton(onClick = { pick(start) { start = it } }, Modifier.semantics { contentDescription = fromText }) { Text(timeText(start)) }
                            Text(stringResource(R.string.quiet_to))
                            OutlinedButton(onClick = { pick(end) { end = it } }, Modifier.semantics { contentDescription = toText }) { Text(timeText(end)) }
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

    /**
     * After the first questionnaire: one step that says why the app needs notifications and "Alarms & reminders",
     * and asks for one after the other (audit A27). Whatever is missing later shows on the start page (Readiness).
     */
    @Composable
    private fun ReadyScreen(onDone: () -> Unit, allowNotifications: () -> Unit) {
        val notifyOk = notifyState == NotifyNotice.NONE
        val exactOk = exactOn
        BackScaffold(stringResource(R.string.ready_title), onDone) { pad ->
            Column(
                pad.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(stringResource(R.string.ready_intro))
                ReadyStep(
                    1, stringResource(R.string.ready_notify_title), stringResource(R.string.ready_notify_text), notifyOk, primary = true,
                    button = stringResource(if (notifyState == NotifyNotice.SETTINGS) R.string.allow_in_settings else R.string.allow),
                    onClick = allowNotifications,
                )
                ReadyStep(
                    2, stringResource(R.string.ready_exact_title), stringResource(R.string.ready_exact_text), exactOk, primary = notifyOk,
                    button = stringResource(R.string.allow), onClick = { openExactSettings() },
                )
                // "Done" once both are there; "Later" quietly beside the permission buttons until then
                if (notifyOk && exactOk) Button(onClick = onDone) { Text(stringResource(R.string.done)) }
                else TextButton(onClick = onDone) { Text(stringResource(R.string.later)) }
            }
        }
    }

    @Composable
    private fun ReadyStep(number: Int, title: String, text: String, ok: Boolean, primary: Boolean, button: String, onClick: () -> Unit) {
        val granted = stringResource(R.string.allowed)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                "$number. $title", style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading(); if (ok) stateDescription = granted },
            )
            Text(text)
            when {
                ok -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Outlined.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(granted)
                }
                // the next missing permission is the filled button; one after the other
                primary -> Button(onClick = onClick) { Text(button) }
                else -> OutlinedButton(onClick = onClick) { Text(button) }
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun JoinScreen(initial: String, onDone: () -> Unit, onJoined: () -> Unit) {
        val context = LocalContext.current
        // what was typed and found survives turning the phone (audit A9); the study's information as its JSON text
        var code by rememberSaveable { mutableStateOf(joinCode(initial) ?: "") }
        // the server of the study: from its join link (QR code, App Link), else the app's own for a typed code
        var server by rememberSaveable { mutableStateOf(Api.defaultServer) }
        var infoText by rememberSaveable { mutableStateOf<String?>(null) }
        val info = remember(infoText) { infoText?.let { runCatching { JSONObject(it) }.getOrNull() } }
        var busy by remember { mutableStateOf(false) }
        var message by rememberSaveable { mutableStateOf<String?>(null) }
        var confirmRejoin by remember { mutableStateOf(false) }
        // the camera was refused: the scan button says so and points to the field (audit A10)
        var cameraRefused by rememberSaveable { mutableStateOf(false) }

        fun errorText(e: ApiError) = when {
            e.key == Api.FOREIGN -> getString(R.string.foreign_server, server.substringAfter("://"))
            e.key == "unknown" -> getString(R.string.not_found)
            e.key == "closed" -> getString(R.string.closed)
            e.key == "offline" -> getString(R.string.offline)
            // the rate limit: trying again at once keeps the lock (audit A12)
            e.status == 429 -> getString(R.string.too_many)
            // with the HTTP status: a 404 of a web server in between is told apart from an error of Askurious
            else -> getString(R.string.error) + if (e.status > 0) " (${e.status})" else ""
        }

        fun lookUp(c: String) {
            val clean = joinCode(c) ?: return
            val at = joinServer(c, BuildConfig.DEBUG) ?: Api.defaultServer
            code = clean
            server = at
            infoText = null
            // a server that is not one of Askurious: refused before it is asked anything (audit 2026-10-13, AN-1)
            if (!Api.isKnown(at)) {
                message = errorText(ApiError(Api.FOREIGN))
                return
            }
            busy = true
            message = null
            model.lookUp(at, clean) { found, e ->
                infoText = found?.toString()
                message = e?.let { errorText(it) }
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
            model.enrol(server, code, info) { study, e ->
                busy = false
                if (study != null) {
                    startActivity(EntryActivity.intent(context, study.code, "entry"))
                    onJoined()
                } else {
                    message = e?.let { errorText(it) }
                }
            }
        }

        val scanner = rememberLauncherForActivityResult(ScanContract()) { result -> result.contents?.let { lookUp(it) } }

        fun scan() {
            cameraRefused = false
            // the scanner turns with the phone and says what to do; its own text is English and blames the camera
            // when the permission is missing, so the app asks for the permission itself first (audit A10)
            scanner.launch(
                ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setBeepEnabled(false)
                    .setOrientationLocked(false).setPrompt(getString(R.string.scan_prompt))
                    .setCaptureActivity(ScanActivity::class.java),
            )
        }

        val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) scan() else cameraRefused = true
        }

        fun scanWithPermission() {
            when {
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED -> scan()
                // refused for good: the system shows no dialog any more, only the app's settings can help
                uiPrefs.getBoolean(CAMERA_ASKED, false) && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                    cameraRefused = true
                    openOther(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
                }
                else -> {
                    uiPrefs.edit { putBoolean(CAMERA_ASKED, true) }
                    cameraLauncher.launch(Manifest.permission.CAMERA)
                }
            }
        }
        BackHandler(onBack = onDone)
        // a join link is looked up once, not again after turning the phone
        LaunchedEffect(initial) { if (initial.isNotBlank() && infoText == null && message == null) lookUp(initial) }
        val focus = remember { FocusRequester() }

        // once the study is found, the screen wears its look (that of its pages)
        StudyTheme(info?.let { Look.fromJson(it.optJSONObject("look")) }, bars = true) {
            BackScaffold(stringResource(R.string.join_study), onDone) { pad ->
                Column(
                    pad.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val i = info
                    if (i == null) {
                        OutlinedButton(onClick = { scanWithPermission() }) { Text(stringResource(R.string.scan_qr)) }
                        if (cameraRefused) {
                            Text(
                                stringResource(R.string.camera_refused),
                                Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        val m = message
                        OutlinedTextField(
                            // lowercase only; the hyphen of a test code (k7qm2x-t3f9a) stays, joinCode removes spaces
                            value = code, onValueChange = { code = it.lowercase(); message = null },
                            label = { Text(stringResource(R.string.join_code)) }, singleLine = true,
                            // the whole width, the cursor in it, an example of a code and where it stands (audit A13)
                            modifier = Modifier.fillMaxWidth().focusRequester(focus),
                            // the error at the field it is about, read out when it comes (audit A12)
                            isError = m != null,
                            supportingText = {
                                if (m != null) Text(m, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                                else Text(stringResource(R.string.join_code_hint))
                            },
                            keyboardOptions = KeyboardOptions(
                                capitalization = KeyboardCapitalization.None,
                                autoCorrectEnabled = false,
                                keyboardType = KeyboardType.Ascii,
                                imeAction = ImeAction.Go,
                            ),
                            // Enter on the keyboard looks the code up, as the button does (audit A13)
                            keyboardActions = KeyboardActions(onGo = { if (!busy && code.isNotBlank()) lookUp(code) }),
                        )
                        Button(onClick = { lookUp(code) }, enabled = !busy && code.isNotBlank()) { Text(stringResource(R.string.look_up)) }
                        // a typed code: the field is ready at once (a link fills it and looks it up by itself)
                        LaunchedEffect(Unit) { if (initial.isBlank() && !cameraRefused) runCatching { focus.requestFocus() } }
                    } else {
                        Text(i.optString("title"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
                        i.optString("description").takeIf { it.isNotBlank() }?.let { Text(it) }
                        val days = i.optInt("period_days")
                        Text(pluralStringResource(R.plurals.study_days, days, days))
                        val modules = i.optJSONArray("modules")
                        if (modules != null && modules.length() > 0) {
                            SectionTitle(stringResource(R.string.study_modules))
                            for (k in 0 until modules.length()) {
                                val m = modules.getJSONObject(k)
                                val name = m.optString("name")
                                Text(
                                    "• " + when {
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
                        // "Join" counts only while no other app's window lies over the screen (AN-16)
                        GuardAgainstOverlays()
                        Button(enabled = !busy, onClick = {
                            // the same study on this phone already: joining again replaces its secret, so ask first
                            if (model.studies.any { it.code == i.optString("code") }) confirmRejoin = true else enrol(i)
                        }) { Text(stringResource(R.string.join)) }
                        TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) }
                    }
                    if (busy) {
                        val label = stringResource(if (i == null) R.string.looking_up else R.string.joining)
                        CircularProgressIndicator(Modifier.semantics { contentDescription = label })
                    }
                    // an error of joining (the field is gone by then): read out when it comes
                    if (i != null) message?.let {
                        Text(it, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        val pendingInfo = info
        if (confirmRejoin && pendingInfo != null) {
            AlertDialog(
                onDismissRequest = { confirmRejoin = false },
                text = {
                    GuardAgainstOverlays()
                    Text(stringResource(R.string.rejoin_confirm))
                },
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

        /** The permission dialog for the camera (QR code) was shown once; the same distinction for the camera. */
        const val CAMERA_ASKED = "camera_asked"

        /** The step for the permissions after the first join was shown (audit A27): once per phone. */
        const val READY_SHOWN = "ready_shown"

        /** The screens of the back stack; a join screen and a study's page carry their link or code after the prefix. */
        const val HOME = "home"
        const val JOIN = "join:"
        const val STUDY = "study:"
        const val ABOUT = "about"
        const val LICENCE = "licence"
        const val READY = "ready"

        val STACK_SAVER = listSaver<SnapshotStateList<String>, String>(save = { it.toList() }, restore = { it.toMutableStateList() })
    }
}
