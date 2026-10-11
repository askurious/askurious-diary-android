package de.askurious.app

import android.app.Application
import de.askurious.app.data.SyncWorker
import de.askurious.app.schedule.Alarms

class DiaryApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Alarms.createChannel(this)
        SyncWorker.schedule(this)
        Alarms.rearm(this)
    }
}
