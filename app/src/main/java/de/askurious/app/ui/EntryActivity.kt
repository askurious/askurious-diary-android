package de.askurious.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
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

        val study = StudyStore(this).get(code) ?: return finish()
        if (savedInstanceState == null || (url == null && offline == null)) {
            lifecycleScope.launch {
                try {
                    val address = Api.absolute(Api.entry(study.secret, module, due, notified, reminded))
                    clearWebState()
                    url = address
                } catch (e: ApiError) {
                    val kept = if (e.key == "offline") keptPage(study, module, due) else null
                    if (kept != null) {
                        clearWebState()
                        offlineStarted = System.currentTimeMillis()
                        offline = kept
                    } else {
                        error = when (e.key) {
                            "expired" -> getString(R.string.expired)
                            "limit" -> getString(R.string.limit)
                            "offline" -> getString(R.string.offline)
                            else -> getString(R.string.error)
                        }
                    }
                }
            }
        }

        setContent {
            DiaryTheme {
                EntryScreen(study.title)
            }
        }
    }

    /**
     * The kept page for answering [module] without a connection, if it may be answered now: a prompt still open,
     * or a self-started module under its daily limit (counting the answers waiting to be sent; those sent today
     * the phone does not know, the server checks them when the answer arrives). Sets [error] when the prompt has
     * expired or the limit is reached.
     */
    private fun keptPage(study: Study, module: String, due: Long?): OfflinePages.Page? {
        if (module == "entry" || !study.running) return null
        val m = study.modules.firstOrNull { it.key == module && it.offline } ?: return null
        val page = OfflinePages(this).usable(study.code, module) ?: return null
        val now = System.currentTimeMillis()
        when (val t = m.trigger) {
            is Trigger.Fixed -> {
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
        done = true
        AnswerQueue(this).add(code, module, due, notified, offlineStarted, System.currentTimeMillis(), page.version, form)
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
                val e = error
                when {
                    e != null || saved -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(e ?: stringResource(R.string.offline_saved))
                        Button(onClick = { finish() }, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.close)) }
                    }
                    u != null -> AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx -> webView(ctx, u) })
                    o != null -> AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx -> offlineWebView(ctx, o) })
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
            settings.userAgentString = settings.userAgentString + " AskuriousDiary/1"
            addJavascriptInterface(Bridge(), "AskuriousApp")
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
                    startActivity(Intent(Intent.ACTION_VIEW, request.url))
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
        val base = Api.baseUrl.toUri()
        val pages = OfflinePages(ctx)
        return baseWebView(ctx).apply {
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse {
                    val u = request.url
                    val path = (u.encodedPath ?: "") + (u.encodedQuery?.let { "?$it" } ?: "")
                    val kept = if (u.scheme == base.scheme && u.host == base.host && u.port == base.port && OfflinePages.servable(path)) {
                        pages.file(path)
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
                    if (request.url.host != base.host) startActivity(Intent(Intent.ACTION_VIEW, request.url))
                    return true
                }
            }
            loadDataWithBaseURL(Api.baseUrl + "/", page.html, "text/html", "utf-8", null)
        }
    }

    inner class Bridge {
        @JavascriptInterface
        fun done() {
            runOnUiThread { finished() }
        }

        /**
         * Called by participant.js on the kept page (form[data-app-offline]) instead of sending the form:
         * [json] is {field: [values]}. Ignored on an online page.
         */
        @JavascriptInterface
        fun offlineSubmit(json: String) {
            runOnUiThread { offlineSubmitted(json) }
        }
    }

    companion object {
        private const val EXTRA_STUDY = "study"
        private const val EXTRA_MODULE = "module"
        private const val EXTRA_DUE = "due"
        private const val EXTRA_NOTIFIED = "notified"
        private const val EXTRA_REMINDED = "reminded"

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
