package de.askurious.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import de.askurious.app.BuildConfig
import de.askurious.app.R
import de.askurious.app.data.Api
import de.askurious.app.data.ApiError
import de.askurious.app.data.Look
import org.json.JSONObject

/**
 * Joining a study: a QR code, a join link ([initial]) or a typed join code; then the study's information in its look
 * and "Join". While the study is looked up or joined, back does nothing (UX audit 2026-10-16, B6); the result of a
 * join is read by MainActivity.App, so it is not lost when the screen goes meanwhile.
 */
@Composable
internal fun MainActivity.JoinScreen(initial: String, onDone: () -> Unit) {
    val context = LocalContext.current
    // what was typed and found survives turning the phone (audit A9); the study's information as its JSON text
    var code by rememberSaveable { mutableStateOf(joinCode(initial) ?: "") }
    // the server of the study: from its join link (QR code, App Link), else the app's own for a typed code
    var server by rememberSaveable { mutableStateOf(Api.defaultServer) }
    var infoText by rememberSaveable { mutableStateOf<String?>(null) }
    val info = remember(infoText) { infoText?.let { runCatching { JSONObject(it) }.getOrNull() } }
    val busy = model.joinBusy
    // while busy this handler takes the back gesture before that of the screens (the last registered wins). It is
    // tied to the activity like the one of the screens (not to this screen's entry of the back stack), so that it
    // stays the last one also after the app was in the background
    CompositionLocalProvider(LocalLifecycleOwner provides this) { BackHandler(enabled = busy) { if (model.cancelLookup()) onDone() } }
    var message by rememberSaveable { mutableStateOf<String?>(null) }
    // the question survives turning the phone; joining needs its button again
    var confirmRejoin by rememberSaveable { mutableStateOf(false) }
    // a scanned QR code without a join code: said at the scan button, which then scans again (the field keeps its code)
    var scanUnusable by rememberSaveable { mutableStateOf(false) }
    // the camera was refused: the scan button says so and points to the field (audit A10)
    var cameraRefused by rememberSaveable { mutableStateOf(false) }

    fun errorText(e: ApiError) = when {
        e.key == Api.FOREIGN -> getString(R.string.foreign_server, server.substringAfter("://"))
        e.key == "unknown" -> getString(R.string.not_found)
        e.key == "closed" -> getString(R.string.closed)
        e.key == "used" -> getString(R.string.code_used)
        e.key == "offline" -> getString(R.string.offline)
        e.key == REJOIN_PENDING -> getString(R.string.rejoin_pending)
        e.key == REJOIN_WAITING -> getString(R.string.rejoin_waiting)
        // the rate limit: trying again at once keeps the lock (audit A12)
        e.status == 429 -> getString(R.string.too_many)
        // with the HTTP status: a 404 of a web server in between is told apart from an error of Askurious
        else -> getString(R.string.error) + if (e.status > 0) " (${e.status})" else ""
    }

    fun lookUp(c: String) {
        if (busy) return
        // a pasted link without its code says so, as a scanned one does (UI/UX audit 2026-10-29, AND-06)
        val clean = joinCode(c) ?: run { if (c.isNotBlank()) message = getString(R.string.link_no_code); return }
        scanUnusable = false
        val at = joinServer(c, BuildConfig.DEBUG) ?: Api.defaultServer
        code = clean
        server = at
        infoText = null
        // a server that is not one of Askurious: refused before it is asked anything (audit 2026-10-13, AN-1)
        if (!Api.isKnown(at)) {
            message = errorText(ApiError(Api.FOREIGN))
            return
        }
        message = null
        model.lookUp(at, clean) { _, _ -> }
    }

    /**
     * Enrols with the join code of [info]. When the same study is on the phone already (the participant agreed to
     * join again), the earlier participation is closed first with its own secret (Sync.rejoin): its waiting answers
     * never go to the new person.
     */
    fun enrol(info: JSONObject) {
        message = null
        model.enrol(server, code, info) { _, _ -> }
    }

    LaunchedEffect(model.joinInfo, model.joinError) {
        model.joinInfo?.let { infoText = it }
        model.joinError?.let { message = errorText(it) }
    }

    // cancelled: nothing; a code without a join code (another link, any text) says so instead of doing nothing
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        result.contents?.let { if (scannedJoinCode(it) != null) lookUp(it) else scanUnusable = true }
    }

    fun scan() {
        cameraRefused = false
        scanUnusable = false
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
            uiPrefs.getBoolean(MainActivity.CAMERA_ASKED, false) && !shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) -> {
                cameraRefused = true
                openOther(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
            }
            else -> {
                uiPrefs.edit { putBoolean(MainActivity.CAMERA_ASKED, true) }
                cameraLauncher.launch(Manifest.permission.CAMERA)
            }
        }
    }
    // a join link is looked up once, not again after turning the phone
    LaunchedEffect(initial) { if (initial.isNotBlank() && infoText == null && message == null) lookUp(initial) }
    val focus = remember { FocusRequester() }

    // once the study is found, the screen wears its look (that of its pages)
    StudyTheme(info?.let { Look.fromJson(it.optJSONObject("look")) }, bars = true) {
        BackScaffold(stringResource(R.string.join_study), { if (model.cancelLookup()) onDone() }) { pad ->
            Column(
                // the insets of the bars are taken by the scaffold already: the keyboard does not add them again (B21)
                pad.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).pageWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val i = info
                if (i == null) {
                    OutlinedButton(onClick = { scanWithPermission() }, enabled = !busy) {
                        Text(stringResource(if (scanUnusable) R.string.scan_again else R.string.scan_qr))
                    }
                    if (scanUnusable) {
                        Text(
                            stringResource(R.string.scan_no_code),
                            Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                        )
                    }
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
                        enabled = !busy,
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
                    i.optString("contact").takeIf { it.isNotBlank() }?.let { StudyContact(it) }
                    val days = i.optInt("period_days")
                    // an open-ended study runs until the researchers end it
                    if (i.optBoolean("open_end", false) || days <= 0) Text(stringResource(R.string.duration_open_end))
                    else Text(pluralStringResource(R.plurals.study_days, days, days))
                    val modules = i.optJSONArray("modules")
                    if (modules != null && modules.length() > 0) {
                        SectionTitle(stringResource(R.string.study_modules))
                        for (k in 0 until modules.length()) {
                            val m = modules.getJSONObject(k)
                            val name = m.optString("name")
                            BulletText(
                                when {
                                    m.optString("trigger") == "self" && m.optInt("max_per_day", 0) > 0 ->
                                        stringResource(R.string.self_module_max, name, m.optInt("max_per_day"))
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
                            // a timed module the participant may also start themselves
                            if (m.optString("trigger") != "self" && (m.optBoolean("self", false) || m.optJSONObject("self") != null)) {
                                BulletText(stringResource(R.string.also_self_module, name))
                            }
                        }
                    }
                    val minutes = i.optInt("minutes")
                    if (minutes > 0) Text(pluralStringResource(R.plurals.about_minutes, minutes, minutes))
                    Text(stringResource(R.string.join_privacy), style = MaterialTheme.typography.bodySmall)
                    // who runs the server of the study, with its address: nobody joins another server unawares (B30)
                    Text(stringResource(R.string.join_server, server.substringAfter("://")), style = MaterialTheme.typography.bodySmall)
                    // a personal code someone joined with already: it moves that participation to this phone
                    val rejoin = i.optBoolean("rejoin", false)
                    if (rejoin) Text(stringResource(R.string.rejoin_code), style = MaterialTheme.typography.bodySmall)
                    // "Join" counts only while no other app's window lies over the screen (AN-16)
                    GuardAgainstOverlays()
                    Button(enabled = !busy, onClick = {
                        // the same study on this phone already: joining again replaces its secret, so ask first;
                        // a personal code connecting again keeps the same participation, nothing to ask
                        if (!rejoin && model.studies.any { it.code == i.optString("code") }) confirmRejoin = true else enrol(i)
                    }) { Text(stringResource(R.string.join)) }
                    TextButton(onClick = onDone, enabled = !busy) { Text(stringResource(R.string.cancel)) }
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
    if (confirmRejoin && pendingInfo != null) RejoinDialog(onDismiss = { confirmRejoin = false }) {
        confirmRejoin = false
        enrol(pendingInfo)
    }
}

/**
 * Joining a study that is on the phone already (UX audit 2026-10-16, B27): a title that TalkBack announces, the core
 * in two sentences, the rest behind "Learn more".
 */
@Composable
private fun RejoinDialog(onDismiss: () -> Unit, onRejoin: () -> Unit) {
    var more by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.rejoin_title)) },
        text = {
            GuardAgainstOverlays()
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.rejoin_confirm))
                if (more) Text(stringResource(R.string.rejoin_more), Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                else TextButton(onClick = { more = true }) { Text(stringResource(R.string.learn_more)) }
            }
        },
        confirmButton = { TextButton(onClick = onRejoin) { Text(stringResource(R.string.rejoin)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
