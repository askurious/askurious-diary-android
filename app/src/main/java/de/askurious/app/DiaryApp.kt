package de.askurious.app

import android.app.Application
import de.askurious.app.data.SyncWorker
import de.askurious.app.schedule.Alarms

class DiaryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Alarms.createChannel(this)
        SyncWorker.schedule(this)
        // whatever is stored, the app must start: a crash here would come back at every start (and at every alarm and
        // boot), and only "Clear data" would end it, taking the secrets of all studies with it. Off the main thread: it
        // opens the stored studies with the Keystore (audit 2026-10-13, AN-8)
        Thread { runCatching { Alarms.rearm(this) } }.start()
    }
}
