package de.askurious.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.content.ActivityNotFoundException
import android.content.Context
import android.Manifest
import android.content.pm.PackageManager
import android.provider.Settings
import android.webkit.PermissionRequest
import android.webkit.GeolocationPermissions
import androidx.activity.viewModels
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import de.askurious.app.R
import de.askurious.app.data.AnswerQueue
import de.askurious.app.data.Api
import de.askurious.app.data.ApiError
import de.askurious.app.data.OfflinePages
import de.askurious.app.data.PromptLog
import de.askurious.app.data.Study
import de.askurious.app.data.StudyStore
import de.askurious.app.data.Sync
import de.askurious.app.data.SyncWorker
import de.askurious.app.data.Trigger
import de.askurious.app.schedule.Alarms
import de.askurious.app.schedule.Prompt
import de.askurious.app.schedule.Schedule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlin.coroutines.resume

/**
 * One entry: asks the server for a one-time address of the module and shows the participant pages of Askurious
 * in a WebView. The questionnaire itself is the web page; the app adds nothing to it. The end page calls
 * AskuriousApp.done() (participant/end.html in app mode): the thanks of the page stay in view, with a bar "Done" below
 * them that closes the entry (UX audit 2026-10-16, B33).
 *
 * Without a connection, a module that can be answered offline shows the page the app kept (OfflinePages): its
 * participant.js hands the filled form to AskuriousApp.offlineSubmit() instead of sending it, and the app queues
 * the answer (AnswerQueue) until it has a connection. The entry part is never answered this way.
 *
 * The WebView's cookies and web storage are shared by the whole app. Each entry starts with them empty, so the
 * session cookie of one study never travels with the entry of another and the server cannot link two studies on
 * one phone. They are emptied again when the entry ends and when the screen goes, and the WebView is destroyed with
 * it: the session cookie of an entry does not stay on the phone for days (audit 2026-10-13, AN-4).
 */
class EntryActivity : ComponentActivity() {
    private val session: EntrySession by viewModels()
    private var url: String? get() = session.url; set(value) { session.url = value; session.state["url"] = value }
    private var offline: OfflinePages.Page? get() = session.offline; set(value) { session.offline = value }
    private var error: String? get() = session.error; set(value) { session.error = value }
    private var saved: Boolean get() = session.saved; set(value) { session.saved = value }
    private var done: Boolean get() = session.done; set(value) { session.done = value }
    private var saving: Boolean get() = session.saving; set(value) { session.saving = value }
    private var offlineAnswerId: String get() = session.offlineAnswerId; set(value) { session.offlineAnswerId = value }

    /** [error] is one that may pass (no connection, a fault): the screen offers "Try again" (audit A8). */
    private var retryable: Boolean get() = session.retryable; set(value) { session.retryable = value }

    /** The page in the WebView did not load (no connection on the way, audit A7): a native error state over it. */
    private var loadFailed: Boolean get() = session.loadFailed; set(value) { session.loadFailed = value }

    /** Loading progress of the page in the WebView, 0..100: a bar under the top bar while below 100. */
    private var pageProgress: Int get() = session.pageProgress; set(value) { session.pageProgress = value }

    /** The question before the entry is closed with a page that may hold answers not yet sent (audit A6). */
    private var askAbort: Boolean get() = session.askAbort; set(value) { session.askAbort = value }
    private var askLeave: Boolean get() = session.askLeave; set(value) { session.askLeave = value }

    /** [error] asks to contact the researchers: the study's contact (if any) is offered with it (A10). */
    private var askTeam: Boolean get() = session.askTeam; set(value) { session.askTeam = value }

    /** The prompt's notification is gone: removed only once the entry has opened, so it stays as the way back (A8). */
    private var cleared: Boolean get() = session.cleared; set(value) { session.cleared = value }

    /** The extras of the intent: the study, the module, the prompt. */
    private var code = ""
    private var module = ""
    private var due: Long? = null
    private var notified: Long? = null
    private var reminded = false

    /** When the kept page was shown: "started" of the queued answer. */
    private var offlineStarted: Long get() = session.offlineStarted; set(value) { session.offlineStarted = value }

    /** The server of the study (Study.server): the only site whose pages may use the bridge. */
    private var server = Api.defaultServer

    /**
     * The study of the entry, read off the main thread when the screen starts (opening it asks the Keystore, audit
     * 2026-10-18, AND-09); null until then, and the screen shows the spinner meanwhile.
     */
    private var study by mutableStateOf<Study?>(null)

    /** The WebView of this entry, to check that a call of the bridge comes from the page of the server. */
    private var web: WebView? get() = session.web; set(value) { session.web = value }


    private var restored by mutableStateOf(false)
    /** The banner of a permission a question needs and the participant denied: the text naming that one (microphone or location). */
    private var permissionNotice by mutableStateOf<Int?>(null)
    private var mediaRequest: PermissionRequest? = null
    private var locationRequest: Pair<String, GeolocationPermissions.Callback>? = null
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        mediaRequest?.let { request ->
            if (grants[Manifest.permission.RECORD_AUDIO] == true && trusted(request.origin.toString())) request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            else { request.deny(); permissionNotice = R.string.permission_microphone }
        }; mediaRequest = null
        locationRequest?.let { (origin, callback) ->
            val granted = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true || grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
            callback.invoke(origin, granted && trusted(origin), false)
            if (!granted) permissionNotice = R.string.permission_location
        }; locationRequest = null
    }
    /**
     * The phone's file picker for an `<input type=file>` of the page (the file alternative of a voice question,
     * accepting audio files): the chosen file goes to the page, cancelling hands it nothing (null). The WebView reads the
     * picked content address itself, so the pages still reach no files or content providers (allowFileAccess,
     * allowContentAccess stay off).
     */
    private val fileChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = session.fileCallback ?: return@registerForActivityResult
        session.fileCallback = null
        // another app's answer: checked before the page gets it (PickedFiles)
        callback.onReceiveValue(PickedFiles.checked(this, WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)))
    }
    private fun trusted(address: String): Boolean = runCatching {
        val uri = java.net.URI(address)
        uri.userInfo == null && Api.sameOrigin(uri, java.net.URI(server)) && Api.isKnown(server)
    }.getOrDefault(false)
    private fun attachChrome(view: WebView) {
        view.settings.textZoom = (resources.configuration.fontScale * 100).toInt().coerceIn(100, 300)
        view.webChromeClient = object: WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) { pageProgress = newProgress }
            override fun onPermissionRequest(request: PermissionRequest) { runOnUiThread {
                if (!trusted(request.origin.toString()) || offline != null || PermissionRequest.RESOURCE_AUDIO_CAPTURE !in request.resources) { request.deny(); return@runOnUiThread }
                if (ContextCompat.checkSelfPermission(this@EntryActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                else { mediaRequest?.deny(); mediaRequest = request; permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO)) }
            } }
            override fun onPermissionRequestCanceled(request: PermissionRequest) { if (mediaRequest == request) mediaRequest = null }
            // only for a page of the study's own server online (as the microphone); false: the WebView picks nothing
            override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                if (!trusted(view.url.orEmpty()) || offline != null) return false
                // a picker still open for an earlier input gets nothing: one at a time
                session.fileCallback?.onReceiveValue(null)
                session.fileCallback = callback
                return try {
                    fileChooser.launch(params.createIntent())
                    true
                } catch (e: ActivityNotFoundException) {
                    // no app on the phone to pick a file with
                    session.fileCallback = null
                    false
                }
            }
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                if (!trusted(origin) || offline != null) { callback.invoke(origin, false, false); return }
                if (ContextCompat.checkSelfPermission(this@EntryActivity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) callback.invoke(origin, true, false)
                else { locationRequest = origin to callback; permissions.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        restored = savedInstanceState != null && web == null && session.state.get<String>("draft") != null
        session.activity = java.lang.ref.WeakReference(this)
        current = java.lang.ref.WeakReference(this)
        enableEdgeToEdge()
        // the answers are nobody else's business: no screenshots, no picture in the list of recent apps
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // the entry is listed in "Recents", so that it survives a switch to another app (audit A5); without a picture
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        // turning the phone does not recreate the activity (android:configChanges); anything else that does (the
        // system took the app's memory, a change of language) keeps what was answered already
        if (savedInstanceState != null) {
            offlineAnswerId = savedInstanceState.getString("offline_answer_id") ?: offlineAnswerId
            done = savedInstanceState.getBoolean(STATE_DONE)
            saved = savedInstanceState.getBoolean(STATE_SAVED)
            error = savedInstanceState.getString(STATE_ERROR)
            retryable = savedInstanceState.getBoolean(STATE_RETRY)
            askTeam = savedInstanceState.getBoolean(STATE_TEAM)
            cleared = savedInstanceState.getBoolean(STATE_CLEARED)
            offlineStarted = savedInstanceState.getLong(STATE_STARTED)
            if (done && !saved && error == null) return finish()
        }
        code = intent.getStringExtra(EXTRA_STUDY) ?: return finish()
        module = intent.getStringExtra(EXTRA_MODULE) ?: return finish()
        due = intent.getLongExtra(EXTRA_DUE, 0L).takeIf { it > 0 }
        notified = intent.getLongExtra(EXTRA_NOTIFIED, 0L).takeIf { it > 0 }
        reminded = intent.getBooleanExtra(EXTRA_REMINDED, false)
        // the stored study, the prompt log and the kept page are read off the main thread: they are opened with the
        // Keystore and read from files (audit 2026-10-18, AND-09); until then the screen shows the spinner
        val keptVersion = if (offline == null) session.state.get<String>("offline_version") else null
        val app = applicationContext
        lifecycleScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                // no further exit notification (the server closes the questionnaire once answered); the one shown
                // stays until the entry has opened (clearPrompt)
                if (module == EXIT) runCatching { PromptLog(app).exitNotified(code) }
                val s = StudyStore(app).get(code) ?: return@withContext null
                s to keptVersion?.let { version -> OfflinePages(app).page(code, module)?.takeIf { it.version == version } }
            }
            val (s, kept) = loaded ?: return@launch finish()
            server = s.server
            if (offline == null && kept != null) offline = kept
            web?.let { it.webViewClient = if (offline != null) offlineClient(app) else onlineClient(); attachChrome(it) }
            study = s
            if (!saved && error == null && url == null && offline == null) open(s)
        }

        setContent {
            // the bar above the page and the screen before it loads in the look of the study's pages
            val s = study
            if (s == null) StudyTheme(null, bars = true) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Box(contentAlignment = Alignment.Center) {
                        val loading = stringResource(R.string.loading)
                        CircularProgressIndicator(Modifier.semantics { contentDescription = loading })
                    }
                }
            } else StudyTheme(s.look, bars = true) {
                EntryScreen(s.title, s.contact)
            }
        }
    }

    /**
     * Asks the server for the one-time address of the entry; without a connection the kept page, if the module has
     * one. An error that may pass (no connection, a fault of the server) can be tried again from the error screen.
     */
    private fun open(study: Study) {
        error = null
        retryable = false
        askTeam = false
        lifecycleScope.launch {
            try {
                val address = Api.absolute(intent.getStringExtra(EXTRA_ADDRESS) ?: Api.entry(study.server, study.secret, module, due, notified, reminded, session.entryId), study.server)
                // a WebView without the main-frame message channel gets no page of the server ([outdatedWebView])
                if (!mainFrameBridgeSupported()) { outdatedWebView(); return@launch }
                clearWebState()
                url = address
            } catch (e: ApiError) {
                // the kept page is read from the files directory: not on the main thread
                val kept = if (e.key == "offline") withContext(Dispatchers.IO) { keptPage(study, module, due) } else null
                if (kept != null) {
                    clearWebState()
                    if (offlineStarted == 0L) offlineStarted = study.now()
                    offline = kept
                    session.state["offline_version"] = kept.version
                    clearPrompt()
                } else if (error == null) {
                    error = if (e.status == 429) getString(R.string.too_many) else when (e.key) {
                        "expired" -> getString(R.string.expired)
                        // today's limit of a self-started report (409); a 429 "limit" is the rate limit
                        "daily_limit" -> getString(R.string.limit)
                        "paused" -> getString(R.string.paused)
                        "closed", "ended" -> getString(R.string.closed)
                        "done" -> getString(R.string.already_answered)
                        "unauthorised" -> getString(R.string.study_unavailable)
                        "offline" -> getString(R.string.offline)
                        else -> getString(R.string.error)
                    }
                    retryable = retryable(e)
                    askTeam = e.key == "unauthorised"
                    // a final answer (expired, today's limit, paused): the notification has nothing left to open
                    if (!retryable) clearPrompt()
                } else {
                    // keptPage() named the reason (expired, saved already, today's limit): final as well
                    clearPrompt()
                }
            }
        }
    }

    /**
     * The entry has opened (or can never open): the prompt's notification goes and its reminder stays silent. Until
     * then the notification stays, so that a participant whose page did not load can open it again (audit A8).
     */
    private fun clearPrompt() {
        if (cleared) return
        cleared = true
        due?.let {
            Alarms.cancel(this, Prompt(code, module, it, 0).id)
            // the log is sealed with the Keystore: written off the main thread, also when the screen goes meanwhile (AND-09)
            val app = applicationContext
            val (c, m) = code to module
            lifecycleScope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { runCatching { PromptLog(app).opened(c, m, it) } }
        }
        if (module == EXIT) Alarms.cancel(this, "$code|exit".hashCode())
    }

    /**
     * The address came, but its first page did not (the connection went on the way): a module that can be answered
     * without a connection switches to its kept page, as if there had been no connection from the start (audit A7).
     */
    private fun toKeptPage() {
        val study = study ?: return
        lifecycleScope.launch {
            val kept = withContext(Dispatchers.IO) { keptPage(study, module, due) } ?: return@launch
            if (cleared || offline != null) return@launch
            clearWebState()
            if (offlineStarted == 0L) offlineStarted = study.now()
            web = null
            url = null
            loadFailed = false
            offline = kept
            clearPrompt()
        }
    }

    /** "Try again" on the error screen: the page that did not load, or a new address (audit A7, A8). */
    private fun retry() {
        val w = web
        if (loadFailed && w != null) {
            loadFailed = false
            // the kept page is no address to load again: its text is put in once more (AND-01)
            val o = offline
            if (o != null) w.loadDataWithBaseURL("$server/", o.html, "text/html", "utf-8", "$server/") else w.reload()
            return
        }
        val study = study ?: return finish()
        open(study)
    }

    /**
     * Whether closing now may lose something: a page of the questionnaire is open and the entry is not over. The
     * error and "saved" screens hold nothing (audit A6).
     */
    private val mayLose: Boolean get() = session.dirty && !done && !saved

    /**
     * Whether closing asks first (UX audit 2026-10-16, B3): with input on the open page, and online from the second
     * page on even without, since the participant may have meant "one page back" and the entry is half done.
     */
    private val askFirst: Boolean get() =
        !done && !saved && error == null && (mayLose || (offline == null && url != null && (session.state.get<Int>("pages") ?: 0) > 0))

    /**
     * The back gesture (UI/UX audit 2026-10-17, A12): on a page of the questionnaire with its own "Back" button it
     * presses that button, so the server goes one page back as it allows it (never the WebView's history, which
     * dropped the input of the open page unasked and showed a page the server had already left behind, B3). On a
     * page without one (the first page, a page after a quota check, the kept page, a page still loading) it asks
     * "Leave the questionnaire?" first. Without a page (an error, the end, the saved answer) it closes, as the X does.
     */
    private fun back() {
        if (saving) return
        val w = web
        if (w == null || done || saved || error != null || loadFailed || (url == null && offline == null)) return close()
        if (offline != null || pageProgress < 100 || !fromOwnPage()) { askLeave = true; askAbort = true; return }
        w.evaluateJavascript(PAGE_BACK) { result ->
            if (result != "true" && !isFinishing && web === w && !done && !saved && error == null) { askLeave = true; askAbort = true }
        }
    }

    /** The X in the top bar and the back gesture: the question before closing when something may be lost (A6, B3). */
    private fun close() {
        if (saving) return
        if (askFirst) { askLeave = false; askAbort = true } else finish()
    }

    /**
     * The kept page for answering [module] without a connection, if it may be answered now: a prompt still open,
     * or a self-started module under its daily limit (counting the answers waiting to be sent; those sent today
     * the phone does not know, the server checks them when the answer arrives). Sets [error] when the prompt has
     * expired or the limit is reached. Times are the study's (virtual for a fast-forward person). The entry part and
     * the exit questionnaire are always answered online.
     */
    private fun keptPage(study: Study, module: String, due: Long?): OfflinePages.Page? {
        if (module == "entry" || module == EXIT || !study.running) return null
        val m = study.modules.firstOrNull { it.key == module && it.offline } ?: return null
        val page = OfflinePages(this).usable(study.code, module) ?: return null
        val now = study.now()
        if (due == null) {
            // started by the participant: a self module, or a prompted one that may also be started so
            val t = m.selfTrigger ?: return null
            if (t.maxPerDay > 0 && AnswerQueue(this).today(study, module, now) >= t.maxPerDay) {
                error = getString(R.string.limit)
                return null
            }
        } else {
            if (m.trigger !is Trigger.Fixed && m.trigger !is Trigger.Random) return null
            if (!Schedule.open(m, due, now)) {
                error = getString(R.string.expired)
                return null
            }
            if (AnswerQueue(this).has(study.code, module, due)) {
                error = getString(R.string.offline_saved)
                return null
            }
        }
        return page
    }

    /** Empties the cookies and the web storage of the WebView; returns when the cookies are gone. */
    private suspend fun clearWebState() {
        val cookies = CookieManager.getInstance()
        suspendCancellableCoroutine { cont -> cookies.removeAllCookies { cont.resume(Unit) } }
        cookies.flush()
        WebStorage.getInstance().deleteAllData()
    }

    /**
     * The entry is over (the end page, an answer saved without a connection) or its screen goes: its session cookie
     * and web storage go too (AN-4). Not when another entry has started meanwhile (a new prompt opens a new screen,
     * and this one is destroyed only afterwards): that one's cookie must stay.
     */
    private fun forgetWebState() {
        if (current?.get() !== this) return
        CookieManager.getInstance().apply {
            removeAllCookies(null)
            flush()
        }
        WebStorage.getInstance().deleteAllData()
    }

    /**
     * The WebView's renderer went (the system took its memory): without this the whole app would crash. The WebView
     * goes ([release] through Compose), the screen offers "Try again" (a new address, or the kept page again); after
     * the end page there is nothing left to show.
     */
    private fun renderGone(): Boolean {
        if (done && !saved) {
            finish()
            return true
        }
        url = null
        offline = null
        loadFailed = false
        if (!saved) {
            error = getString(R.string.load_failed)
            retryable = true
        }
        return true
    }

    /** The WebView [w] is no longer shown: it stops, loses the bridge and is destroyed (AN-4). */
    private fun release(w: WebView) {
        if (isChangingConfigurations && web === w) { (w.parent as? ViewGroup)?.removeView(w); return }
        if (web === w) web = null
        runCatching {
            w.stopLoading()
            stopListening(w)
            w.removeJavascriptInterface(BRIDGE)
            w.loadUrl("about:blank")
            (w.parent as? ViewGroup)?.removeView(w)
            w.destroy()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_DONE, done)
        outState.putString("offline_answer_id", offlineAnswerId)
        outState.putBoolean(STATE_SAVED, saved)
        outState.putString(STATE_ERROR, error)
        outState.putBoolean(STATE_RETRY, retryable)
        outState.putBoolean(STATE_TEAM, askTeam)
        // the system may now end the process: the entry's session cookie is written to disk first, so that the entry
        // address opens this same entry again when the screen comes back (A11); it goes when the entry ends (AN-4)
        if (!isFinishing && url != null && !done) CookieManager.getInstance().flush()
        outState.putBoolean(STATE_CLEARED, cleared)
        outState.putLong(STATE_STARTED, offlineStarted)
    }

    override fun onDestroy() {
        // the WebView itself is destroyed when Compose lets it go (AndroidView onRelease, [release])
        mediaRequest?.deny(); mediaRequest = null
        locationRequest?.let { it.second.invoke(it.first, false, false) }; locationRequest = null
        (web?.parent as? ViewGroup)?.removeView(web)
        if (isFinishing) forgetWebState()
        if (session.activity?.get() === this) session.activity = null
        if (current?.get() === this) current = null
        super.onDestroy()
    }

    /**
     * Whether the page now in the WebView is one of the server (online) or the kept page under its address: only then
     * may a call of the bridge count. The bridge is reachable from every frame; the participant pages have none, and
     * a page of another site must not be able to end an entry or queue an answer.
     */
    private fun fromOwnPage(): Boolean {
        val current = web?.url ?: return false
        return runCatching { Api.sameOrigin(java.net.URI(current), java.net.URI(server)) }.getOrDefault(false)
    }

    /**
     * Opens a link of the page in another app: only web, mail and phone links, and nothing happens when no app on the
     * phone takes it (an unknown scheme must not crash the entry).
     */
    private fun openOutside(uri: Uri) {
        val scheme = uri.scheme?.lowercase() ?: return
        if (scheme !in OUTSIDE_SCHEMES) return
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: ActivityNotFoundException) {
            // nothing to open it with
        } catch (e: SecurityException) {
            // the only app for it does not take links from others
        }
    }

    private fun finished() {
        if (done) return
        done = true
        session.dirty = false; session.state["dirty"] = false; session.state.remove<String>("draft")
        val app = applicationContext
        val (c, m) = code to module
        // the log is sealed with the Keystore: written off the main thread (AND-09)
        due?.let { d -> lifecycleScope.launch(Dispatchers.IO + kotlinx.coroutines.NonCancellable) { runCatching { PromptLog(app).completed(c, m, d) } } }
        // the end page is shown: the session of the entry is no longer needed (AN-4)
        forgetWebState()
        val code = intent.getStringExtra(EXTRA_STUDY) ?: return
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { StudyStore(app).get(code) }?.let { runCatching { Sync.one(this@EntryActivity, it) } }
        }
    }

    /** The kept page was submitted: the answer waits in the queue, the participant sees that it was saved. */
    private fun offlineSubmitted(json: String) {
        val page = offline ?: return
        if (done || saving) return
        // only a JSON object goes into the queue: the server takes nothing else
        val form = runCatching { JSONObject(json) }.getOrNull()?.toString() ?: return
        val code = intent.getStringExtra(EXTRA_STUDY) ?: return
        val module = intent.getStringExtra(EXTRA_MODULE) ?: return
        val due = intent.getLongExtra(EXTRA_DUE, 0L).takeIf { it > 0 }
        val notified = intent.getLongExtra(EXTRA_NOTIFIED, 0L).takeIf { it > 0 }
        // the study read when the screen started (not again from the store on the main thread, AND-09)
        val completed = study?.takeIf { it.code == code }?.now() ?: System.currentTimeMillis()
        saving = true
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO + kotlinx.coroutines.NonCancellable) {
                    AnswerQueue(this@EntryActivity).add(code, module, due, notified, offlineStarted, completed, page.version, form, offlineAnswerId, moduleName = StudyStore(this@EntryActivity).get(code)?.modules?.firstOrNull { it.key == module }?.name.orEmpty())
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the filled WebView open. A failed write must never show the saved screen. The banner above
                // the page stays until the answer is saved; "Save again" sends the same form (UX audit 2026-10-16, B8)
                session.unsaved = form
                return@launch
            } finally {
                saving = false
            }
            session.unsaved = null
            done = true
            saved = true
            forgetWebState()
            // A scheduling failure cannot turn an already durable save into a failed save.
            runCatching { SyncWorker.sendSoon(this@EntryActivity) }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @androidx.compose.runtime.Composable
    private fun EntryScreen(title: String, contact: String) {
        BackHandler { back() }
        Scaffold(bottomBar = {
            // the end page of the questionnaire was reached: "Done" closes the entry, the same as the X, but where the
            // thumb is and with the words of the end (B33)
            if (done && !saved && error == null) Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                Button(onClick = { finish() }, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                    Text(stringResource(R.string.done))
                }
            }
        }, topBar = {
            Column {
                PageHeader(title, onClose = { close() }, compact = true)
                // a page is loading: the questionnaire has no indicator of its own between its pages (audit A7)
                val loading = stringResource(R.string.loading)
                if (url != null && pageProgress < 100 && !loadFailed && error == null) {
                    LinearProgressIndicator(
                        progress = { pageProgress / 100f },
                        modifier = Modifier.fillMaxWidth().semantics { contentDescription = loading },
                    )
                }
            }
        }) { pad ->
            // imePadding: the window does not resize for the keyboard (edge to edge), so the page is shortened here;
            // otherwise the WebView scrolls the caret into view anew with every letter of a long text answer. The bars'
            // insets are in the scaffold's padding already: consumed, so the keyboard does not add them again (B21)
            Column(Modifier.padding(pad).consumeWindowInsets(pad).imePadding().fillMaxSize()) {
            // the notices of the entry as banners between the top bar and the page, never over its questions; each
            // can be closed (UX audit 2026-10-16, B2), the failed save only by saving (B8)
            session.unsaved?.let { form ->
                Banner(
                    stringResource(R.string.save_failed), error = true, action = stringResource(R.string.save_again),
                    actionEnabled = !saving, onAction = { offlineSubmitted(form) },
                )
            }
            if (restored) Banner(stringResource(R.string.restored), onClose = { restored = false })
            permissionNotice?.let { notice ->
                Banner(
                    stringResource(notice), action = stringResource(R.string.open_settings),
                    onAction = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())) },
                    onClose = { permissionNotice = null },
                )
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val u = url
                val o = offline
                // no white flash before a dark page has loaded
                val page = MaterialTheme.colorScheme.background.toArgb()
                val e = error
                when {
                    e != null || saved -> Message(e ?: stringResource(R.string.offline_saved), retry = e != null && retryable, contact = if (e != null && askTeam) contact else "")
                    u != null -> AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx -> (web ?: webView(ctx.applicationContext, u)).apply { (parent as? ViewGroup)?.removeView(this); setBackgroundColor(page) } },
                        onRelease = { release(it) },
                    )
                    o != null -> AndroidView(
                        modifier = Modifier.fillMaxSize(),
                        factory = { ctx -> (web ?: offlineWebView(ctx.applicationContext, o)).apply { (parent as? ViewGroup)?.removeView(this); setBackgroundColor(page) } },
                        onRelease = { release(it) },
                    )
                    else -> {
                        val loading = stringResource(R.string.loading)
                        CircularProgressIndicator(Modifier.semantics { contentDescription = loading })
                    }
                }
                // the page did not load: the app's own error state over the WebView, never the browser's page (A7)
                if (loadFailed && e == null && !saved) {
                    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                        Box(contentAlignment = Alignment.Center) { Message(stringResource(R.string.load_failed), retry = true) }
                    }
                }
            }
            }
        }
        if (askAbort) {
            AlertDialog(
                onDismissRequest = { askAbort = false },
                // the X cancels the entry; the back gesture on a page without "Back" leaves the questionnaire (A12)
                title = { Text(stringResource(if (askLeave) R.string.entry_leave_title else R.string.entry_abort_title)) },
                text = {
                    // what goes, and whether the entry can be continued (B3): the server keeps the pages sent so far
                    // and opens the same entry again while it is open; a kept page sends nothing before its end.
                    // Scrolls at a large font size (A01)
                    val lost = if (mayLose) stringResource(R.string.entry_abort_text) + " " else ""
                    DialogText { Text(lost + stringResource(if (offline != null) R.string.entry_abort_offline else R.string.entry_abort_resume)) }
                },
                confirmButton = {
                    TextButton(onClick = { askAbort = false; finish() }) {
                        Text(stringResource(if (askLeave) R.string.entry_leave else R.string.entry_abort), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = { askAbort = false }) { Text(stringResource(R.string.entry_keep)) } },
            )
        }
    }

    /**
     * A message instead of the page; "Try again" as the main button when it may help (audit A8). A message that asks to
     * contact the researchers comes with the study's [contact] and its actions, when the study has one (A10).
     */
    @androidx.compose.runtime.Composable
    private fun Message(text: String, retry: Boolean, contact: String = "") {
        Column(Modifier.padding(24.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            // read out as soon as it shows (it replaces the page or the spinner)
            Text(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (contact.isNotBlank()) Box(Modifier.padding(top = 16.dp)) { StudyContact(contact) }
            if (retry) {
                Button(onClick = { retry() }, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.retry)) }
                OutlinedButton(onClick = { close() }, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.close)) }
            } else {
                Button(onClick = { close() }, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.close)) }
            }
        }
    }

    /**
     * Whether this phone's WebView offers the bridge as a message channel of the main frame (WEB_MESSAGE_LISTENER and
     * DOCUMENT_START_SCRIPT). Without it the server's pages are not opened: addJavascriptInterface would reach every
     * frame of the page, and the app could only check the address of the main page, not the frame that calls.
     */
    private fun mainFrameBridgeSupported(): Boolean = Api.origin(server) != null &&
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
        WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    /**
     * The WebView is too old for the bridge of the server's pages: a message asks to update Android System WebView
     * instead of the page. "Try again" checks again (after the update); the prompt's notification stays, so the entry
     * can be opened from it once the WebView is updated.
     */
    private fun outdatedWebView() {
        url = null
        error = getString(R.string.webview_outdated)
        retryable = true
    }

    /**
     * [online]: the page comes from the server. Its bridge is a message channel that only the main frame of the
     * server's own origin reaches (audit 2026-10-13, AN-12): addWebMessageListener with that origin as the only rule,
     * and a script at the start of each document of that origin that offers participant.js the same names as before
     * (window.AskuriousApp.done, .offlineSubmit). A WebView too old for it gets no bridge and no page of the server
     * ([webView], [outdatedWebView]). Only the kept page (whose WebView loads nothing from any other origin,
     * [offlineWebView]) uses addJavascriptInterface, which [fromOwnPage] guards.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun baseWebView(ctx: Context, online: Boolean): WebView {
        CookieManager.getInstance().setAcceptCookie(true)
        return WebView(ctx.applicationContext).apply {
            // nothing of an earlier entry (another study) is taken from the cache
            clearCache(true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            // no files and no content providers of the phone: the pages come from the server or from OfflinePages
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            // autofill services do not see (nor offer) what is typed into a questionnaire
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
            settings.userAgentString = settings.userAgentString + " AskuriousDiary/1"
            // online only the message channel of the main frame (webView loads nothing without it); the interface,
            // which every frame reaches, only for the kept page
            if (online) listenOnMainFrame(this) else addJavascriptInterface(Bridge(session), BRIDGE)
            settings.textZoom = (resources.configuration.fontScale * 100).toInt().coerceIn(100, 300)
            web = this
            attachChrome(this)
        }
    }

    /** The bridge as a message channel of the main frame of the server's origin (AN-12); false when not supported. */
    private fun listenOnMainFrame(w: WebView): Boolean {
        val origin = Api.origin(server) ?: return false
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return false
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return false
        return try {
            val retained = session
            WebViewCompat.addWebMessageListener(w, PORT, setOf(origin)) { _, message, source, isMainFrame, _ ->
                retained.activity?.get()?.let { activity ->
                    if (isMainFrame && Api.origin(source.toString()) == origin && activity.fromOwnPage()) activity.bridgeMessage(message.data)
                }
            }
            listening.add(w)
            WebViewCompat.addDocumentStartJavaScript(w, BRIDGE_SCRIPT, setOf(origin))
            true
        } catch (e: RuntimeException) {
            // an origin rule the WebView does not take: no bridge, and webView loads no page
            stopListening(w)
            false
        }
    }

    /** Removes the message listener of [w], if it has one. */
    private fun stopListening(w: WebView) {
        if (!listening.remove(w)) return
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            runCatching { WebViewCompat.removeWebMessageListener(w, PORT) }
        }
    }

    /** A message of the bridge script ([BRIDGE_SCRIPT]): "done", or "offline:" with the form as JSON. */
    private fun bridgeMessage(data: String?) {
        when {
            data == null -> {}
            data == "done" -> finished()
            data == "changed" -> changed()
            data.startsWith("loaded:") -> draftPage(data.removePrefix("loaded:"))
            data.startsWith("draft:") -> draft(data.removePrefix("draft:"))
            data.startsWith("offline:") -> offlineSubmitted(data.removePrefix("offline:"))
        }
    }

    private fun webView(ctx: Context, start: String): WebView {
        val host = start.toUri().host
        return baseWebView(ctx, online = true).apply {
            webViewClient = onlineClient()
            attachChrome(this)
            // without the message channel (an old WebView, an origin rule it does not take) the page is not loaded
            if (this in listening) loadUrl(start)
            else android.os.Handler(android.os.Looper.getMainLooper()).post { outdatedWebView() }
        }
    }

    private fun onlineClient(): WebViewClient = object : WebViewClient() {
                /** The page now loading failed (its main frame): its onPageFinished is no success. */
                private var failed = false

                // pages of the study stay in the app; any other link (privacy statement of an institution, ...)
                // opens in the browser
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (trusted(request.url.toString())) return false
                    openOutside(request.url)
                    return true
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                    failed = false
                }

                // no connection on the way to a page: the app's own error state with "Try again" instead of the
                // browser's English page (audit A7); a missing picture or style sheet (no main frame) does not count
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (!request.isForMainFrame) return
                    failed = true
                    loadFailed = true
                    // the first page did not come for want of a connection: the kept page, if the module has one
                    if (!cleared && error.errorCode in OFFLINE_ERRORS) toKeptPage()
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame) { failed = true; loadFailed = true }
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean = renderGone()

                override fun onPageFinished(view: WebView, url: String?) {
                    // the first page of the entry is there: now its notification may go (audit A8)
                    if (!failed) { clearPrompt(); attachDraft(view) }
                }
            }

    /**
     * The kept page under the server's address, so its absolute paths resolve; its /static/ and /media/ files come
     * from the phone. Every other request fails here: the page must not reach the network, nor half of a page load
     * from the server if the connection comes back meanwhile.
     */
    private fun offlineWebView(ctx: Context, page: OfflinePages.Page): WebView {
        return baseWebView(ctx, online = false).apply {
            webViewClient = offlineClient(ctx)
            // the history URL is the server's too, so that url (fromOwnPage) names it
            loadDataWithBaseURL("$server/", page.html, "text/html", "utf-8", "$server/")
        }
    }

    private fun offlineClient(ctx: Context): WebViewClient {
        val base = server.toUri()
        val pages = OfflinePages(ctx)
        return object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val u = request.url
                    // the kept page itself: the document loadDataWithBaseURL puts in (a data: address, which reaches
                    // no network) loads as it is. Answered with 404, as before, the WebView showed its own error page
                    // instead of the questionnaire (audit 2026-10-18, AND-01)
                    if (offlinePassThrough(u.scheme)) return null
                    // a WebView that asks for the page under its base address instead gets the kept page from here
                    if (request.isForMainFrame && request.method.equals("GET", ignoreCase = true) && u.toString() == "$server/") {
                        offline?.let { return WebResourceResponse("text/html", "utf-8", ByteArrayInputStream(it.html.toByteArray())) }
                    }
                    val path = (u.encodedPath ?: "") + (u.encodedQuery?.let { "?$it" } ?: "")
                    val kept = if (u.scheme == base.scheme && u.host == base.host && u.port == base.port && OfflinePages.servable(path)) {
                        pages.file(server, path)
                    } else {
                        null
                    }
                    if (kept == null) return WebResourceResponse("text/plain", "utf-8", 404, "Not Found", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                    val (file, type) = kept
                    val mime = type.substringBefore(';').trim()
                    val charset = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)?.trim()
                        ?: if (mime.startsWith("text/")) "utf-8" else null
                    return WebResourceResponse(mime, charset, file.inputStream())
                }

                override fun onPageFinished(view: WebView, url: String?) { attachDraft(view) }

                // the kept page did not load: the app's own error state with "Try again", never the browser's page (AND-01)
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) loadFailed = true
                }

                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame) loadFailed = true
                }

                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean = renderGone()

                // links leave the app as on the online page; nothing loads in place of the kept page
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.host != base.host) openOutside(request.url)
                    return true
                }
            }
    }

    private fun attachDraft(view: WebView) {
        val draft = draftLiteral(session.state.get<String>("draft"))
        view.evaluateJavascript("""
            (() => {
                if (window.__askDraft) return; window.__askDraft = true;
                const old = $draft;
                const page = location.href + '|' + (document.querySelector('main')?.dataset.page || '') + '|' +
                    Array.from(document.querySelectorAll('form input:not([type=hidden]), form textarea, form select')).map(el => el.name).join('|');
                AskuriousApp.loaded(page);
                const fields = () => Array.from(document.querySelectorAll('form input, form textarea, form select'));
                if (old && old.page === page) {
                    const filled = [];
                    fields().forEach((el, n) => {
                        const v = old.values[n]; if (!v || v.name !== el.name || el.type === 'file' || el.type === 'password' || el.type === 'hidden') return;
                        el.value = v.value; el.checked = v.checked;
                        if (v.selected) Array.from(el.options).forEach((o, i) => o.selected = v.selected[i]);
                        filled.push(el);
                    });
                    // the page reacts to restored answers as to typed ones: display logic, piping, counters (A11)
                    filled.forEach(el => { el.dispatchEvent(new Event('input', {bubbles: true})); el.dispatchEvent(new Event('change', {bubbles: true})); });
                }
                const keep = () => AskuriousApp.draft(JSON.stringify({url:location.href,page:page,values:fields().map(el =>
                    ({name:el.name,value:el.type === 'file' || el.type === 'password' || el.type === 'hidden' ? '' : el.value,checked:el.checked,
                    selected:el.options ? Array.from(el.options).map(o => o.selected) : null}))}));
                document.addEventListener('input', keep); document.addEventListener('change', keep);
                document.addEventListener('pointerdown', e => { if(e.target.closest('form canvas, form svg')) AskuriousApp.changed(); });
                document.addEventListener('click', e => {if(e.target.closest('form button, form canvas, form svg')) AskuriousApp.changed();});
            })();
        """.trimIndent(), null)
    }


    private fun changed() { if (!done) { session.dirty = true; session.state["dirty"] = true } }
    private fun draftPage(page: String) {
        val previous = session.state.get<String>("page")
        if (previous != null && previous != page) {
            session.dirty = false; session.state["dirty"] = false; session.state.remove<String>("draft")
            // a further page of the questionnaire: closing asks from now on (B3); the restored answers were seen (B2)
            session.state["pages"] = (session.state.get<Int>("pages") ?: 0) + 1
            restored = false
        }
        session.state["page"] = page
    }
    private fun draft(json: String) {
        if (done || json.length > 200_000) return
        // kept as the strictly written JSON text, never as the page sent it (org.json also takes unquoted words such as
        // alert(1)); it goes back into the page only as a string for JSON.parse ([draftLiteral], AND-04)
        val strict = runCatching { JSONObject(json).toString() }.getOrNull() ?: return
        session.state["draft"] = strict; changed()
    }

    /**
     * The bridge for addJavascriptInterface: the kept page, and the online page of a WebView too old for the message
     * channel. Every frame of the page reaches it; [drafts] false (online) takes no drafts there (AND-04).
     */
    class Bridge(private val session: EntrySession, private val drafts: Boolean = true) {
        private fun send(data: String) { android.os.Handler(android.os.Looper.getMainLooper()).post {
            session.activity?.get()?.let { if (it.fromOwnPage()) it.bridgeMessage(data) }
        } }
        @JavascriptInterface fun done() = send("done")
        @JavascriptInterface fun offlineSubmit(json: String) = send("offline:$json")
        @JavascriptInterface fun loaded(page: String) = send("loaded:$page")
        @JavascriptInterface fun changed() = send("changed")
        @JavascriptInterface fun draft(json: String) { if (drafts) send("draft:$json") }
    }

    companion object {
        /**
         * The name participant.js calls the bridge by (window.AskuriousApp.done, .offlineSubmit): the server's
         * tests/test_diary_app_bridge.py runs the pages against these names, BridgeContractTest checks them here.
         */
        const val BRIDGE = "AskuriousApp"

        /** The name of the message channel the bridge script talks through (AN-12); participant.js does not see it. */
        private const val PORT = "AskuriousAppPort"

        /**
         * Offers participant.js window.AskuriousApp in the main frame of the server's pages, as a message channel
         * (AN-12). A frame inside the page gets nothing, and the app takes messages only from the main frame anyway.
         */
        const val BRIDGE_SCRIPT = """(function () {
  if (window.top !== window || !window.$PORT) return;
  var port = window.$PORT;
  window.$BRIDGE = {
    done: function () { port.postMessage("done"); },
    offlineSubmit: function (json) { port.postMessage("offline:" + String(json)); },
    loaded: function (page) { port.postMessage("loaded:" + String(page)); },
    changed: function () { port.postMessage("changed"); },
    draft: function (json) { port.postMessage("draft:" + String(json)); }
  };
})();"""

        /** The entry shown now: only it may empty the cookies (a new entry may start before an old one is destroyed). */
        private var current: java.lang.ref.WeakReference<EntryActivity>? = null

        /** The WebViews with a message listener, to remove it before they are destroyed. */
        private val listening = java.util.Collections.newSetFromMap(java.util.WeakHashMap<WebView, Boolean>())

        private const val EXTRA_ADDRESS = "address"
        private const val EXTRA_STUDY = "study"
        private const val EXTRA_MODULE = "module"
        private const val EXTRA_DUE = "due"
        private const val EXTRA_NOTIFIED = "notified"
        private const val EXTRA_REMINDED = "reminded"
        private const val STATE_DONE = "done"
        private const val STATE_SAVED = "saved"
        private const val STATE_ERROR = "error"
        private const val STATE_STARTED = "started"
        private const val STATE_RETRY = "retry"
        private const val STATE_CLEARED = "cleared"
        private const val STATE_TEAM = "team"

        /**
         * Presses the page's own "Back" (participant/_nav.html: button.p-back, name "nav", value "back", in the form of
         * the page) and says whether there was one (A12). The server then shows the page before, as it allows it.
         */
        const val PAGE_BACK = """(function () {
  var b = document.querySelector('form button.p-back[name="nav"][value="back"]');
  if (!b || b.disabled) return false;
  b.click();
  return true;
})()"""

        /** The WebView's errors that mean no connection (not a page the server refused). */
        private val OFFLINE_ERRORS = setOf(
            WebViewClient.ERROR_HOST_LOOKUP, WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_TIMEOUT, WebViewClient.ERROR_IO,
        )

        /**
         * Whether "Try again" may help after [e]: no connection, the rate limit, a fault of the server or an answer it
         * could not read; not an expired prompt, today's limit or a paused study (audit A8).
         */
        fun retryable(e: ApiError): Boolean = e.transient || e.key == "error"

        /**
         * The draft of the open page as a JavaScript expression: a string literal that the page reads with JSON.parse,
         * so a draft is data and never code (AND-04: it was put in as it came, `const old = <draft>;`, and ran as
         * script at every load of the entry). "null" when there is none or it is no JSON object.
         */
        fun draftLiteral(draft: String?): String {
            val strict = draft?.let { runCatching { JSONObject(it).toString() }.getOrNull() } ?: return "null"
            return "JSON.parse(" + JSONObject.quote(strict) + ")"
        }

        /**
         * Requests of the kept page's WebView that load as they are: the document loadDataWithBaseURL puts in (data:)
         * and the blank page when the WebView goes (about:). They reach no network (AND-01).
         */
        fun offlinePassThrough(scheme: String?): Boolean = scheme?.lowercase() in setOf("data", "about")

        /** The links of a page that may open in another app. */
        private val OUTSIDE_SCHEMES = setOf("http", "https", "mailto", "tel")

        /** The module key of the exit questionnaire (POST /entry {"module": "exit"}, without due). */
        const val EXIT = "exit"

        fun intent(
            context: Context, study: String, module: String, due: Long? = null, notified: Long? = null, reminded: Boolean = false, address: String? = null,
        ): Intent =
            Intent(context, EntryActivity::class.java)
                .putExtra(EXTRA_ADDRESS, address)
                .putExtra(EXTRA_STUDY, study).putExtra(EXTRA_MODULE, module)
                .putExtra(EXTRA_DUE, due ?: 0L).putExtra(EXTRA_NOTIFIED, notified ?: 0L)
                .putExtra(EXTRA_REMINDED, reminded)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
