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
     * form as participant.js passed it, {field: [values]}, as JSON text.
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
    ) {
        /** The body of POST /offline. */
        fun body(): JSONObject = JSONObject().put("module", module)
            .put("due", due ?: JSONObject.NULL).put("notified", notified ?: JSONObject.NULL)
            .put("started", started).put("completed", completed).put("version", version).put("form", JSONObject(form))

        fun toJson(): JSONObject = body().put("id", id).put("study", study)

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
            )
        }
    }

    @Synchronized
    fun all(): List<Item> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
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
    fun removeStudy(study: String) = save(all().filterNot { it.study == study })

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

        /** refused for good (answered already, expired, unknown module, secret unknown): remove it unsent */
        DROP,

        /** the questionnaire changed since the page was kept: remove the answer and the kept page, fetch it again */
        CHANGED,
    }

    companion object {
        private const val KEY = "items"

        /** The server takes an answer up to 7 days after "started". */
        const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

        /** [error]: null when the server answered 200. */
        fun fate(error: ApiError?): Fate = when {
            error == null -> Fate.SENT
            error.transient -> Fate.KEEP
            error.key == "changed" -> Fate.CHANGED
            // 409 done, 410 expired, 400 unknown, 401 and anything else the server will not take later either
            else -> Fate.DROP
        }

        /** Whether [item] is too old for the server at [now]: dropped without trying. */
        fun tooOld(item: Item, now: Long): Boolean = now - item.started > MAX_AGE_MS
    }
}
