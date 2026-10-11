package de.askurious.app.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.askurious.app.schedule.Alarms
import java.util.concurrent.TimeUnit

/**
 * Fetches every study again every few hours: a change of the schedule or an end set by the researchers reaches
 * the phone without a push service. The prompts themselves never need the network.
 */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val offline = Sync.all(applicationContext)
        return if (offline) Result.retry() else Result.success()
    }

    companion object {
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}

object Sync {
    /** Fetches one study and stores it; a study the server no longer knows (deleted) is marked "ended". */
    suspend fun one(context: Context, study: Study): Study {
        val store = StudyStore(context)
        val fresh = try {
            Study.fromJson(Api.study(study.secret), study.secret)
        } catch (e: ApiError) {
            if (e.status == 401 || e.status == 404) study.copy(status = "ended") else throw e
        }
        store.put(fresh)
        Alarms.rearm(context)
        return fresh
    }

    /** Fetches all studies that are not over; returns true when the network was missing. */
    suspend fun all(context: Context): Boolean {
        var offline = false
        for (s in StudyStore(context).all().filter { it.status == "pending" || it.status == "active" }) {
            try {
                one(context, s)
            } catch (e: ApiError) {
                if (e.key == "offline") offline = true
            }
        }
        return offline
    }
}
