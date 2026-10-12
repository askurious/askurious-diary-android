package de.askurious.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
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
 * AskuriousApp.done() (participant/end.html in app mode), and the activity closes.
 *
 * Without a connection, a module that can be answered offline shows the page the app kept (OfflinePages): its
 * participant.js hands the filled form to AskuriousApp.offlineSubmit() instead of sending it, and the app queues
 * the answer (AnswerQueue) until it has a connection. The entry part is never answered this way.
 *
 * The WebView's cookies and web storage are shared by the whole app. Each entry starts with them empty, so the
 * session cookie of one study never travels with the entry of another and the server cannot link two studies on
 * one phone.
 */
class EntryActivity : ComponentActivity() {
    private var url by mutableStateOf<String?>(null)
    private var offline by mutableStateOf<OfflinePages.Page?>(null)
    private var error by mutableStateOf<String?>(null)
    private var saved by mutableStateOf(false)
    private var done = false

    /** [error] is one that may pass (no connection, a fault): the screen offers "Try again" (audit A8). */
    private var retryable by mutableStateOf(false)

    /** The page in the WebView did not load (no connection on the way, audit A7): a native error state over it. */
    private var loadFailed by mutableStateOf(false)

    /** Loading progress of the page in the WebView, 0..100: a bar under the top bar while below 100. */
    private var pageProgress by mutableIntStateOf(100)

    /** The question before the entry is closed with a page that may hold answers not yet sent (audit A6). */
    private var askAbort by mutableStateOf(false)

    /** The prompt's notification is gone: removed only once the entry has opened, so it stays as the way back (A8). */
    private var cleared = false

    /** The extras of the intent: the study, the module, the prompt. */
    private var code = ""
    private var module = ""
    private var due: Long? = null
    private var notified: Long? = null
    private var reminded = false

    /** When the kept page was shown: "started" of the queued answer. */
    private var offlineStarted = 0L

    /** The server of the study (Study.server): the only site whose pages may use the bridge. */
    private var server = Api.defaultServer

    /** The WebView of this entry, to check that a call of the bridge comes from the page of the server. */
    private var web: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // the answers are nobody else's business: no screenshots, no picture in the list of recent apps
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        // the entry is listed in "Recents", so that it survives a switch to another app (audit A5); without a picture
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false)
        // turning the phone does not recreate the activity (android:configChanges); anything else that does (the
        // system took the app's memory, a change of language) keeps what was answered already
        if (savedInstanceState != null) {
            done = savedInstanceState.getBoolean(STATE_DONE)
            saved = savedInstanceState.getBoolean(STATE_SAVED)
            error = savedInstanceState.getString(STATE_ERROR)
            retryable = savedInstanceState.getBoolean(STATE_RETRY)
            cleared = savedInstanceState.getBoolean(STATE_CLEARED)
            offlineStarted = savedInstanceState.getLong(STATE_STARTED)
            if (done && !saved && error == null) return finish()
        }
        code = intent.getStringExtra(EXTRA_STUDY) ?: return finish()
        module = intent.getStringExtra(EXTRA_MODULE) ?: return finish()
        due = intent.getLongExtra(EXTRA_DUE, 0L).takeIf { it > 0 }
        notified = intent.getLongExtra(EXTRA_NOTIFIED, 0L).takeIf { it > 0 }
        reminded = intent.getBooleanExtra(EXTRA_REMINDED, false)
        if (module == EXIT) {
            // no further exit notification (the server closes the questionnaire once answered); the one shown stays
            // until the entry has opened (clearPrompt)
            PromptLog(this).exitNotified(code)
        }

        val study = StudyStore(this).get(code) ?: return finish()
        server = study.server
        if (!saved && error == null && url == null && offline == null) open(study)

        setContent {
            // the bar above the page and the screen before it loads in the look of the study's pages
            StudyTheme(study.look, bars = true) {
                EntryScreen(study.title)
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
        lifecycleScope.launch {
            try {
                val address = Api.absolute(Api.entry(study.server, study.secret, module, due, notified, reminded), study.server)
                clearWebState()
                url = address
            } catch (e: ApiError) {
                // the kept page is read from the files directory: not on the main thread
                val kept = if (e.key == "offline") withContext(Dispatchers.IO) { keptPage(study, module, due) } else null
                if (kept != null) {
                    clearWebState()
                    if (offlineStarted == 0L) offlineStarted = study.now()
                    offline = kept
                    clearPrompt()
                } else if (error == null) {
                    error = when (e.key) {
                        "expired" -> getString(R.string.expired)
                        // today's limit of a self-started report (409); a 429 "limit" is the rate limit
                        "daily_limit" -> getString(R.string.limit)
                        "paused" -> getString(R.string.paused)
                        "offline" -> getString(R.string.offline)
                        else -> getString(R.string.error)
                    }
                    retryable = retryable(e)
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
            PromptLog(this).opened(code, module, it)
        }
        if (module == EXIT) Alarms.cancel(this, "$code|exit".hashCode())
    }

    /**
     * The address came, but its first page did not (the connection went on the way): a module that can be answered
     * without a connection switches to its kept page, as if there had been no connection from the start (audit A7).
     */
    private fun toKeptPage() {
        val study = StudyStore(this).get(code) ?: return
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
            w.reload()
            return
        }
        val study = StudyStore(this).get(code) ?: return finish()
        open(study)
    }

    /**
     * Whether closing now may lose something: a page of the questionnaire is open and the entry is not over. The
     * error and "saved" screens hold nothing (audit A6).
     */
    private val mayLose: Boolean get() = (url != null || offline != null) && !done && !saved && error == null && !loadFailed

    /** The back gesture: one page back in the questionnaire, else the question before closing (audit A6). */
    private fun back() {
        val w = web
        if (url != null && mayLose && w != null && w.canGoBack()) w.goBack() else close()
    }

    /** The X in the top bar and the last step of the back gesture. */
    private fun close() {
        if (mayLose) askAbort = true else finish()
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
        when (val t = m.trigger) {
            is Trigger.Fixed, is Trigger.Random -> {
                if (due == null) return null
                if (!Schedule.open(m, due, now)) {
                    error = getString(R.string.expired)
                    return null
                }
                if (AnswerQueue(this).has(study.code, module, due)) {
                    error = getString(R.string.offline_saved)
                    return null
                }
            }
            is Trigger.Self -> if (t.maxPerDay > 0 && AnswerQueue(this).today(study.code, module, now) >= t.maxPerDay) {
                error = getString(R.string.limit)
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_DONE, done)
        outState.putBoolean(STATE_SAVED, saved)
        outState.putString(STATE_ERROR, error)
        outState.putBoolean(STATE_RETRY, retryable)
        outState.putBoolean(STATE_CLEARED, cleared)
        outState.putLong(STATE_STARTED, offlineStarted)
    }

    override fun onDestroy() {
        web = null
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
        val code = intent.getStringExtra(EXTRA_STUDY) ?: return
        val store = StudyStore(this)
        lifecycleScope.launch {
            store.get(code)?.let { runCatching { Sync.one(this@EntryActivity, it) } }
        }
    }

    /** The kept page was submitted: the answer waits in the queue, the participant sees that it was saved. */
    private fun offlineSubmitted(json: String) {
        val page = offline ?: return
        if (done) return
        // only a JSON object goes into the queue: the server takes nothing else
        val form = runCatching { JSONObject(json) }.getOrNull()?.toString() ?: return
        val code = intent.getStringExtra(EXTRA_STUDY) ?: return
        val module = intent.getStringExtra(EXTRA_MODULE) ?: return
        val due = intent.getLongExtra(EXTRA_DUE, 0L).takeIf { it > 0 }
        val notified = intent.getLongExtra(EXTRA_NOTIFIED, 0L).takeIf { it > 0 }
        val completed = StudyStore(this).get(code)?.now() ?: System.currentTimeMillis()
        done = true
        AnswerQueue(this).add(code, module, due, notified, offlineStarted, completed, page.version, form)
        SyncWorker.sendSoon(this)
        saved = true
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @androidx.compose.runtime.Composable
    private fun EntryScreen(title: String) {
        BackHandler { back() }
        Scaffold(topBar = {
            Column {
                TopAppBar(
                    // one line, cut with "…": a long title of a study took three lines of the page (audit A21)
                    title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    // the X closes the entry, at the left where a screen has its way out (audit A6)
                    navigationIcon = {
                        IconButton(onClick = { close() }) { Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.close)) }
                    },
                )
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
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                val u = url
                val o = offline
                // no white flash before a dark page has loaded
                val page = MaterialTheme.colorScheme.background.toArgb()
                val e = error
                when {
                    e != null || saved -> Message(e ?: stringResource(R.string.offline_saved), retry = e != null && retryable)
                    u != null -> AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx -> webView(ctx, u).apply { setBackgroundColor(page) } })
                    o != null -> AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx -> offlineWebView(ctx, o).apply { setBackgroundColor(page) } })
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
        if (askAbort) {
            AlertDialog(
                onDismissRequest = { askAbort = false },
                title = { Text(stringResource(R.string.entry_abort_title)) },
                text = { Text(stringResource(R.string.entry_abort_text)) },
                confirmButton = {
                    TextButton(onClick = { askAbort = false; finish() }) {
                        Text(stringResource(R.string.entry_abort), color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = { TextButton(onClick = { askAbort = false }) { Text(stringResource(R.string.entry_keep)) } },
            )
        }
    }

    /** A message instead of the page; "Try again" as the main button when it may help (audit A8). */
    @androidx.compose.runtime.Composable
    private fun Message(text: String, retry: Boolean) {
        Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            // read out as soon as it shows (it replaces the page or the spinner)
            Text(text, Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            if (retry) {
                Button(onClick = { retry() }, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.retry)) }
                OutlinedButton(onClick = { finish() }, modifier = Modifier.padding(top = 8.dp)) { Text(stringResource(R.string.close)) }
            } else {
                Button(onClick = { finish() }, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.close)) }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun baseWebView(ctx: Context): WebView {
        CookieManager.getInstance().setAcceptCookie(true)
        return WebView(ctx).apply {
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
            addJavascriptInterface(Bridge(), BRIDGE)
            web = this
        }
    }

    private fun webView(ctx: Context, start: String): WebView {
        val host = start.toUri().host
        return baseWebView(ctx).apply {
            webViewClient = object : WebViewClient() {
                /** The page now loading failed (its main frame): its onPageFinished is no success. */
                private var failed = false

                // pages of the study stay in the app; any other link (privacy statement of an institution, ...)
                // opens in the browser
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.host == host) return false
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

                override fun onPageFinished(view: WebView, url: String?) {
                    // the first page of the entry is there: now its notification may go (audit A8)
                    if (!failed) clearPrompt()
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    pageProgress = newProgress
                }
            }
            loadUrl(start)
        }
    }

    /**
     * The kept page under the server's address, so its absolute paths resolve; its /static/ and /media/ files come
     * from the phone. Every other request fails here: the page must not reach the network, nor half of a page load
     * from the server if the connection comes back meanwhile.
     */
    private fun offlineWebView(ctx: Context, page: OfflinePages.Page): WebView {
        val base = server.toUri()
        val pages = OfflinePages(ctx)
        return baseWebView(ctx).apply {
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val u = request.url
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

                // links leave the app as on the online page; nothing loads in place of the kept page
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.host != base.host) openOutside(request.url)
                    return true
                }
            }
            // the history URL is the server's too, so that url (fromOwnPage) names it
            loadDataWithBaseURL("$server/", page.html, "text/html", "utf-8", "$server/")
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun done() {
            runOnUiThread { if (fromOwnPage()) finished() }
        }

        /**
         * Called by participant.js on the kept page (form[data-app-offline]) instead of sending the form:
         * [json] is {field: [values]}. Ignored on an online page.
         */
        @JavascriptInterface
        fun offlineSubmit(json: String) {
            runOnUiThread { if (fromOwnPage()) offlineSubmitted(json) }
        }
    }

    companion object {
        /**
         * The name participant.js calls the bridge by (window.AskuriousApp.done, .offlineSubmit): the server's
         * tests/test_diary_app_bridge.py runs the pages against these names, BridgeContractTest checks them here.
         */
        const val BRIDGE = "AskuriousApp"
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

        /** The WebView's errors that mean no connection (not a page the server refused). */
        private val OFFLINE_ERRORS = setOf(
            WebViewClient.ERROR_HOST_LOOKUP, WebViewClient.ERROR_CONNECT, WebViewClient.ERROR_TIMEOUT, WebViewClient.ERROR_IO,
        )

        /**
         * Whether "Try again" may help after [e]: no connection, the rate limit, a fault of the server or an answer it
         * could not read; not an expired prompt, today's limit or a paused study (audit A8).
         */
        fun retryable(e: ApiError): Boolean = e.transient || e.key == "error"

        /** The links of a page that may open in another app. */
        private val OUTSIDE_SCHEMES = setOf("http", "https", "mailto", "tel")

        /** The module key of the exit questionnaire (POST /entry {"module": "exit"}, without due). */
        const val EXIT = "exit"

        fun intent(
            context: Context, study: String, module: String, due: Long? = null, notified: Long? = null, reminded: Boolean = false,
        ): Intent =
            Intent(context, EntryActivity::class.java)
                .putExtra(EXTRA_STUDY, study).putExtra(EXTRA_MODULE, module)
                .putExtra(EXTRA_DUE, due ?: 0L).putExtra(EXTRA_NOTIFIED, notified ?: 0L)
                .putExtra(EXTRA_REMINDED, reminded)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
