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
import de.askurious.app.ui.Shortcuts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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

/**
 * Everything that talks to the server for the stored studies. One at a time in the whole process ([lock]): the sync
 * when the app comes to the front, the periodic one, the one after an answer was queued and the one after an entry
 * would otherwise send the same queued answer twice and write studies back over each other. Everything here runs off
 * the main thread.
 */
object Sync {
    private val lock = Mutex()

    /** When the last full sync without network trouble ended (real epoch milliseconds); 0 = none in this process. */
    @Volatile
    private var lastAll = 0L

    /** The shortest time between two full syncs started by bringing the app to the front ([allIfDue]). */
    const val RESUME_GAP_MS = 5L * 60 * 1000

    /**
     * Fetches one study and stores it (see [oneLocked]). Reads the stored study again under the lock: the one handed
     * in may be out of date (left, or joined again meanwhile). Returns the stored study, or [study] if it is gone.
     */
    suspend fun one(context: Context, study: Study): Study = withContext(Dispatchers.IO) {
        val fresh = lock.withLock {
            val current = StudyStore(context).get(study.code)
            if (current == null) null else oneLocked(context, current)
        } ?: return@withContext study
        refreshPages(context, fresh)
        fresh
    }

    /**
     * Brings the kept pages of [study] up to date, after [lock] was let go (audit 2026-10-13, AN-9): downloading them
     * may take long on a slow network, and "Delete my answers" or leaving must not wait for it. OfflinePages has a
     * lock of its own, checks that the study is still on the phone, and stops when the study is dropped meanwhile.
     */
    private suspend fun refreshPages(context: Context, study: Study) {
        try {
            OfflinePages(context).refresh(study)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the pages are a fallback: the next sync tries again
        }
    }

    /**
     * Fetches one study and stores it, sending quiet hours the server has not confirmed yet
     * ([Study.quietPending]); from then on the server's history counts. A 401 (secret unknown) ends the study only
     * when it keeps coming ([Study.unauthorised]); any answer of the server clears it. Other errors (offline, 429,
     * 5xx) change nothing and are thrown. What the participant changed on the phone while the call ran wins over the
     * server's answer ([merged]). Only with [lock] held; the caller brings the kept pages up to date afterwards
     * ([refreshPages]).
     */
    private suspend fun oneLocked(context: Context, study: Study): Study {
        val sent = study.quietPending
        val fetched = try {
            Study.fromJson(Api.study(study.server, study.secret, sent), study.secret, study.server)
        } catch (e: ApiError) {
            if (e.status == 401) study.unauthorised(System.currentTimeMillis()) else throw e
        }
        val store = StudyStore(context)
        val fresh = store.synced(fetched, sent) ?: return study
        // the home-screen shortcuts of a study that ended lead nowhere any more
        Shortcuts.update(context)
        // an exit questionnaire that opened after its moment (or whose moment passed while the phone was off)
        Alarms.notifyExits(context)
        Alarms.rearm(context)
        // the pages for answering without a connection follow once the lock is let go (refreshPages)
        return fresh
    }

    /**
     * The study to store after a sync: [fetched] (the server's answer, or the study after a 401) with what the
     * participant did on the phone while the call ran, as [stored] holds it now. Null when the study is gone from the
     * phone or was joined again with another secret: nothing to store. Quiet hours changed again stay pending (after a
     * 401 they stay anyway); a leave stays, also one the server has not heard of yet ([Study.leavePending]); the
     * notification sound ([Study.sound]) is the phone's.
     */
    fun merged(fetched: Study, stored: Study?, sentQuiet: QuietFrom?): Study? {
        if (stored == null || stored.secret != fetched.secret) return null
        // the sound is the participant's choice on the phone; the server knows nothing of it
        var fresh = fetched.copy(sound = stored.sound)
        val pending = stored.quietPending
        if (pending != null && pending != sentQuiet) fresh = fresh.copy(quietPending = pending)
        if (stored.status == "left" || stored.leavePending) fresh = fresh.copy(status = "left", leavePending = stored.leavePending)
        return fresh
    }

    /**
     * Sets the quiet hours of [study] to [quiet] (null: none): kept as pending at once, valid from tomorrow (the
     * server's rule), and sent with a sync; without a connection the next sync sends them. The server's answer then
     * says from which day they count.
     */
    suspend fun setQuiet(context: Context, study: Study, quiet: Quiet?): Unit = withContext(Dispatchers.IO) {
        val store = StudyStore(context)
        val current = store.get(study.code) ?: return@withContext
        val from = current.today().plusDays(1)
        store.update(current.copy(quietPending = QuietFrom(from, quiet)))
        Alarms.rearm(context)
        try {
            one(context, current)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SyncWorker.sendSoon(context)
        }
    }

    /**
     * Marks [study] as left on the phone at once, before anything is sent: no more prompts from now on, even if the
     * app is closed or a sync is running ([merged] keeps it). The server hears of it with [leave].
     */
    fun markLeft(context: Context, study: Study) {
        val store = StudyStore(context)
        val current = store.get(study.code) ?: return
        store.update(current.copy(status = "left", leavePending = true))
        Alarms.rearm(context)
        Shortcuts.update(context)
    }

    /**
     * Leaves [study]: shown as left at once ([markLeft]), and told to the server. When the server cannot be reached
     * (offline, 429, 5xx) the study keeps [Study.leavePending] and [all] tries again. Returns false in that case.
     */
    suspend fun leave(context: Context, study: Study): Boolean = withContext(Dispatchers.IO) {
        markLeft(context, study)
        val sent = try {
            lock.withLock {
                val current = StudyStore(context).get(study.code)
                if (current == null || !current.leavePending) true else leaveLocked(context, current)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
        if (!sent) SyncWorker.sendSoon(context)
        sent
    }

    /** Tells the server that [study] was left; only with [lock] held. */
    private suspend fun leaveLocked(context: Context, study: Study): Boolean {
        val store = StudyStore(context)
        OfflinePages(context).dropStudy(study.code)
        val sent = transmitLeave(
            upload = { sendLocked(context, study) && AnswerQueue(context).of(study.code).isEmpty() },
            leave = {
                try {
                    Api.leave(study.server, study.secret)
                    true
                } catch (e: ApiError) {
                    e.status == 401
                }
            },
        )
        if (sent) store.get(study.code)?.takeIf { it.secret == study.secret }?.let {
            store.update(it.copy(status = "left", leavePending = false))
        }
        return sent
    }

    /**
     * Deletes all answers of [study] on the server and removes the study from the phone. A 401 means there is
     * nothing left on the server, so the study goes too; any other error (offline, ...) is thrown and the study stays.
     */
    suspend fun erase(context: Context, study: Study): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                Api.erase(study.server, study.secret)
            } catch (e: ApiError) {
                if (e.status != 401) throw e
            }
            forgetLocked(context, study.code)
        }
    }

    /**
     * Removes [code] from the phone with everything kept for it: queued answers, kept pages and the log of its
     * prompts. Used by "Delete my answers" and "Remove from list".
     */
    suspend fun forget(context: Context, code: String): Unit = withContext(Dispatchers.IO) {
        lock.withLock { forgetLocked(context, code) }
    }

    private suspend fun forgetLocked(context: Context, code: String) {
        // A queue that cannot be read does not keep the study on the phone: its answers could never be sent, and
        // "Delete my answers" has already deleted them on the server. They go once the queue can be read again or the
        // participant discards it (AnswerQueue.forgetStudy; Abgleich 2026-10-15, T07-N-2).
        AnswerQueue(context).forgetStudy(code)
        StudyStore(context).remove(code)
        OfflinePages(context).dropStudy(code)
        PromptLog(context).removeStudy(code)
        TransmissionLog(context).remove(code)
        Alarms.rearm(context)
        Shortcuts.update(context)
    }

    /**
     * Joins [study] in place of [old], an earlier participation in the same study on this phone, after the participant
     * agreed to it. The earlier participation is closed first, with its own secret: its queued answers are sent (they
     * belong to it, never to the new person), a running one is left, then everything kept for it goes; then the new
     * one is stored. Trouble with the earlier one does not stop it: the new secret is already issued. Under the lock,
     * so that no sync writes the earlier study back.
     */
    suspend fun rejoin(context: Context, old: Study, study: Study): Unit = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                sendLocked(context, old)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // what could not be sent goes with the earlier participation
            }
            if (old.status == "active" || old.status == "pending" || old.status == "paused" || old.leavePending) {
                try {
                    Api.leave(old.server, old.secret)
                } catch (e: ApiError) {
                    // nothing more to do for it: the participant chose to start again
                }
            }
            forgetLocked(context, old.code)
            StudyStore(context).put(study)
            Alarms.rearm(context)
        }
    }

    /**
     * Sends the queued answers of [study] (POST /offline, each with its uuid). Returns false when some must wait for
     * the network (no network, 429, 5xx). All unacknowledged answers stay encrypted on the phone; refusals are
     * noted for clarification on the study card. Changed questionnaire pages are fetched again separately.
     * Only with [lock] held.
     */
    private suspend fun sendLocked(context: Context, study: Study): Boolean {
        val queue = AnswerQueue(context)
        var kept = false
        for (item in queue.of(study.code)) {
            // "started" is in the study's time (virtual for a fast-forward person)
            if (AnswerQueue.tooOld(item, study.now())) {
                if (item.refusal != "expired") queue.replace(item.copy(refusal = "expired"))
                continue
            }
            val error = try {
                val reply = Api.offlineAnswer(study.server, study.secret, item)
                if (AnswerQueue.acknowledged(reply)) {
                    TransmissionLog(context).confirmed(study.code, item.module, reply.optString("status") == "review", item.id, item.moduleName.ifBlank { study.modules.firstOrNull { it.key == item.module }?.name.orEmpty() }, study.realAt(item.completed))
                    item.due?.let { PromptLog(context).completed(study.code, item.module, it) }
                    null
                } else ApiError("unconfirmed", 409)
            } catch (e: ApiError) {
                e
            }
            if (error != null) queue.attempted(item.id, if (error.status == 429) "rate_limit" else error.key)
            when (queue.recordDelivery(queue.get(item.id) ?: item, error, Api.clockSkew)) {
                AnswerQueue.Fate.KEEP -> kept = true
                AnswerQueue.Fate.CHANGED -> {
                    OfflinePages(context).drop(study.code, item.module)
                }
                else -> Unit
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
     * Whether a full sync started by bringing the app to the front should run at [now]: none is [running], and the
     * last one without network trouble ended at least [RESUME_GAP_MS] ago ([last]; 0 = none yet; a time after [now],
     * the clock set back, counts as long ago).
     */
    fun due(last: Long, now: Long, running: Boolean): Boolean =
        !running && (last == 0L || now - last !in 0 until RESUME_GAP_MS)

    /**
     * [all] when the app comes to the front, but not while one runs and not again within [RESUME_GAP_MS] of the last
     * one that reached the server: every return from an entry or a permission dialog would otherwise fetch every
     * study and check every kept page. Returns null when it did not run.
     */
    suspend fun allIfDue(context: Context): Boolean? {
        if (!due(lastAll, System.currentTimeMillis(), lock.isLocked)) return null
        return all(context)
    }

    /**
     * Sends the queued answers, fetches all studies that can bring news ([wanted]) and sends pending leaves; returns
     * true when the network was missing or an answer or a leave could not be sent yet (SyncWorker then tries again).
     * The answers go first: those given before leaving belong to the study. A study whose sync fails in any other way
     * (an answer of the server this app cannot read) is skipped; the others go on.
     */
    suspend fun all(context: Context): Boolean = withContext(Dispatchers.IO) {
        val synced = mutableListOf<Study>()
        val offline = lock.withLock {
            var offline = false
            var checked = false
            // answers of studies taken off the phone while the queue could not be read (T07-N-2)
            runCatching { AnswerQueue(context).retryForget() }
            val exits = PromptLog(context).exits()
            val store = StudyStore(context)
            for (code in store.all().map { it.code }) {
                // read again: a leave or a removal while the studies before were synced
                val s = store.get(code) ?: continue
                try {
                    checked = checked || s.leavePending || wanted(s, s.code in exits) || AnswerQueue(context).of(s.code).isNotEmpty()
                    if (!s.leavePending && !sendLocked(context, s)) offline = true
                    when {
                        s.leavePending -> if (!leaveLocked(context, s)) offline = true
                        wanted(s, s.code in exits) -> synced += oneLocked(context, s)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: ApiError) {
                    offline = true
                } catch (e: Exception) {
                    offline = true // retry unreadable storage and failed uploads too
                }
            }
            if (!offline) lastAll = System.currentTimeMillis()
            val status = context.getSharedPreferences("sync_status", Context.MODE_PRIVATE).edit().putBoolean("failed", offline)
            if (!offline && checked) status.putLong("success", lastAll)
            status.apply()
            offline
        }
        // the kept pages without the lock: a leave or a deletion meanwhile does not wait for the downloads (AN-9)
        for (s in synced) refreshPages(context, s)
        offline
    }
}
