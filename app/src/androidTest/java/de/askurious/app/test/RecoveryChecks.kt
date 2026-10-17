package de.askurious.app.test

import android.app.Instrumentation
import android.os.SystemClock
import de.askurious.app.data.TransmissionLog
import de.askurious.app.ui.MainModel
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real main dispatcher, delayed HTTP response and encrypted receipts, with synthetic data only. */
object RecoveryChecks {
    fun run(test: Instrumentation) {
        val server = ServerSocket(5080, 50, InetAddress.getByName("127.0.0.1"))
        val slowStarted = CountDownLatch(1)
        val releaseSlow = CountDownLatch(1)
        val slowFinished = CountDownLatch(1)
        val worker = Thread {
            repeat(2) {
                val socket = server.accept()
                Thread {
                    socket.use {
                        val input = it.getInputStream().bufferedReader()
                        val slow = input.readLine().contains("slow01")
                        while (!input.readLine().isNullOrEmpty()) { }
                        if (slow) { slowStarted.countDown(); check(releaseSlow.await(15, TimeUnit.SECONDS)) }
                        val body = """{"code":"${if (slow) "slow01" else "audit01"}"}""".toByteArray()
                        runCatching {
                            it.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            it.getOutputStream().write(body)
                        }
                        if (slow) slowFinished.countDown()
                    }
                }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; start() }
        lateinit var model: MainModel
        var obsoleteCallback = false
        val fresh = CountDownLatch(1)
        try {
            test.runOnMainSync {
                model = MainModel(test.targetContext.applicationContext as android.app.Application)
                model.lookUp("http://127.0.0.1:5080", "slow01") { _, _ -> obsoleteCallback = true }
            }
            check(slowStarted.await(10, TimeUnit.SECONDS)) { "Slow lookup did not start" }
            test.runOnMainSync {
                check(model.joinBusy)
                val start = SystemClock.elapsedRealtime()
                check(model.cancelLookup())
                check(SystemClock.elapsedRealtime() - start < 500)
                check(!model.joinBusy && model.joinInfo == null && model.joinedStudy == null)
                model.lookUp("http://127.0.0.1:5080", "audit01") { info, error ->
                    check(error == null && info?.optString("code") == "audit01")
                    fresh.countDown()
                }
            }
            check(fresh.await(10, TimeUnit.SECONDS)) { "New lookup could not finish while old response waited" }
            releaseSlow.countDown()
            check(slowFinished.await(5, TimeUnit.SECONDS))
            SystemClock.sleep(500)
            test.runOnMainSync {
                check(!obsoleteCallback && !model.joinBusy)
                check(org.json.JSONObject(model.joinInfo!!).getString("code") == "audit01")
                model.cancelLookup()
            }
        } finally { releaseSlow.countDown(); server.close(); worker.join(2000) }
        val log = TransmissionLog(test.targetContext)
        val code = "recovery-test-receipts"
        log.remove(code)
        try {
            val now = System.currentTimeMillis()
            log.confirmed(code, "mood", false, "one", completed = now - 10000)
            log.confirmed(code, "event", true, "two", completed = now - 5000)
            log.confirmed(code, "mood", false, "one", completed = now - 10000)
            log.confirmed(code, "legacy", false, "old")
            val receipts = log.of(code)
            check(receipts.size == 3)
            check(receipts[0].getLong("completed") == now - 10000)
            check(receipts[1].getString("module") == "event" && receipts[1].getBoolean("review"))
            check(!receipts[0].getBoolean("review") && !receipts[2].has("completed"))
        } finally { log.remove(code) }
    }
}
