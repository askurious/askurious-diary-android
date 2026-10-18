package de.askurious.app.test

import android.app.Instrumentation
import android.os.StrictMode
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import de.askurious.app.data.AnswerQueue
import de.askurious.app.data.Module
import de.askurious.app.data.OfflinePages
import de.askurious.app.data.StudyStore
import de.askurious.app.data.Study
import de.askurious.app.data.Sync
import de.askurious.app.data.Trigger
import de.askurious.app.ui.EntryActivity
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The kept page without a connection (audit 2026-10-18, AND-01) and the entry's start off the main thread (AND-09),
 * in the real EntryActivity and WebView, with synthetic data only. Nothing listens on the debug server's address
 * (127.0.0.1:5080; no `adb reverse` while it runs), so the entry finds no connection and opens the kept page. The
 * page must render its form (it showed the WebView's error page, net::ERR_HTTP_RESPONSE_CODE_FAILURE) and its answer
 * must reach the queue; reading the study, the log and the kept page must not touch the disk on the main thread.
 */
object OfflineChecks {
    private const val CODE = "offline-check"

    fun run(test: Instrumentation) {
        val context = test.targetContext
        val today = LocalDate.now()
        val module = Module("m1", "Ereignis", Trigger.Fixed(listOf(LocalTime.of(3, 0)), (1..7).toSet()), 60, 1, 14, "",
            offline = true, selfStart = Trigger.Self("Ereignis melden", 5))
        val study = Study(CODE, "synthetic-only", "Synthetische Offline-Studie", "active", today.minusDays(1), today.plusDays(10), listOf(module))
        kotlinx.coroutines.runBlocking { Sync.forget(context, CODE) }
        StudyStore(context).put(study)
        val html = """<!doctype html><html><head><title>Kept page</title></head><body><main data-page="one">
            <form id="f"><input type="hidden" name="csrf_token" value="x"><label>Answer<input id="answer" name="answer"></label></form>
            </main></body></html>"""
        OfflinePages(context).save(CODE, "m1", OfflinePages.Page(html, "v-check", emptyList(), System.currentTimeMillis()))

        // a new process: nothing opened is kept in memory, the entry has to open the study with the Keystore
        SealedTextCache.clear()
        // disk reads and writes of the app's stores and calls of the Keystore on the main thread while the entry
        // starts (AND-09); StrictMode's listener needs API 28
        check(android.os.Build.VERSION.SDK_INT >= 28) { "Needs API 28" }
        val violations = java.util.Collections.synchronizedList(mutableListOf<String>())
        val previous = arrayOfNulls<StrictMode.ThreadPolicy>(1)
        test.runOnMainSync {
            previous[0] = StrictMode.getThreadPolicy()
            StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectCustomSlowCalls()
                .penaltyListener(java.util.concurrent.Executors.newSingleThreadExecutor()) { v ->
                    val stack = v.stackTrace.joinToString("\n") { it.toString() }
                    if (APP_STORES.any { stack.contains(it) }) violations += stack.lineSequence().take(25).joinToString("\n")
                }.build())
        }
        try {
            val activity = test.startActivitySync(EntryActivity.intent(context, CODE, "m1")) as EntryActivity
            var web: WebView? = null
            waitFor("kept page attached") { test.runOnMainSync { web = findWeb(activity.window.decorView) }; web != null }
            waitFor("kept page rendered: " + evaluate(test, web!!, "document.title + ' ' + location.href")) {
                evaluate(test, web!!, "Boolean(document.querySelector('form#f input#answer'))") == "true"
            }
            check(evaluate(test, web!!, "document.title") == "\"Kept page\"") { "Not the kept page" }
            // the page hands its form to the app as participant.js does
            evaluate(test, web!!, "AskuriousApp.offlineSubmit(JSON.stringify({answer:'offline'}));true")
            waitFor("answer queued") { AnswerQueue(context).of(CODE).isNotEmpty() }
            check(AnswerQueue(context).of(CODE).single().form.contains("offline"))
            SystemClock.sleep(500)
            check(violations.isEmpty()) { "Disk access of the app's stores on the main thread:\n" + violations.first() }
            test.runOnMainSync { activity.finish() }
        } finally {
            test.runOnMainSync { previous[0]?.let { StrictMode.setThreadPolicy(it) } }
            kotlinx.coroutines.runBlocking { Sync.forget(context, CODE) }
        }
    }

    /** The app's own stores and files: what EntryActivity reads when it starts. */
    private val APP_STORES = listOf("de.askurious.app.data.StudyStore", "de.askurious.app.data.PromptLog",
        "de.askurious.app.data.OfflinePages", "de.askurious.app.data.SealedText", "de.askurious.app.data.KeystoreSealer")

    /** The opened texts SealedText keeps in memory for the process. */
    private object SealedTextCache {
        fun clear() {
            val f = de.askurious.app.data.SealedText::class.java.getDeclaredField("CACHE").apply { isAccessible = true }
            synchronized(de.askurious.app.data.SealedText.LOCK) { (f.get(null) as MutableMap<*, *>).clear() }
        }
    }

    private fun findWeb(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWeb(view.getChildAt(it)) }
        else -> null
    }

    private fun waitFor(label: String, test: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20000
        while (!test() && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
        check(test()) { "UI condition timed out: $label" }
    }

    private fun evaluate(test: Instrumentation, web: WebView, script: String): String {
        var value = ""
        val latch = CountDownLatch(1)
        test.runOnMainSync { web.evaluateJavascript(script) { value = it; latch.countDown() } }
        check(latch.await(5, TimeUnit.SECONDS))
        return value
    }
}
