package de.askurious.app.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.os.BundleCompat
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavController
import androidx.navigation.NavDeepLinkRequest
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import androidx.navigation.toRoute
import de.askurious.app.R
import de.askurious.app.data.StudyStore
import de.askurious.app.schedule.Alarms
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var linkCode by mutableStateOf<String?>(null)

    /** A study whose page a home-screen shortcut opens, when its report cannot start now (see [report]). */
    private var shortcutStudy by mutableStateOf<String?>(null)

    /** The studies, the waiting answers and the calls to the server (MainModel); this activity only draws them. */
    internal val model: MainModel by viewModels()

    /** The notice for notifications (none, the system dialog, the app's settings); read again in [onResume]. */
    internal var notifyState by mutableStateOf(NotifyNotice.NONE)
        private set

    /**
     * The notification channels of the prompts the participant switched off in the system settings (Alarms.CHANNEL,
     * Alarms.CHANNEL_SILENT); read again in [onResume]. A study warns only when its own channel is off
     * ([studyChannelOff]): a study without sound uses the silent one.
     */
    internal var channelsOff by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Exact alarms are allowed ("Alarms & reminders"); read again in [onResume], also after the system settings. */
    internal var exactOn by mutableStateOf(true)
        private set

    /**
     * The app is exempt from the battery optimisation; read again in [onResume]. Optional: exact alarms pass Doze,
     * but some makers' battery managers stop them all the same (UX audit 2026-10-16, B11).
     */
    internal var batteryOn by mutableStateOf(true)
        private set

    internal val uiPrefs get() = getSharedPreferences(UI_PREFS, MODE_PRIVATE)

    /**
     * Reads the permissions again: on every return to the app (from the system settings, the permission dialog, an
     * entry), so that a notice goes as soon as the permission is there and comes back when it was taken away.
     */
    private fun readPermissions() {
        val manager = NotificationManagerCompat.from(this)
        // a channel switched off counts only for the studies that use it (with or without sound), not for all
        channelsOff = channelsOff(listOf(Alarms.CHANNEL, Alarms.CHANNEL_SILENT).associateWith { manager.getNotificationChannelCompat(it)?.importance })
        val canAsk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        notifyState = notifyNotice(
            enabled = manager.areNotificationsEnabled(),
            canAsk = canAsk,
            granted = !canAsk || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
            asked = uiPrefs.getBoolean(NOTIFY_ASKED, false),
            rationale = canAsk && shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS),
        )
        exactOn = Alarms.canExact(this)
        batteryOn = getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) != false
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
    internal fun openOther(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // nothing to open it with
        }
    }

    /**
     * The phone's list of the battery optimisation, where the participant can exempt the app (B11). Only the list,
     * never the direct request ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS: that needs a permission of its own and
     * is restricted on Google Play; the list needs none.
     */
    internal fun openBatterySettings() = openOther(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))

    /** The settings of "Alarms & reminders" for this app (Android 12 and later; before, exact alarms need nothing). */
    internal fun openExactSettings() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            openOther(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:$packageName".toUri()))
        }
    }

    /** The system settings of one notification channel of the app ([channel]: Alarms.CHANNEL or CHANNEL_SILENT). */
    internal fun openChannelSettings(channel: String) = openOther(
        Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, channel),
    )

    /** The phone's settings of date and time, from the notice that its clock is wrong (UX audit 2026-10-16, B29). */
    internal fun openDateSettings() = openOther(Intent(Settings.ACTION_DATE_SETTINGS))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // the whole link: JoinScreen reads the code and the server from it (joinCode, joinServer); after turning the
        // phone (a new activity, the same intent) the screens come from the saved back stack, not again from the link
        linkCode = if (savedInstanceState == null) intent?.data?.toString()?.takeIf { isJoinLink(it) } else null
        // App opens the link on top of the screens (as one that comes while the app runs). The NavController would
        // otherwise take the launch intent itself, as it matches a deep link of the graph, and for a link of another
        // app (FLAG_ACTIVITY_NEW_TASK) start the task anew; so the intent keeps no link for it
        if (linkCode != null) setIntent(Intent(intent).setData(null))
        if (savedInstanceState == null) intent?.let { report(it) }
        setContent { DiaryTheme { App() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.toString()?.takeIf { isJoinLink(it) }?.let { linkCode = it }
        report(intent)
    }

    /**
     * A home-screen shortcut (Shortcuts): opens the entry of its module while the study runs and the module may be
     * started today; otherwise the page of the study when it is still on the phone, else just the app. Only the stored
     * study is used (its own server, as for any entry); the extras of the intent pick nothing else. Read off the main
     * thread, as the stored studies are opened with the Keystore (AN-8). Only an intent with the app's own token
     * ([Shortcuts.trusted]): another app cannot start entries and so neither use up the daily limit nor make empty
     * entries (Abgleich 2026-10-15, T07-N-1); without it the app just opens.
     */
    private fun report(intent: Intent) {
        if (intent.action != Shortcuts.ACTION_REPORT) return
        val code = intent.getStringExtra(Shortcuts.EXTRA_STUDY) ?: return
        val module = intent.getStringExtra(Shortcuts.EXTRA_MODULE)
        val given = intent.getStringExtra(Shortcuts.EXTRA_TOKEN)
        lifecycleScope.launch {
            val (target, known) = withContext(Dispatchers.IO) {
                if (!Shortcuts.trusted(given, Shortcuts.token(this@MainActivity))) return@withContext null to false
                Shortcuts.target(this@MainActivity, code, module) to (StudyStore(this@MainActivity).get(code) != null)
            }
            if (target != null) startActivity(EntryActivity.intent(this@MainActivity, target.first, target.second))
            else if (known) shortcutStudy = code
        }
    }

    override fun onResume() {
        super.onResume()
        model.reload()
        readPermissions()
        // not while a sync runs, nor again within a few minutes (every return from an entry or a dialog resumes)
        model.syncIfDue()
    }

    /**
     * The screens of the app on the back stack of Navigation Compose (audit SA1, A9; UX audit 2026-10-16, B22): the
     * back gesture goes one screen back, with a preview of the screen below (predictive back) and a short slide and
     * fade between the screens. From a width of 840 dp (window size class "expanded") the list and a study's page
     * stand side by side (B34).
     */
    @Composable
    private fun App() {
        val nav = rememberNavController()
        val snackbar = remember { SnackbarHostState() }
        val scope = rememberCoroutineScope()
        // each message goes after its time (MessageHost), so the next one follows; an error has a close button
        val say = remember(snackbar, scope) {
            Say { text, error -> scope.launch { snackbar.showSnackbar(text, withDismissAction = error, duration = Say.duration(error)) } }
        }
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
        val wide = LocalConfiguration.current.screenWidthDp >= EXPANDED_DP
        // a card opens its study; side by side another card replaces the study shown, so back always leads to the list
        val openStudy: (String) -> Unit = { code ->
            nav.navigate(Route.Study(code)) {
                popUpTo<Route.Home>()
                launchSingleTop = true
            }
        }

        NavHost(
            nav, startDestination = Route.Home, modifier = Modifier.fillMaxSize(),
            enterTransition = { if (sideBySide(wide)) fadeIn(tween(FADE_MS)) else forward(true) },
            exitTransition = { if (sideBySide(wide)) fadeOut(tween(FADE_MS)) else forwardOut(true) },
            popEnterTransition = { if (sideBySide(wide)) fadeIn(tween(FADE_MS)) else forward(false) },
            popExitTransition = { if (sideBySide(wide)) fadeOut(tween(FADE_MS)) else forwardOut(false) },
        ) {
            composable<Route.Home> {
                val list = @Composable {
                    HomeScreen(
                        onJoin = { nav.navigate(Route.Join()) }, onAbout = { nav.navigate(Route.About) }, onStudy = { openStudy(it.code) },
                        allowNotifications = allowNotifications, snackbar = snackbar, say = say,
                    )
                }
                if (wide) SideBySide(list) { NoStudyChosen() } else list()
            }
            composable<Route.Study> { entry ->
                val code = entry.toRoute<Route.Study>().code
                val study = model.studies.firstOrNull { it.code == code }
                val page = @Composable {
                    // gone from the phone; before the studies were read (they are read off the main thread) nothing yet
                    if (study == null) { if (model.loaded) LaunchedEffect(code) { nav.back(entry) } }
                    else StudyTheme(study.look, bars = true) { StudyScreen(study, onBack = { nav.back(entry) }, snackbar = snackbar, say = say) }
                }
                if (wide) SideBySide({
                    HomeScreen(
                        onJoin = { nav.navigate(Route.Join()) }, onAbout = { nav.navigate(Route.About) }, onStudy = { openStudy(it.code) },
                        allowNotifications = allowNotifications, snackbar = null, say = say, selected = code,
                    )
                }, page) else page()
            }
            composable<Route.Join>(deepLinks = JOIN_LINKS.map { navDeepLink { uriPattern = it } }) { entry ->
                // a join link that opened the app: its whole address (the server and the code), else the route's link;
                // the internal address Navigation puts in every entry is no join link (AND-03)
                val deep = entry.arguments?.let { BundleCompat.getParcelable(it, NavController.KEY_DEEP_LINK_INTENT, Intent::class.java) }
                    ?.data?.toString()
                val link = joinLinkOf(deep, entry.toRoute<Route.Join>().link)
                JoinScreen(initial = link, onDone = { nav.back(entry) })
            }
            composable<Route.Ready> { entry -> ReadyScreen(onDone = { nav.back(entry) }, allowNotifications = allowNotifications) }
            composable<Route.About> { entry -> AboutScreen(onBack = { nav.back(entry) }, onLicence = { nav.navigate(Route.Licence) }) }
            composable<Route.Licence> { entry -> LicenceScreen(onBack = { nav.back(entry) }) }
        }

        val link = linkCode
        LaunchedEffect(link) {
            if (link != null) {
                nav.openJoinLink(link)
                linkCode = null
            }
        }
        val opened = shortcutStudy
        LaunchedEffect(opened) {
            if (opened != null) {
                val top = nav.currentBackStackEntry?.takeIf { it.destination.hasRoute<Route.Study>() }?.toRoute<Route.Study>()?.code
                if (top != opened) nav.navigate(Route.Study(opened))
                shortcutStudy = null
            }
        }
        // the result of a join, read here and not on the join screen, so that it arrives also when that screen went
        // meanwhile (UX audit 2026-10-16, B6): the entry questionnaire opens, then the step for the permissions
        val context = LocalContext.current
        LaunchedEffect(model.joinedStudy) {
            model.joinedStudy?.let { study ->
                val address = model.entryAddress
                model.consumedJoin()
                if (study.status == "pending") startActivity(EntryActivity.intent(context, study.code, "entry", address = address))
                if (nav.currentBackStackEntry?.destination?.hasRoute<Route.Join>() == true) nav.popBackStack()
                // after the entry questionnaire: one step that explains the permissions (audit A27), once per phone
                if ((notifyState != NotifyNotice.NONE || !exactOn || !batteryOn) && !uiPrefs.getBoolean(READY_SHOWN, false)) {
                    uiPrefs.edit { putBoolean(READY_SHOWN, true) }
                    nav.navigate(Route.Ready)
                }
                model.reload()
            }
        }
        val top by nav.currentBackStackEntryAsState()
        val destination = top?.destination
        // the join screen closed (back, its arrow, "Cancel", a join): what it found or failed with does not show up the
        // next time it opens (B6), and the list is read again
        val joining = destination?.hasRoute<Route.Join>() == true
        var wasJoining by rememberSaveable { mutableStateOf(false) }
        LaunchedEffect(joining) {
            if (wasJoining && !joining) {
                model.leftJoin()
                model.reload()
            }
            wasJoining = joining
        }
        // the list of studies, a study's page and a found study show titles ("Diary on drinking"): no picture of them
        // in "Recents" (audit 2026-10-13, AN-7); the other screens keep theirs
        val hidden = when {
            destination == null || destination.hasRoute<Route.Home>() -> model.studies.isNotEmpty() || !model.loaded
            destination.hasRoute<Route.Study>() || joining -> true
            else -> false
        }
        LaunchedEffect(hidden) { hideInRecents(hidden) }
    }

    /** The list beside a study's page, from the window size class "expanded" on (UX audit 2026-10-16, B34). */
    @Composable
    private fun SideBySide(list: @Composable () -> Unit, page: @Composable () -> Unit) {
        Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { list() }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Box(Modifier.weight(1.25f)) { page() }
        }
    }

    /** The right side while no study is chosen. */
    @Composable
    private fun NoStudyChosen() {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxHeight().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.choose_study), textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    /**
     * A join link that came with an intent: through the deep links of the join screen (the link stays in the entry's
     * deep-link intent), on top of the screens shown; a link the deep links do not take (a debug server, a path after
     * the code) as the route's link, as joinCode and joinServer read it.
     */
    private fun NavController.openJoinLink(link: String) {
        val request = NavDeepLinkRequest.Builder.fromUri(link.toUri()).build()
        if (graph.hasDeepLink(request)) navigate(request) else navigate(Route.Join(link))
    }

    /** One screen back from [entry], only while it is on top: a second tap during the transition goes nowhere. */
    private fun NavController.back(entry: NavBackStackEntry) {
        if (currentBackStackEntry?.id == entry.id) popBackStack()
    }

    /** The list and a study's page side by side: the change between them only fades, nothing slides. */
    private fun AnimatedContentTransitionScope<NavBackStackEntry>.sideBySide(wide: Boolean): Boolean =
        wide && listOf(initialState, targetState).all { it.destination.hasRoute<Route.Home>() || it.destination.hasRoute<Route.Study>() }

    /** The new screen slides in a little from the end ([forwards]) or the start (back), and fades in. */
    private fun AnimatedContentTransitionScope<NavBackStackEntry>.forward(forwards: Boolean): EnterTransition =
        slideIntoContainer(if (forwards) SlideDirection.Start else SlideDirection.End, tween(SLIDE_MS)) { it / 4 } + fadeIn(tween(SLIDE_MS))

    private fun AnimatedContentTransitionScope<NavBackStackEntry>.forwardOut(forwards: Boolean): ExitTransition =
        slideOutOfContainer(if (forwards) SlideDirection.Start else SlideDirection.End, tween(SLIDE_MS)) { it / 4 } + fadeOut(tween(SLIDE_MS))

    internal companion object {
        /** Preferences of the screens (not of the studies). */
        const val UI_PREFS = "ui"

        /** The permission dialog for notifications was shown once: tells "never asked" from "denied for good". */
        const val NOTIFY_ASKED = "notify_asked"

        /** The permission dialog for the camera (QR code) was shown once; the same distinction for the camera. */
        const val CAMERA_ASKED = "camera_asked"

        /** The step for the permissions after the first join was shown (audit A27): once per phone. */
        const val READY_SHOWN = "ready_shown"

        /** From this width on (window size class "expanded", Material 3) the list and a study's page stand side by side. */
        const val EXPANDED_DP = 840

        /** The duration of a change of screens: Material's short motion. */
        const val SLIDE_MS = 300
        const val FADE_MS = 150
    }
}
