package de.askurious.app.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.askurious.app.data.AnswerQueue
import de.askurious.app.data.Api
import de.askurious.app.data.ApiError
import de.askurious.app.data.Quiet
import de.askurious.app.data.Study
import de.askurious.app.data.StudyStore
import de.askurious.app.data.Sync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * The state of the start page and what the participant does there (architecture concept 2026-10-12, K3): the studies,
 * the answers waiting to be sent, and the calls to the server. MainActivity only draws it. A call runs in the model's
 * scope, so turning the phone or a dark-mode switch (a new activity) no longer cancels a leave, a deletion or a join
 * halfway; the screens read the result when it comes.
 */
class MainModel(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<Application>()

    var studies by mutableStateOf<List<Study>>(emptyList())
        private set

    /** Answers waiting to be sent, per study code (AnswerQueue). */
    var waiting by mutableStateOf<Map<String, Int>>(emptyMap())
        private set

    /** Studies with an answer the server refused for today's limit, not yet seen by the participant (AnswerQueue). */
    var refused by mutableStateOf<Set<String>>(emptySet())
        private set

    /** The phone's clock is off and answers given without a connection wait for it (AnswerQueue.Fate.CLOCK). */
    var clockOff by mutableStateOf(false)
        private set

    /** The studies were read once: until then an empty list means "not read yet", not "no studies". */
    var loaded by mutableStateOf(false)
        private set

    /** Counts the reloads: only the newest one sets the state, one started earlier may end later. */
    private var reloads = 0

    /**
     * Reads the studies and the queue again (after a sync, an entry, a change on the start page). Off the main thread:
     * the first read of a process opens the stored texts with the Keystore (audit 2026-10-13, AN-8).
     */
    fun reload() {
        val mine = ++reloads
        viewModelScope.launch {
            val (s, items, r) = withContext(Dispatchers.IO) {
                val queue = AnswerQueue(context)
                Triple(StudyStore(context).all(), queue.all(), queue.refusedStudies())
            }
            if (mine != reloads) return@launch
            studies = s
            waiting = items.groupingBy { it.study }.eachCount()
            refused = r
            clockOff = clockOff(items.size, Api.clockSkew)
            loaded = true
        }
    }

    /** Syncs when the app comes to the front: not while a sync runs, nor again within a few minutes (Sync.allIfDue). */
    fun syncIfDue() {
        viewModelScope.launch { if (Sync.allIfDue(context) != null) reload() }
    }

    fun setQuiet(study: Study, quiet: Quiet?) {
        viewModelScope.launch {
            Sync.setQuiet(context, study, quiet)
            reload()
        }
    }

    /** Shown as left at once, also while a sync runs; the server hears of it as soon as it can. */
    fun leave(study: Study) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { Sync.markLeft(context, study) }
            reload()
            Sync.leave(context, study)
            reload()
        }
    }

    /** Takes the study off the phone, with its queued answers and kept pages. */
    fun remove(code: String) {
        viewModelScope.launch {
            Sync.forget(context, code)
            reload()
        }
    }

    /**
     * Deletes the participant's answers at the server; [done] gets null when they are gone, else the error, and the
     * study stays (its secret).
     */
    fun erase(study: Study, done: (ApiError?) -> Unit) {
        viewModelScope.launch {
            val error = try {
                Sync.erase(context, study)
                null
            } catch (e: ApiError) {
                e
            }
            reload()
            done(error)
        }
    }

    fun clearRefused(code: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { AnswerQueue(context).clearRefused(code) }
            reload()
        }
    }

    /** The public information of a study for the join screen; [done] gets it or the error. */
    fun lookUp(server: String, code: String, done: (JSONObject?, ApiError?) -> Unit) {
        viewModelScope.launch {
            try {
                done(Api.join(server, code), null)
            } catch (e: ApiError) {
                done(null, e)
            }
        }
    }

    /**
     * Enrols with the join code [code] of [info] at [server]. When the same study is on the phone already (the
     * participant agreed to join again), the earlier participation is closed first with its own secret (Sync.rejoin):
     * its waiting answers never go to the new person. [done] gets the new study or the error.
     */
    fun enrol(server: String, code: String, info: JSONObject, done: (Study?, ApiError?) -> Unit) {
        viewModelScope.launch {
            try {
                val r = Api.enrol(server, code)
                val secret = r.optString("secret").ifBlank { throw ApiError("error") }
                val study = Study.fromJson(JSONObject(info.toString()).put("status", "pending").put("modules", JSONArray()), secret, server)
                val store = StudyStore(context)
                val old = withContext(Dispatchers.IO) { store.get(study.code) }
                if (old != null) Sync.rejoin(context, old, study) else withContext(Dispatchers.IO) { store.put(study) }
                reload()
                done(study, null)
            } catch (e: ApiError) {
                done(null, e)
            } catch (e: org.json.JSONException) {
                // the server's answer lacks something this app needs
                done(null, ApiError("error"))
            }
        }
    }

    companion object {
        /**
         * Whether the start page says the phone's clock is wrong: answers wait and the server's clock (from its Date
         * header) differs by more than [AnswerQueue.MAX_SKEW_MS].
         */
        fun clockOff(waiting: Int, skew: Long?): Boolean = waiting > 0 && skew?.let { Math.abs(it) > AnswerQueue.MAX_SKEW_MS } == true
    }
}
