package de.askurious.app.test

import android.app.Instrumentation
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import de.askurious.app.data.*
import de.askurious.app.ui.EntryActivity
import org.json.JSONObject
import java.net.InetAddress
import java.net.ServerSocket
import java.time.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Activity/WebView regression checks; local fixtures have no external service or participant data. */
object UiAuditChecks {
    fun run(test: Instrumentation, phase: String) {
        val context = test.targetContext
        val today = LocalDate.now()
        val now = ZonedDateTime.now().withSecond(0).withNano(0)
        val module = Module("m1", "Stimmung jetzt", Trigger.Fixed(listOf(now.minusMinutes(15).toLocalTime()), (1..7).toSet()),60,1,14,"",selfStart=Trigger.Self("Ereignis melden",3))
        val study = Study("ui-active", "synthetic-only", "Arbeitsalltag und Wohlbefinden", "active", today.minusDays(2), today.plusDays(11), listOf(module),
            summary="Drei kurze Befragungen zu Ihrem Arbeitsalltag.", contact="team@example.org")
        if (phase == "ui-seed") {
            val store = StudyStore(context)
            kotlinx.coroutines.runBlocking { for (code in listOf("ui-active", "ui-finished")) Sync.forget(context,code) }
            store.put(study)
            store.put(study.copy(code="ui-finished",title="Abgeschlossene Studie",status="finished",exit=Exit("Abschlussbefragung",true,today.plusDays(7))))
            val item=AnswerQueue(context).add("ui-finished","m1",null,null,System.currentTimeMillis()-60000,System.currentTimeMillis(),"synthetic", "{}")
            AnswerQueue(context).attempted(item.id,"invalid")
            check(AnswerQueue(context).of("ui-finished").size==1)
            // Instrumentation exits without an Activity stop; allow asynchronous preference writes to finish.
            SystemClock.sleep(1000)
            return
        }
        check(phase == "ui-entry")
        StudyStore(context).put(study)
        val server=ServerSocket(5080,50,InetAddress.getByName("127.0.0.1"))
        val worker=Thread {
            while (!server.isClosed) runCatching {
                server.accept().use { socket ->
                    val reader=socket.getInputStream().bufferedReader()
                    val request=reader.readLine().orEmpty()
                    android.util.Log.i("UiFixture",request)
                    var length=0
                    while (true) {
                        val header=reader.readLine().orEmpty()
                        if (header.isBlank()) break
                        if (header.startsWith("Content-Length:",true)) length=header.substringAfter(':').trim().toInt()
                    }
                    repeat(length) { reader.read() }
                    val page=request.contains(" /ui-test ")
                    val body=if (page) """<!doctype html><html><body><main data-page="one"><form><label>Answer<input id="answer" name="answer"></label></form></main></body></html>"""
                        else if (request.contains("/entry")) """{"url":"/ui-test"}""" else study.toJson().toString()
                    val bytes=body.toByteArray()
                    socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Type: ${if(page) "text/html" else "application/json"}; charset=utf-8\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                    socket.getOutputStream().write(bytes)
                }
            }.onFailure { if (!server.isClosed) android.util.Log.e("UiFixture","Fixture request failed",it) }
        }.apply { isDaemon=true;start() }
        val current=java.util.concurrent.atomic.AtomicReference<EntryActivity>()
        val application=context.applicationContext as android.app.Application
        val observer=object:android.app.Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(a:android.app.Activity) { if(a is EntryActivity) current.set(a) }
            override fun onActivityCreated(a:android.app.Activity,b:android.os.Bundle?) {}
            override fun onActivityStarted(a:android.app.Activity) {}
            override fun onActivityPaused(a:android.app.Activity) {}
            override fun onActivityStopped(a:android.app.Activity) {}
            override fun onActivitySaveInstanceState(a:android.app.Activity,b:android.os.Bundle) {}
            override fun onActivityDestroyed(a:android.app.Activity) {}
        }
        application.registerActivityLifecycleCallbacks(observer)
        try {
            val activity=test.startActivitySync(EntryActivity.intent(context,study.code,"m1",address="/ui-test")) as EntryActivity
            var web:WebView?=null
            waitFor("WebView attached") { test.runOnMainSync { web=findWeb(activity.window.decorView) };web!=null }
            waitFor("Draft hook: " + evaluate(test,web!!,"location.href")) { evaluate(test,web!!,"Boolean(document.querySelector('#answer') && window.__askDraft)")=="true" }
            evaluate(test,web!!,"document.querySelector('#answer').value='remember me';document.querySelector('#answer').dispatchEvent(new Event('input',{bubbles:true}));true")
            SystemClock.sleep(300)
            test.runOnMainSync { activity.onBackPressedDispatcher.onBackPressed() }
            SystemClock.sleep(300)
            check(!activity.isFinishing) { "Dirty questionnaire closed without confirmation" }
            // The real Activity handles rotation/recreation through its retained WebView.
            test.runOnMainSync { activity.recreate() }
            SystemClock.sleep(1500)
            check(evaluate(test,web!!,"document.querySelector('#answer').value")=="\"remember me\"") { "Input lost on recreation" }
            waitFor("Recreated Activity resumed") { current.get()!=null && current.get()!==activity }
            val rotated=current.get()
            // Drop the live WebView/ViewModel: recovery must now use Android saved instance state alone.
            test.runOnMainSync {
                (web!!.parent as? ViewGroup)?.removeView(web)
                rotated.viewModelStore.clear()
                rotated.recreate()
            }
            waitFor("Saved-state Activity resumed") { current.get()!=null && current.get()!==rotated }
            web=null
            waitFor("Saved-state WebView attached") { test.runOnMainSync { web=findWeb(current.get().window.decorView) };web!=null }
            waitFor("Form draft recovered from saved state") { evaluate(test,web!!,"document.querySelector('#answer')?.value")=="\"remember me\"" }
        } finally { application.unregisterActivityLifecycleCallbacks(observer);server.close();worker.join(2000) }
    }
    private fun findWeb(view:View):WebView?=when(view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { findWeb(view.getChildAt(it)) }
        else -> null
    }
    private fun waitFor(label:String,test:()->Boolean) {
        val deadline=SystemClock.elapsedRealtime()+20000
        while (!test() && SystemClock.elapsedRealtime()<deadline) SystemClock.sleep(100)
        check(test()) { "UI condition timed out: $label" }
    }
    private fun evaluate(test:Instrumentation,web:WebView,script:String):String {
        var value="";val latch=CountDownLatch(1)
        test.runOnMainSync { web.evaluateJavascript(script) { value=it;latch.countDown() } }
        check(latch.await(5,TimeUnit.SECONDS))
        return value
    }
}
