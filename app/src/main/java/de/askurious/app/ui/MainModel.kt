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

    /** Studies with retained answers needing clarification. */
    var refused by mutableStateOf<Set<String>>(emptySet())
        private set

    /** The phone's clock is off and answers given without a connection wait for it (AnswerQueue.Fate.CLOCK). */
    var clockOff by mutableStateOf(false)
        private set

    /** The studies were read once: until then an empty list means "not read yet", not "no studies". */
    var loaded by mutableStateOf(false)
        private set
    var queueUnreadable by mutableStateOf(false)
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
            val (s, items) = withContext(Dispatchers.IO) {
                val queue = AnswerQueue(context)
                val items = try { queue.all() } catch (e: java.io.IOException) { null }
                Pair(StudyStore(context).all(), items)
            }
            if (mine != reloads) return@launch
            studies = s
            queueUnreadable = items == null
            waiting = items.orEmpty().groupingBy { it.study }.eachCount()
            // Legacy refusal notices refer to answers older versions already deleted; never claim those survived.
            refused = items.orEmpty().filter { it.refusal != null }.map { it.study }.toSet()
            clockOff = clockOff(items.orEmpty().size, Api.clockSkew)
            loaded = true
        }
    }

    /** Syncs when the app comes to the front: not while a sync runs, nor again within a few minutes (Sync.allIfDue). */
    fun syncIfDue() {
        viewModelScope.launch { if (Sync.allIfDue(context) != null) reload() }
    }

    var transferring by mutableStateOf(false); private set
    fun transfer() {
        if (transferring) return
        transferring = true
        viewModelScope.launch { try { Sync.all(context) } finally { transferring = false; reload() } }
    }

    fun setQuiet(study: Study, quiet: Quiet?) {
        viewModelScope.launch {
            Sync.setQuiet(context, study, quiet)
            reload()
        }
    }

    /** The notification sound of [study] on or off; kept only on the phone (Study.sound, Alarms.channelOf). */
    fun setSound(study: Study, on: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val store = StudyStore(context)
                store.get(study.code)?.let { store.update(it.copy(sound = on)) }
            }
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
    fun remove(code: String, done: (ApiError?) -> Unit) {
        viewModelScope.launch {
            val error = try {
                Sync.forget(context, code)
                null
            } catch (e: java.io.IOException) {
                ApiError("error")
            }
            reload()
            done(error)
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
            } catch (e: java.io.IOException) {
                ApiError("error")
            }
            reload()
            done(error)
        }
    }

    /** "Discard unreadable answers": the queue that cannot be read goes, new answers can be kept again (T07-N-2). */
    fun discardQueue(done: (Boolean) -> Unit) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { AnswerQueue(context).discardUnreadable() }.isSuccess }
            reload()
            done(ok)
        }
    }

    fun clearRefused(code: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { AnswerQueue(context).clearRefused(code) }
            reload()
        }
    }

    var joinBusy by mutableStateOf(false); private set
    var joinInfo by mutableStateOf<String?>(null); private set
    var joinError by mutableStateOf<ApiError?>(null); private set
    var joinedStudy by mutableStateOf<Study?>(null); private set
    var entryAddress: String? = null; private set
    fun consumedJoin() { joinedStudy = null; entryAddress = null; joinInfo = null; joinError = null }

    /** The public information of a study for the join screen; [done] gets it or the error. */
    fun lookUp(server: String, code: String, done: (JSONObject?, ApiError?) -> Unit) {
        if (joinBusy) return
        joinBusy = true; joinInfo = null; joinError = null
        viewModelScope.launch {
            try {
                val info = Api.join(server, code)
                joinInfo = info.toString(); done(info, null)
            } catch (e: ApiError) {
                joinError = e; done(null, e)
            } finally { joinBusy = false }
        }
    }

    /**
     * Enrols with the join code [code] of [info] at [server]. When the same study is on the phone already (the
     * participant agreed to join again), the earlier participation is closed first with its own secret (Sync.rejoin):
     * its waiting answers never go to the new person. [done] gets the new study or the error.
     */
    fun enrol(server: String, code: String, info: JSONObject, done: (Study?, ApiError?) -> Unit) {
        if (joinBusy) return
        joinBusy = true; joinError = null; joinedStudy = null
        viewModelScope.launch {
            try {
                val r = Api.enrol(server, code)
                val secret = r.optString("secret").ifBlank { throw ApiError("error") }
                // a personal code connected this phone to its participation again ("rejoined"): the server's status,
                // so that a participation past its entry questionnaire does not begin anew
                val status = if (r.optBoolean("rejoined")) r.optString("status").ifBlank { "pending" } else "pending"
                val study = Study.fromJson(JSONObject(info.toString()).put("status", status).put("summary", info.optString("description")).put("modules", JSONArray()), secret, server)
                val store = StudyStore(context)
                val old = withContext(Dispatchers.IO) { store.get(study.code) }
                if (old != null) Sync.rejoin(context, old, study) else withContext(Dispatchers.IO) { store.put(study) }
                val fresh = try { Study.fromJson(Api.study(server, secret), secret, server).copy(sound = old?.sound ?: study.sound).also { withContext(Dispatchers.IO) { store.update(it) } } }
                            catch (_: ApiError) { study }
                entryAddress = r.optString("url").takeIf { it.isNotBlank() && it != "null" }
                joinedStudy = fresh
                reload()
                done(fresh, null)
            } catch (e: ApiError) {
                joinError = e; done(null, e)
            } catch (e: java.io.IOException) {
                joinError = ApiError("error"); done(null, joinError)
            } catch (e: org.json.JSONException) {
                // the server's answer lacks something this app needs
                joinError = ApiError("error"); done(null, joinError)
            } finally { joinBusy = false }
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
