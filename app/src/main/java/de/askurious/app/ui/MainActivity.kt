package de.askurious.app.ui

import android.Manifest
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import de.askurious.app.data.Api
import de.askurious.app.data.ApiError
import de.askurious.app.data.Study
import de.askurious.app.data.StudyStore
import de.askurious.app.data.Sync
import de.askurious.app.data.Trigger
import de.askurious.app.schedule.Alarms
import de.askurious.app.schedule.Schedule
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun DiaryTheme(content: @Composable () -> Unit) {
    val blue = Color(0xFF1F4E79)
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(primary = Color(0xFF9CC3E6)) else lightColorScheme(primary = blue)
    MaterialTheme(colorScheme = scheme, content = content)
}

class MainActivity : ComponentActivity() {
    private var linkCode by mutableStateOf<String?>(null)
    private var studies by mutableStateOf<List<Study>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        linkCode = intent?.data?.let { joinCode(it.toString()) }
        setContent { DiaryTheme { App() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.data?.let { linkCode = joinCode(it.toString()) }
    }

    override fun onResume() {
        super.onResume()
        studies = StudyStore(this).all()
        lifecycleScope.launch {
            Sync.all(this@MainActivity)
            studies = StudyStore(this@MainActivity).all()
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
        val context = LocalContext.current
        var askNotify by remember { mutableStateOf(false) }
        val notifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { askNotify = false }
        LaunchedEffect(studies.size) {
            askNotify = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && studies.isNotEmpty() &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        }
        Scaffold(topBar = {
            TopAppBar(title = { Text(stringResource(R.string.my_studies)) }, actions = {
                TextButton(onClick = onJoin) { Text(stringResource(R.string.join_study)) }
                IconButton(onClick = onAbout) { Icon(Icons.Outlined.Info, contentDescription = stringResource(R.string.about)) }
            })
        }) { pad ->
            LazyColumn(
                Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (askNotify) item {
                    Notice(stringResource(R.string.allow_notifications)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }
                }
                if (studies.any { it.running } && !Alarms.canExact(context)) item {
                    Notice(stringResource(R.string.allow_exact)) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:$packageName".toUri()))
                        }
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
            }
        }
    }

    @Composable
    private fun Notice(text: String, onAllow: () -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(text)
                TextButton(onClick = onAllow) { Text(stringResource(R.string.allow)) }
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
        var eraseError by remember { mutableStateOf<String?>(null) }
        val today = LocalDate.now()
        val fmt = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(study.title, style = MaterialTheme.typography.titleMedium)
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
                        Schedule.next(listOf(study), Instant.now()).firstOrNull()?.let {
                            Text(stringResource(R.string.next_prompt, Instant.ofEpochMilli(it.due).atZone(ZoneId.systemDefault()).format(fmt)))
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
                    "finished" -> Text(stringResource(R.string.finished))
                    "left" -> Text(stringResource(if (study.leavePending) R.string.left_pending else R.string.left))
                    else -> Text(stringResource(R.string.ended))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (study.status == "active" || study.status == "pending") {
                        TextButton(onClick = { confirmLeave = true }) { Text(stringResource(R.string.leave_study)) }
                    } else if (!study.leavePending) {
                        // a leave not yet told to the server keeps the study, so that the next sync can send it
                        TextButton(onClick = {
                            StudyStore(context).remove(study.code)
                            studies = StudyStore(context).all()
                        }) { Text(stringResource(R.string.remove_from_list)) }
                    }
                    // every study still holds its secret, so its answers can be deleted, also after the end
                    TextButton(onClick = { eraseError = null; confirmErase = true }) {
                        Text(stringResource(R.string.erase_answers), color = MaterialTheme.colorScheme.error)
                    }
                }
                eraseError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
        if (confirmLeave) {
            AlertDialog(
                onDismissRequest = { confirmLeave = false },
                text = { Text(stringResource(R.string.leave_confirm)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmLeave = false
                        scope.launch {
                            Sync.leave(context, study)
                            studies = StudyStore(context).all()
                        }
                    }) { Text(stringResource(R.string.leave_study)) }
                },
                dismissButton = { TextButton(onClick = { confirmLeave = false }) { Text(stringResource(R.string.cancel)) } },
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
                            studies = StudyStore(context).all()
                        }
                    }) { Text(stringResource(R.string.erase), color = MaterialTheme.colorScheme.error) }
                },
                dismissButton = { TextButton(onClick = { confirmErase = false }) { Text(stringResource(R.string.cancel)) } },
            )
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun JoinScreen(initial: String, onDone: () -> Unit) {
        val context = LocalContext.current
        val scope = rememberCoroutineScope()
        var code by remember { mutableStateOf(initial) }
        var info by remember { mutableStateOf<JSONObject?>(null) }
        var busy by remember { mutableStateOf(false) }
        var message by remember { mutableStateOf<String?>(null) }

        fun errorText(e: ApiError) = when (e.key) {
            "unknown" -> getString(R.string.not_found)
            "closed" -> getString(R.string.closed)
            "offline" -> getString(R.string.offline)
            else -> getString(R.string.error)
        }

        fun lookUp(c: String) {
            val clean = joinCode(c) ?: return
            code = clean
            busy = true
            message = null
            scope.launch {
                try {
                    info = Api.join(clean)
                } catch (e: ApiError) {
                    message = errorText(e)
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
                    Button(enabled = !busy, onClick = {
                        busy = true
                        scope.launch {
                            try {
                                val r = Api.enrol(code)
                                val study = Study.fromJson(i.put("status", "pending").put("modules", org.json.JSONArray()), r.getString("secret"))
                                StudyStore(context).put(study)
                                startActivity(EntryActivity.intent(context, study.code, "entry"))
                                onDone()
                            } catch (e: ApiError) {
                                message = errorText(e)
                            }
                            busy = false
                        }
                    }) { Text(stringResource(R.string.join)) }
                    TextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) }
                }
                if (busy) CircularProgressIndicator()
                message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
