package de.askurious.app.data

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import java.io.IOException

/**
 * Answers given without a connection (EntryActivity, offline page), waiting to be sent with POST /offline.
 * Kept in the app's private storage like the studies (no backup, not moved to a new phone) and only until the
 * server confirmed durable storage or the participant explicitly removes them. Age and refusals never delete an
 * unacknowledged answer. Production uses [QueueDatabase]: individually sealed rows, indexed queries and durable
 * single-row writes. The preferences constructor retains the legacy codec for migration regression tests.
 */
class AnswerQueue internal constructor(private val prefs: SharedPreferences, sealer: Sealer, private val rows: QueueDatabase? = null) {
    constructor(context: Context, sealer: Sealer = KeystoreSealer) :
        this(context.applicationContext.getSharedPreferences("queue", Context.MODE_PRIVATE), sealer,
            QueueDatabase(context, context.applicationContext.getSharedPreferences("queue", Context.MODE_PRIVATE), sealer))

    private val text = SealedText(prefs, KEY, sealer, AAD)

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
        val refusal: String? = null,
        val attempted: Long? = null,
        val error: String? = null,
        val moduleName: String = "",
    ) {
        /** The body of POST /offline; "uuid" makes sending it again harmless (the server answers as the first time). */
        fun body(): JSONObject = JSONObject().put("uuid", id).put("module", module)
            .put("due", due ?: JSONObject.NULL).put("notified", notified ?: JSONObject.NULL)
            .put("started", started).put("completed", completed).put("version", version).put("form", JSONObject(form))

        fun toJson(): JSONObject = body().put("id", id).put("study", study)
            .put("refused_since", refusedSince ?: JSONObject.NULL)
            .put("refusal", refusal ?: JSONObject.NULL)
            .put("attempted", attempted ?: JSONObject.NULL).put("error", error ?: JSONObject.NULL).put("module_name", moduleName)

        companion object {
            fun fromJson(o: JSONObject) = Item(
                id = o.getString("id"),
                study = o.getString("study"),
                module = o.getString("module"),
                due = if (o.isNull("due")) null else o.optLong("due"),
                notified = if (o.isNull("notified")) null else o.optLong("notified"),
                started = o.getLong("started"),
                completed = o.getLong("completed"),
                version = o.getString("version"),
                form = o.getJSONObject("form").toString(),
                refusedSince = if (o.isNull("refused_since")) null else o.optLong("refused_since"),
                refusal = if (o.isNull("refusal")) null else o.getString("refusal"),
                attempted = if (o.isNull("attempted")) null else o.optLong("attempted"),
                error = if (o.isNull("error")) null else o.optString("error"),
                moduleName = o.optString("module_name"),
            )
        }
    }

    fun all(): List<Item> = synchronized(SealedText.LOCK) {
        rows?.let { return@synchronized it.all() }
        val opened = text.read()
        if (opened.unreadable) throw IOException("Queued answers cannot be decrypted")
        val raw = opened.text ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { Item.fromJson(arr.getJSONObject(it)) }
        } catch (e: Exception) {
            throw IOException("Queued answers cannot be read", e)
        }
    }

    fun of(study: String): List<Item> = rows?.of(study) ?: all().filter { it.study == study }

    fun get(id: String): Item? = if (rows != null) rows.get(id) else all().firstOrNull { it.id == id }

    fun add(study: String, module: String, due: Long?, notified: Long?, started: Long, completed: Long, version: String, form: String,
            id: String = UUID.randomUUID().toString(), moduleName: String = ""): Item =
        synchronized(SealedText.LOCK) {
            if (rows != null) return@synchronized rows.add(Item(id, study, module, due, notified, started, completed, version, form, moduleName = moduleName))
            val existing = all()
            existing.firstOrNull { it.id == id }?.let { return@synchronized it }
            val item = Item(id, study, module, due, notified, started, completed, version, form, moduleName = moduleName)
            save(existing + item)
            item
        }

    fun attempted(id: String, error: String): Unit = synchronized(SealedText.LOCK) {
        rows?.let { it.update(id) { item -> item.copy(attempted = System.currentTimeMillis(), error = error) }; return@synchronized }
        save(all().map { if (it.id == id) it.copy(attempted = System.currentTimeMillis(), error = error) else it })
    }

    fun remove(id: String): Unit = synchronized(SealedText.LOCK) {
        if (rows != null) rows.remove(id) else save(all().filterNot { it.id == id })
    }

    fun removeStudy(study: String): Unit = synchronized(SealedText.LOCK) {
        if (rows != null) rows.removeStudy(study) else save(all().filterNot { it.study == study })
        clearRefused(study)
        if (study in forgetLater()) prefs.edit { putStringSet(FORGET, forgetLater() - study) }
    }

    /**
     * Removes the answers of [study] for "Remove from list" and "Delete my answers"; false when the queue cannot be
     * read: the study is then noted ([retryForget]) and its answers go as soon as the queue can be read again or is
     * discarded, so that taking the study off the phone never hangs on an unreadable queue (Abgleich 2026-10-15,
     * T07-N-2).
     */
    fun forgetStudy(study: String): Boolean = synchronized(SealedText.LOCK) {
        try {
            removeStudy(study)
            true
        } catch (e: IOException) {
            if (!prefs.edit().putStringSet(FORGET, forgetLater() + study).commit()) throw e
            clearRefused(study)
            false
        }
    }

    /** The answers of studies taken off the phone while the queue could not be read ([forgetStudy]) go now. */
    fun retryForget(): Unit = synchronized(SealedText.LOCK) {
        forgetLater().forEach { runCatching { removeStudy(it) } }
    }

    private fun forgetLater(): Set<String> = runCatching { prefs.getStringSet(FORGET, null)?.toSet() }.getOrNull() ?: emptySet()

    /**
     * Discards every queued answer: the participant's explicit choice when the queue cannot be read any more (the
     * phone's key was lost, the file is damaged). Those answers could never be sent; without this no new answer could
     * be kept either (Abgleich 2026-10-15, T07-N-2).
     */
    fun discardUnreadable(): Unit = synchronized(SealedText.LOCK) {
        if (rows != null) rows.discard() else text.remove(durable = true)
        prefs.edit { remove(FORGET) }
    }

    /** Stores [item] again in place of the one with its id (the time of a first refusal); nothing if it is gone. */
    fun replace(item: Item): Unit = synchronized(SealedText.LOCK) {
        rows?.let { it.update(item.id) { item }; return@synchronized }
        val all = all()
        val previous = all.firstOrNull { it.id == item.id }
        if (previous != null && previous != item) save(all.map { if (it.id == item.id) item else it })
    }

    /**
     * An answer of [study] the server refused because today's limit of the report was reached (409 daily_limit):
     * the study card says so once, until the participant closes the note.
     */
    fun noteRefused(study: String): Unit = synchronized(SealedText.LOCK) { prefs.edit { putStringSet(REFUSED, refusedStudies() + study) } }

    /** The studies with an answer refused for the daily limit that the participant has not seen yet. */
    fun refusedStudies(): Set<String> = runCatching { prefs.getStringSet(REFUSED, null)?.toSet() }.getOrNull() ?: emptySet()

    fun clearRefused(study: String): Unit = synchronized(SealedText.LOCK) { prefs.edit { putStringSet(REFUSED, refusedStudies() - study) } }

    /**
     * The refused answers whose notice the participant closed ("Close" on the study card): their ids. The notice
     * stays away for them and comes back with the next refusal (UX audit 2026-10-16, B5).
     */
    fun seenRefusals(): Set<String> = runCatching { prefs.getStringSet(SEEN, null)?.toSet() }.getOrNull() ?: emptySet()

    /** Closes the notice of the refused answers of [study]; ids of answers no longer kept are dropped on the way. */
    fun dismissRefused(study: String): Unit = synchronized(SealedText.LOCK) {
        val items = all()
        val kept = items.map { it.id }.toSet()
        val now = items.filter { it.study == study && it.refusal != null }.map { it.id }
        prefs.edit { putStringSet(SEEN, (seenRefusals() intersect kept) + now) }
        clearRefused(study)
    }

    /** Whether an answer to the prompt of [module] due at [due] is waiting: it counts as opened for the reminder. */
    fun has(study: String, module: String, due: Long): Boolean = rows?.has(study, module, due)
        ?: all().any { it.study == study && it.module == module && it.due == due }

    /**
     * How many self-started answers to [module] were given today without a connection (max_per_day); answers to its
     * prompts (with a due time) do not count, for a prompted module the participant may also start.
     */
    fun today(study: String, module: String, now: Long, zone: ZoneId = ZoneId.systemDefault()): Int {
        rows?.let { return it.today(study, module, now, zone) }
        fun date(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
        val day = date(now)
        return all().count {
            it.study == study && it.module == module && it.due == null && date(it.completed) == day
        }
    }

    private fun save(items: List<Item>) {
        if (items.isEmpty()) text.remove(durable = true)
        else text.write(JSONArray(items.map { it.toJson() }).toString(), durable = true)
    }

    /** Apply a delivery result atomically to the persisted queue; only acknowledged storage removes data. */
    fun recordDelivery(item: Item, error: ApiError?, skew: Long? = null, now: Long = System.currentTimeMillis()): Fate {
        val result = fate(error, skew)
        when (result) {
            Fate.SENT -> remove(item.id)
            Fate.KEEP -> Unit
            Fate.WAIT -> replace(afterRefusal(item, now).copy(refusal = error?.key ?: "unauthorised"))
            Fate.CLOCK -> replace(item.copy(refusal = "clock"))
            Fate.CHANGED -> replace(item.copy(refusal = "changed"))
            Fate.LIMIT -> replace(item.copy(refusal = "daily_limit"))
            Fate.REVIEW -> replace(item.copy(refusal = error?.key ?: "unconfirmed"))
        }
        return result
    }

    /** What becomes of a queued answer after the server's answer to POST /offline. */
    enum class Fate {
        /** stored by the server: remove it */
        SENT,

        /** no network, the rate limit or a fault of the server: try again later */
        KEEP,

        /**
         * refused for now (401 or "paused"): kept indefinitely, tried again at the next regular sync,
         * without an immediate retry loop
         */
        WAIT,

        /**
         * refused because the phone's clock is wrong (a 400 while the server's clock differs by more than
         * [MAX_SKEW_MS], or "clock"): kept for clarification, the app shows a note
         */
        CLOCK,

        /** Not accepted: keep the original for clarification, without an immediate retry loop. */
        REVIEW,

        /** Daily limit reached: retain the original for clarification, the card says so. */
        LIMIT,

        /** Questionnaire changed: retain the answer, replace only the cached questionnaire page. */
        CHANGED,
    }

    companion object {
        private const val KEY = "items"
        private const val AAD = "queue"
        private const val REFUSED = "refused"
        private const val FORGET = "forget"
        private const val SEEN = "refusals_seen"

        /** The server takes an answer up to 7 days after "started". */
        const val MAX_AGE_MS = 7L * 24 * 3600 * 1000

        /** How far the phone's clock may be off before the server refuses an answer (its LATE_SECONDS). */
        const val MAX_SKEW_MS = 5L * 60 * 1000

        /**
         * [error]: null only after an acknowledged stored/review reply. [skew]: the phone's clock minus the server's (milliseconds, from
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
            else -> Fate.REVIEW
        }

        /**
         * [item] after a refusal that may pass ([Fate.WAIT]) at the real moment [now]: with the time of the first
         * refusal noted. A prolonged refusal never deletes the original.
         */
        fun afterRefusal(item: Item, now: Long): Item = item.copy(refusedSince = item.refusedSince ?: now)

        /** Only these replies confirm the server retained the complete original, including review cases. */
        fun acknowledged(reply: JSONObject): Boolean = reply.optString("status") in setOf("stored", "review")

        /** Whether [item] exceeds the server's acceptance window: retain locally for clarification. */
        fun tooOld(item: Item, now: Long): Boolean = now - item.started > MAX_AGE_MS
    }
}
