package de.askurious.app.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
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
import de.askurious.app.data.Api
import de.askurious.app.data.ApiError
import de.askurious.app.data.StudyStore
import de.askurious.app.data.Sync
import de.askurious.app.schedule.Alarms
import de.askurious.app.schedule.Prompt
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * One entry: asks the server for a one-time address of the module and shows the participant pages of Askurious
 * in a WebView. The questionnaire itself is the web page; the app adds nothing to it. The end page calls
 * AskuriousApp.done() (participant/end.html in app mode), and the activity closes.
 *
 * The WebView's cookies and web storage are shared by the whole app. Each entry starts with them empty, so the
 * session cookie of one study never travels with the entry of another and the server cannot link two studies on
 * one phone.
 */
class EntryActivity : ComponentActivity() {
    private var url by mutableStateOf<String?>(null)
    private var error by mutableStateOf<String?>(null)
    private var done by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val code = intent.getStringExtra(EXTRA_STUDY) ?: return finish()
        val module = intent.getStringExtra(EXTRA_MODULE) ?: return finish()
        val due = intent.getLongExtra(EXTRA_DUE, 0L).takeIf { it > 0 }
        val notified = intent.getLongExtra(EXTRA_NOTIFIED, 0L).takeIf { it > 0 }
        if (due != null) Alarms.cancel(this, Prompt(code, module, due, 0).id)

        val study = StudyStore(this).get(code) ?: return finish()
        if (savedInstanceState == null || url == null) {
            lifecycleScope.launch {
                try {
                    val address = Api.absolute(Api.entry(study.secret, module, due, notified))
                    clearWebState()
                    url = address
                } catch (e: ApiError) {
                    error = when (e.key) {
                        "expired" -> getString(R.string.expired)
                        "limit" -> getString(R.string.limit)
                        "offline" -> getString(R.string.offline)
                        else -> getString(R.string.error)
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
                val e = error
                when {
                    e != null -> Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(e)
                        Button(onClick = { finish() }, modifier = Modifier.padding(top = 16.dp)) { Text(stringResource(R.string.close)) }
                    }
                    u == null -> CircularProgressIndicator()
                    else -> AndroidView(modifier = Modifier.fillMaxSize(), factory = { ctx -> webView(ctx, u) })
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun webView(ctx: Context, start: String): WebView {
        CookieManager.getInstance().setAcceptCookie(true)
        val host = start.toUri().host
        return WebView(ctx).apply {
            // nothing of an earlier entry (another study) is taken from the cache
            clearCache(true)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = settings.userAgentString + " AskuriousDiary/1"
            addJavascriptInterface(Bridge(), "AskuriousApp")
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

    inner class Bridge {
        @JavascriptInterface
        fun done() {
            runOnUiThread { finished() }
        }
    }

    companion object {
        private const val EXTRA_STUDY = "study"
        private const val EXTRA_MODULE = "module"
        private const val EXTRA_DUE = "due"
        private const val EXTRA_NOTIFIED = "notified"

        fun intent(context: Context, study: String, module: String, due: Long? = null, notified: Long? = null): Intent =
            Intent(context, EntryActivity::class.java)
                .putExtra(EXTRA_STUDY, study).putExtra(EXTRA_MODULE, module)
                .putExtra(EXTRA_DUE, due ?: 0L).putExtra(EXTRA_NOTIFIED, notified ?: 0L)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
    }
}
