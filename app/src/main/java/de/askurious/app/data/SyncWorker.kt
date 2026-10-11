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
 * the phone without a push service. A leave made without a connection is sent here too. The prompts themselves
 * never need the network.
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
    /**
     * Fetches one study and stores it. A 401 (secret unknown) ends the study only when it keeps coming
     * ([Study.unauthorised]); any answer of the server clears it. Other errors (offline, 429, 5xx) change nothing
     * and are thrown.
     */
    suspend fun one(context: Context, study: Study): Study {
        val fresh = try {
            Study.fromJson(Api.study(study.secret), study.secret)
        } catch (e: ApiError) {
            if (e.status == 401) study.unauthorised(System.currentTimeMillis()) else throw e
        }
        StudyStore(context).update(fresh)
        Alarms.rearm(context)
        return fresh
    }

    /**
     * Leaves [study]: shown as left at once, and told to the server. When the server cannot be reached (offline,
     * 429, 5xx) the study keeps [Study.leavePending] and [all] tries again. Returns false in that case.
     */
    suspend fun leave(context: Context, study: Study): Boolean {
        val store = StudyStore(context)
        // stored as pending first: no more prompts from now on, even if the app is closed during the call
        store.update(study.copy(status = "left", leavePending = true))
        Alarms.rearm(context)
        val sent = try {
            Api.leave(study.secret)
            true
        } catch (e: ApiError) {
            // any other answer (401: the server no longer knows the secret) leaves nothing to tell
            !e.transient
        }
        if (sent) store.update(study.copy(status = "left", leavePending = false))
        return sent
    }

    /**
     * Deletes all answers of [study] on the server and removes the study from the phone. A 401 means there is
     * nothing left on the server, so the study goes too; any other error (offline, ...) is thrown and the study stays.
     */
    suspend fun erase(context: Context, study: Study) {
        try {
            Api.erase(study.secret)
        } catch (e: ApiError) {
            if (e.status != 401) throw e
        }
        StudyStore(context).remove(study.code)
        Alarms.rearm(context)
    }

    /**
     * Fetches all studies that are not over and sends pending leaves; returns true when the network was missing or
     * a leave could not be sent yet (SyncWorker then tries again).
     */
    suspend fun all(context: Context): Boolean {
        var offline = false
        for (s in StudyStore(context).all()) {
            try {
                when {
                    s.leavePending -> if (!leave(context, s)) offline = true
                    s.status == "pending" || s.status == "active" -> one(context, s)
                }
            } catch (e: ApiError) {
                if (e.key == "offline") offline = true
            }
        }
        return offline
    }
}
