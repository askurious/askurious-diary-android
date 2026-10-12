package de.askurious.app.data

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Answers given without a connection (EntryActivity, offline page), waiting to be sent with POST /offline.
 * Kept in the app's private storage like the studies (no backup, not moved to a new phone) and only until the
 * server took them or refused them for good; the server takes an answer at most [MAX_AGE_MS] after the page was
 * opened, so an older one is dropped unsent.
 */
class AnswerQueue(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("queue", Context.MODE_PRIVATE)

    /**
     * One answer. [due] and [notified]: of the prompt, null for a module the participant started; [started]: the
     * page was shown; [completed]: the participant submitted it; [version]: of the kept page; [form]: the filled
     * form as participant.js passed it, {field: [values]}, as JSON text. [id] goes to the server as "uuid": an answer
     * sent twice (a timeout after the server stored it, two syncs at once) is stored only once. [refusedSince]: real
     * epoch milliseconds of the first refusal that may pass (401, "paused"; see [Fate.WAIT]), null until then.
     */
    data class Item(
        val id: String,
        val study: String,
        val module: String,
        val due: Long?,
        val notified: Long?,
        val started: Long,
        val completed: Long,
        val version: String,
        val form: String,
        val refusedSince: Long? = null,
    ) {
        /** The body of POST /offline; "uuid" makes sending it again harmless (the server answers as the first time). */
        fun body(): JSONObject = JSONObject().put("uuid", id).put("module", module)
            .put("due", due ?: JSONObject.NULL).put("notified", notified ?: JSONObject.NULL)
            .put("started", started).put("completed", completed).put("version", version).put("form", JSONObject(form))

        fun toJson(): JSONObject = body().put("id", id).put("study", study)
            .put("refused_since", refusedSince ?: JSONObject.NULL)

        companion object {
            fun fromJson(o: JSONObject) = Item(
                id = o.getString("id"),
                study = o.getString("study"),
                module = o.getString("module"),
                due = if (o.isNull("due")) null else o.optLong("due"),
                notified = if (o.isNull("notified")) null else o.optLong("notified"),
                started = o.optLong("started"),
                completed = o.optLong("completed"),
                version = o.optString("version"),
                form = (o.optJSONObject("form") ?: JSONObject()).toString(),
                refusedSince = if (o.isNull("refused_since")) null else o.optLong("refused_since"),
            )
        }
    }

    @Synchronized
    fun all(): List<Item> {
        val raw = runCatching { prefs.getString(KEY, null) }.getOrNull() ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until arr.length()).mapNotNull { runCatching { Item.fromJson(arr.getJSONObject(it)) }.getOrNull() }
    }

    fun of(study: String): List<Item> = all().filter { it.study == study }

    @Synchronized
    fun add(study: String, module: String, due: Long?, notified: Long?, started: Long, completed: Long, version: String, form: String): Item {
        val item = Item(UUID.randomUUID().toString(), study, module, due, notified, started, completed, version, form)
        save(all() + item)
        return item
    }

    @Synchronized
    fun remove(id: String) = save(all().filterNot { it.id == id })

    @Synchronized
    fun removeStudy(study: String) {
        save(all().filterNot { it.study == study })
        clearRefused(study)
    }

    /** Stores [item] again in place of the one with its id (the time of a first refusal); nothing if it is gone. */
    @Synchronized
    fun replace(item: Item) {
        val all = all()
        if (all.any { it.id == item.id }) save(all.map { if (it.id == item.id) item else it })
    }

    /**
     * An answer of [study] the server refused because today's limit of the report was reached (409 daily_limit):
     * the study card says so once, until the participant closes the note.
     */
    @Synchronized
    fun noteRefused(study: String) = prefs.edit { putStringSet(REFUSED, refusedStudies() + study) }

    /** The studies with an answer refused for the daily limit that the participant has not seen yet. */
    @Synchronized
    fun refusedStudies(): Set<String> = runCatching { prefs.getStringSet(REFUSED, null)?.toSet() }.getOrNull() ?: emptySet()

    @Synchronized
    fun clearRefused(study: String) = prefs.edit { putStringSet(REFUSED, refusedStudies() - study) }

    /** Whether an answer to the prompt of [module] due at [due] is waiting: it counts as opened for the reminder. */
    fun has(study: String, module: String, due: Long): Boolean = all().any { it.study == study && it.module == module && it.due == due }

    /** How many answers to the self-started [module] were given today without a connection (max_per_day). */
    fun today(study: String, module: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
        fun date(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val day = date(now)
        return all().count { it.study == study && it.module == module && date(it.completed) == day }
    }

    private fun save(items: List<Item>) {
        prefs.edit { putString(KEY, JSONArray(items.map { it.toJson() }).toString()) }
    }

    /** What becomes of a queued answer after the server's answer to POST /offline. */
    enum class Fate {
        /** stored by the server: remove it */
        SENT,

        /** no network, the rate limit or a fault of the server: try again later */
        KEEP,

        /**
         * refused for now (401: the server does not know the secret; 409 "paused"): kept with the same grace as the
         * study itself ([Study.UNAUTHORISED_GRACE_MS], [afterRefusal]), tried again at the next regular sync, not at
         * once: a server restored from a backup or a pause of the researchers must not cost the answer
         */
        WAIT,

        /**
         * refused because the phone's clock is wrong (a 400 while the server's clock differs by more than
         * [MAX_SKEW_MS], or "clock"): kept until it is too old ([tooOld]), the app shows a note
         */
        CLOCK,

        /** refused for good (answered already, expired, unknown module): remove it unsent */
        DROP,

        /** today's limit of the self-started report was reached (409 daily_limit): remove it, the card says so */
        LIMIT,

        /** the questionnaire changed since the page was kept: remove the answer and the kept page, fetch it again */
        CHANGED,
    }

    companion object {
        private const val KEY = "items"
        private const val REFUSED = "refused"

        /** The server takes an answer up to 7 days after "started". */
        const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

        /** How far the phone's clock may be off before the server refuses an answer (its LATE_SECONDS). */
        const val MAX_SKEW_MS = 5L * 60 * 1000

        /**
         * [error]: null when the server answered 200. [skew]: the phone's clock minus the server's (milliseconds, from
         * the Date header of the server's answer), null when unknown.
         */
        fun fate(error: ApiError?, skew: Long? = null): Fate = when {
            error == null -> Fate.SENT
            error.transient -> Fate.KEEP
            error.key == "changed" -> Fate.CHANGED
            error.key == "daily_limit" -> Fate.LIMIT
            error.status == 401 || error.key == "paused" -> Fate.WAIT
            error.key == "clock" || (error.status == 400 && skew != null && Math.abs(skew) > MAX_SKEW_MS) -> Fate.CLOCK
            // 409 done, 410 expired, 400 unknown and anything else the server will not take later either
            else -> Fate.DROP
        }

        /**
         * [item] after a refusal that may pass ([Fate.WAIT]) at the real moment [now]: with the time of the first
         * refusal noted, or null when the server has refused it for more than [Study.UNAUTHORISED_GRACE_MS] (then it
         * is dropped, like the study itself after that long).
         */
        fun afterRefusal(item: Item, now: Long): Item? {
            val since = item.refusedSince ?: return item.copy(refusedSince = now)
            return if (now - since > Study.UNAUTHORISED_GRACE_MS) null else item
        }

        /** Whether [item] is too old for the server at [now]: dropped without trying. */
        fun tooOld(item: Item, now: Long): Boolean = now - item.started > MAX_AGE_MS
    }
}
