package de.askurious.app.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.askurious.app.schedule.Alarms
import java.util.concurrent.TimeUnit

/**
 * Fetches every study again every few hours: a change of the schedule or an end set by the researchers reaches
 * the phone without a push service. A leave made without a connection is sent here too, and so are the answers
 * given without one (AnswerQueue). The prompts themselves never need the network.
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

        /**
         * Runs a sync as soon as the phone has a connection, after an answer was queued: it should not wait up to six
         * hours for the periodic one. Appended, so an answer queued while a sync runs is not missed by it.
         */
        fun sendSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("send", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}

object Sync {
    /**
     * Fetches one study and stores it, sending quiet hours the server has not confirmed yet
     * ([Study.quietPending]); from then on the server's history counts. A 401 (secret unknown) ends the study only
     * when it keeps coming ([Study.unauthorised]); any answer of the server clears it. Other errors (offline, 429,
     * 5xx) change nothing and are thrown.
     */
    suspend fun one(context: Context, study: Study): Study {
        val sent = study.quietPending
        var fresh = try {
            Study.fromJson(Api.study(study.secret, sent), study.secret)
        } catch (e: ApiError) {
            if (e.status == 401) study.unauthorised(System.currentTimeMillis()) else throw e
        }
        // quiet hours changed again while the call ran stay pending for the next sync (after a 401 they stay anyway)
        val pending = StudyStore(context).get(study.code)?.quietPending
        if (pending != null && pending != sent) fresh = fresh.copy(quietPending = pending)
        StudyStore(context).update(fresh)
        // an exit questionnaire that opened after its moment (or whose moment passed while the phone was off)
        Alarms.notifyExits(context)
        Alarms.rearm(context)
        // the pages for answering without a connection; only while the study is still on the phone
        if (StudyStore(context).get(fresh.code) != null) OfflinePages(context).refresh(fresh)
        return fresh
    }

    /**
     * Sets the quiet hours of [study] to [quiet] (null: none): kept as pending at once, valid from tomorrow (the
     * server's rule), and sent with a sync; without a connection the next sync sends them. The server's answer then
     * says from which day they count.
     */
    suspend fun setQuiet(context: Context, study: Study, quiet: Quiet?) {
        val store = StudyStore(context)
        val current = store.get(study.code) ?: return
        val from = current.today().plusDays(1)
        store.update(current.copy(quietPending = QuietFrom(from, quiet)))
        Alarms.rearm(context)
        try {
            store.get(study.code)?.let { one(context, it) }
        } catch (e: ApiError) {
            SyncWorker.sendSoon(context)
        }
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
        OfflinePages(context).dropStudy(study.code)
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
        forget(context, study.code)
    }

    /**
     * Removes [code] from the phone with everything kept for it: queued answers, kept pages and the log of its
     * prompts. Used by "Delete my answers" and "Remove from list".
     */
    fun forget(context: Context, code: String) {
        StudyStore(context).remove(code)
        AnswerQueue(context).removeStudy(code)
        OfflinePages(context).dropStudy(code)
        PromptLog(context).removeStudy(code)
        Alarms.rearm(context)
    }

    /**
     * Sends the queued answers of [study] (POST /offline). Returns false when some must wait (no network, 429,
     * 5xx). An answer the questionnaire no longer fits ("changed") is dropped with the kept page, which the sync of
     * the study fetches again.
     */
    suspend fun send(context: Context, study: Study): Boolean {
        val queue = AnswerQueue(context)
        var kept = false
        for (item in queue.of(study.code)) {
            // "started" is in the study's time (virtual for a fast-forward person)
            if (AnswerQueue.tooOld(item, study.now())) {
                queue.remove(item.id)
                continue
            }
            val error = try {
                Api.offlineAnswer(study.secret, item)
                null
            } catch (e: ApiError) {
                e
            }
            when (AnswerQueue.fate(error)) {
                AnswerQueue.Fate.KEEP -> kept = true
                AnswerQueue.Fate.CHANGED -> {
                    queue.remove(item.id)
                    OfflinePages(context).drop(study.code, item.module)
                }
                else -> queue.remove(item.id)
            }
        }
        return !kept
    }

    /**
     * Whether a sync of [study] can bring news: it is not over (pending, active, paused), or it is over (finished,
     * ended) with an exit questionnaire that may still open or is open: until its last day, as long as it is open or
     * was neither notified nor opened ([exitNotified]).
     */
    fun wanted(study: Study, exitNotified: Boolean): Boolean {
        if (study.status == "pending" || study.status == "active" || study.status == "paused") return true
        if (study.status != "finished" && study.status != "ended") return false
        val exit = study.exit ?: return false
        if (exit.until != null && study.today().isAfter(exit.until)) return false
        return exit.open || !exitNotified
    }

    /**
     * Sends the queued answers, fetches all studies that can bring news ([wanted]) and sends pending leaves; returns
     * true when the network was missing or an answer or a leave could not be sent yet (SyncWorker then tries again).
     * The answers go first: those given before leaving belong to the study.
     */
    suspend fun all(context: Context): Boolean {
        var offline = false
        val exits = PromptLog(context).exits()
        for (s in StudyStore(context).all()) {
            if (!send(context, s)) offline = true
            try {
                when {
                    s.leavePending -> if (!leave(context, s)) offline = true
                    wanted(s, s.code in exits) -> one(context, s)
                }
            } catch (e: ApiError) {
                if (e.key == "offline") offline = true
            }
        }
        return offline
    }
}
