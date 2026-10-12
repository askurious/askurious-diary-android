package de.askurious.app.ui

import android.annotation.SuppressLint
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
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
            offlineStarted = savedInstanceState.getLong(STATE_STARTED)
            if (done && !saved && error == null) return finish()
        }
        val code = intent.getStringExtra(EXTRA_STUDY) ?: return finish()
        val module = intent.getStringExtra(EXTRA_MODULE) ?: return finish()
        val due = intent.getLongExtra(EXTRA_DUE, 0L).takeIf { it > 0 }
        val notified = intent.getLongExtra(EXTRA_NOTIFIED, 0L).takeIf { it > 0 }
        val reminded = intent.getBooleanExtra(EXTRA_REMINDED, false)
        if (due != null) {
            Alarms.cancel(this, Prompt(code, module, due, 0).id)
            // opened: the reminder of this prompt stays silent
            PromptLog(this).opened(code, module, due)
        }
        if (module == EXIT) {
            Alarms.cancel(this, "$code|exit".hashCode())
            // opened: no exit notification any more (the server closes it once answered)
            PromptLog(this).exitNotified(code)
        }

        val study = StudyStore(this).get(code) ?: return finish()
        server = study.server
        if (!saved && error == null && url == null && offline == null) {
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
                    } else if (error == null) {
                        error = when (e.key) {
                            "expired" -> getString(R.string.expired)
                            // today's limit of a self-started report (409); a 429 "limit" is the rate limit
                            "daily_limit" -> getString(R.string.limit)
                            "paused" -> getString(R.string.paused)
                            "offline" -> getString(R.string.offline)
                            else -> getString(R.string.error)
                        }
                    }
                }
            }
        }

        setContent {
            // the bar above the page and the screen before it loads in the look of the study's pages
            StudyTheme(study.look, bars = true) {
                EntryScreen(study.title)
            }
        }
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
        Scaffold(topBar = {
            TopAppBar(
                title = { Text(title) },
                actions = { TextButton(onClick = { finish() }) { Text(stringResource(R.string.close)) } },
            )
        }) { pad ->
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) {
                val u = url
                val o = offline
                // no white flash before a dark page has loaded
                val page = MaterialTheme.colorScheme.background.toArgb()
                val e = error
                when {
                    e != null || saved -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(e ?: stringResource(R.string.offline_saved))
                        Button(onClick = { finish() }, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.close)) }
                    }
                    u != null -> AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx -> webView(ctx, u).apply { setBackgroundColor(page) } })
                    o != null -> AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx -> offlineWebView(ctx, o).apply { setBackgroundColor(page) } })
                    else -> CircularProgressIndicator()
                }
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
                // pages of the study stay in the app; any other link (privacy statement of an institution, ...)
                // opens in the browser
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.url.host == host) return false
                    openOutside(request.url)
                    return true
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
