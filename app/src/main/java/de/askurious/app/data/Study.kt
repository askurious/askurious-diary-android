package de.askurious.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

/**
 * When a module is due: at fixed times on some weekdays, at random times within time windows, or whenever the
 * participant wants to report. Fixed and random modules are prompted (notification, expiry, reminder).
 */
sealed class Trigger {
    /** [weekdays]: ISO numbers, 1 = Monday .. 7 = Sunday. */
    data class Fixed(val times: List<LocalTime>, val weekdays: Set<Int>) : Trigger()

    /**
     * [perDay] prompts a day at times drawn per person and day (RandomTimes) within [windows] ("HH:MM" start and
     * end, local time; an end before the start runs over midnight into the early morning of the same day), at
     * least [minGap] minutes apart, outside the participant's quiet hours.
     */
    data class Random(val perDay: Int, val windows: List<Pair<String, String>>, val minGap: Int, val weekdays: Set<Int>) : Trigger()
    data class Self(val label: String, val maxPerDay: Int) : Trigger()

    /** Whether the schedule gives prompts for this trigger (fixed or random). */
    val prompted: Boolean get() = this !is Self
}

/** Quiet hours from [start] to [end] ("HH:MM", local time; an end before the start runs over midnight). */
data class Quiet(val start: String, val end: String) {
    fun toJson(): JSONArray = JSONArray(listOf(start, end))

    companion object {
        private val TIME = Regex("^([01]?\\d|2[0-3]):([0-5]\\d)$")

        /** ["22:00", "07:00"] or null; anything else is no quiet hours. */
        fun fromJson(a: JSONArray?): Quiet? {
            if (a == null || a.length() != 2) return null
            val start = hhmm(a.optString(0)) ?: return null
            val end = hhmm(a.optString(1)) ?: return null
            return Quiet(start, end)
        }

        /** "7:05" or "07:05" as "07:05"; null when it is no time of day. */
        fun hhmm(text: String): String? {
            val m = TIME.matchEntire(text.trim()) ?: return null
            return "%02d:%s".format(java.util.Locale.ROOT, m.groupValues[1].toInt(), m.groupValues[2])
        }
    }
}

/** Quiet hours [quiet] (null: none) valid from day [from] on, until a later entry of the history. */
data class QuietFrom(val from: LocalDate, val quiet: Quiet?) {
    fun toJson(): JSONObject = JSONObject().put("from", from.toString()).put("q", quiet?.toJson() ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): QuietFrom? {
            val from = o.optStringOrNull("from")?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
            return QuietFrom(from, if (o.isNull("q")) null else Quiet.fromJson(o.optJSONArray("q")))
        }

        /**
         * The quiet hours valid on [day]: those of the last entry from [day] or earlier (the history is in order);
         * the server's diary.quiet_on.
         */
        fun on(history: List<QuietFrom>, day: LocalDate): Quiet? = history.lastOrNull { !it.from.isAfter(day) }?.quiet

        /**
         * [history] with a change to [quiet] valid from [from], by the server's rule (diary.set_quiet): no entry when
         * the last one has the same quiet hours, and an entry of the same day is replaced.
         */
        fun with(history: List<QuietFrom>, from: LocalDate, quiet: Quiet?): List<QuietFrom> {
            if (history.lastOrNull()?.quiet == quiet) return history
            return history.filterNot { it.from == from } + QuietFrom(from, quiet)
        }
    }
}

/** A repeatable part of a diary study (server: Survey.sampling["modules"]). */
data class Module(
    val key: String,
    val name: String,
    val trigger: Trigger,
    /** minutes until a prompt expires; 0 = never */
    val expires: Int,
    /** active from day [dayFrom] to day [dayTo] of the study period (1-based, both included) */
    val dayFrom: Int,
    val dayTo: Int,
    /** the text of the notification; blank = the default text */
    val notify: String,
    /** minutes after the prompt for a second notification if it was not opened; 0 = none (prompted modules only) */
    val reminder: Int = 0,
    /** the module can be answered without a connection: the app keeps its page (OfflinePages) */
    val offline: Boolean = false,
)

/** One enrolment of this device in a diary study. [secret] is the only key to it; the server keeps its hash. */
data class Study(
    val code: String,
    val secret: String,
    val title: String,
    /** pending (first questionnaire not done) | active | finished | left | ended */
    val status: String,
    val firstDay: LocalDate?,
    val lastDay: LocalDate?,
    val modules: List<Module>,
    /**
     * Epoch milliseconds of the first 401 in a row from the server (secret unknown), null while the server knows
     * the secret. Kept only on the device; see [unauthorised].
     */
    val unauthorisedSince: Long? = null,
    /** The participant left while the server could not be reached: the app tells it at the next sync. */
    val leavePending: Boolean = false,
    /** The person's seed of the random prompts (from the server, below 2^53); 0 until the first sync. */
    val seed: Long = 0L,
    /** The quiet hours as the server keeps them: each entry valid from its day on (ordered). */
    val quiet: List<QuietFrom> = emptyList(),
    /**
     * Quiet hours the participant set that the server has not confirmed yet (no connection); sent with the next
     * sync. Until then the app takes them as valid from [QuietFrom.from], the day after they were set.
     */
    val quietPending: QuietFrom? = null,
) {
    val running get() = status == "active"

    /** The quiet history the schedule uses: the server's, with a change not yet confirmed added by its rule. */
    val quietHistory: List<QuietFrom>
        get() = quietPending?.let { QuietFrom.with(quiet, it.from, it.quiet) } ?: quiet

    /** The quiet hours valid on [day]. */
    fun quietOn(day: LocalDate): Quiet? = QuietFrom.on(quietHistory, day)

    /** Whether a module of this study prompts at random times (the quiet hours matter only for those). */
    val hasRandom: Boolean get() = modules.any { it.trigger is Trigger.Random }

    /**
     * The study after a 401 at [now]. One 401 does not end a study: a server restored from a backup or a passing
     * fault must not cost a participant the rest of the study. Only when the server keeps answering 401 for more
     * than [UNAUTHORISED_GRACE_MS] (the researchers deleted the study) is it marked "ended".
     */
    fun unauthorised(now: Long): Study {
        val since = unauthorisedSince ?: now
        return copy(unauthorisedSince = since, status = if (now - since > UNAUTHORISED_GRACE_MS) "ended" else status)
    }

    fun dayIndex(date: LocalDate): Int? =
        firstDay?.let { (date.toEpochDay() - it.toEpochDay()).toInt() + 1 }

    val periodDays: Int? get() = if (firstDay != null && lastDay != null) dayIndex(lastDay) else null

    fun toJson(): JSONObject = JSONObject()
        .put("code", code).put("secret", secret).put("title", title).put("status", status)
        .put("first_day", firstDay?.toString() ?: JSONObject.NULL)
        .put("last_day", lastDay?.toString() ?: JSONObject.NULL)
        .put("modules", JSONArray(modules.map { moduleJson(it) }))
        .put("unauthorised_since", unauthorisedSince ?: JSONObject.NULL)
        .put("leave_pending", leavePending)
        .put("seed", seed)
        .put("quiet", JSONArray(quiet.map { it.toJson() }))
        .put("quiet_pending", quietPending?.toJson() ?: JSONObject.NULL)

    companion object {
        /** How long the server may answer 401 before the app takes the study as deleted: three days. */
        const val UNAUTHORISED_GRACE_MS = 3L * 24 * 3600 * 1000

        /**
         * Reads the study as the server sends it (POST /api/diary/v1/study) or as stored on the device; the server
         * sends neither "unauthorised_since", "leave_pending" nor "quiet_pending", so a fresh copy starts without them.
         */
        fun fromJson(o: JSONObject, secret: String = o.optString("secret")): Study = Study(
            code = o.getString("code"),
            secret = secret,
            title = o.optString("title"),
            status = o.optString("status", "pending"),
            firstDay = o.optStringOrNull("first_day")?.let(LocalDate::parse),
            lastDay = o.optStringOrNull("last_day")?.let(LocalDate::parse),
            modules = o.optJSONArray("modules")?.let { arr -> (0 until arr.length()).map { module(arr.getJSONObject(it)) } }
                ?: emptyList(),
            unauthorisedSince = if (o.isNull("unauthorised_since")) null else o.optLong("unauthorised_since"),
            leavePending = o.optBoolean("leave_pending", false),
            seed = o.optLong("seed", 0L),
            quiet = o.optJSONArray("quiet")?.let { arr ->
                (0 until arr.length()).mapNotNull { k -> arr.optJSONObject(k)?.let(QuietFrom::fromJson) }
            } ?: emptyList(),
            quietPending = o.optJSONObject("quiet_pending")?.let(QuietFrom::fromJson),
        )

        private fun module(o: JSONObject): Module {
            val t = o.getJSONObject("trigger")
            val weekdayList = t.optJSONArray("weekdays")
            val weekdays = weekdayList?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: (1..7).toSet()
            val trigger = when (t.optString("t")) {
                "self" -> Trigger.Self(t.optString("label"), t.optInt("max_per_day", 0))
                "random" -> Trigger.Random(
                    perDay = t.optInt("per_day", 0),
                    windows = t.optJSONArray("windows")?.let { a ->
                        (0 until a.length()).mapNotNull { k ->
                            val w = a.optJSONArray(k) ?: return@mapNotNull null
                            val start = Quiet.hhmm(w.optString(0)) ?: return@mapNotNull null
                            val end = Quiet.hhmm(w.optString(1)) ?: return@mapNotNull null
                            start to end
                        }
                    } ?: emptyList(),
                    minGap = t.optInt("min_gap", 0).coerceAtLeast(0),
                    weekdays = weekdays,
                )
                else -> {
                    val times = t.optJSONArray("times")
                    Trigger.Fixed(
                        times = times?.let { a -> (0 until a.length()).map { LocalTime.parse(a.getString(it)) } } ?: emptyList(),
                        weekdays = weekdays,
                    )
                }
            }
            val days = o.optJSONArray("days")
            return Module(
                key = o.getString("key"),
                name = o.optString("name"),
                trigger = trigger,
                expires = o.optInt("expires", 0),
                dayFrom = days?.optInt(0, 1) ?: 1,
                dayTo = days?.optInt(1, Int.MAX_VALUE) ?: Int.MAX_VALUE,
                notify = o.optString("notify"),
                reminder = if (trigger.prompted) o.optInt("reminder", 0).coerceAtLeast(0) else 0,
                offline = o.optBoolean("offline", false),
            )
        }

        private fun moduleJson(m: Module): JSONObject {
            val t = when (val tr = m.trigger) {
                is Trigger.Fixed -> JSONObject().put("t", "fixed")
                    .put("times", JSONArray(tr.times.map { it.toString() }))
                    .put("weekdays", JSONArray(tr.weekdays.sorted()))
                is Trigger.Random -> JSONObject().put("t", "random").put("per_day", tr.perDay)
                    .put("windows", JSONArray(tr.windows.map { JSONArray(listOf(it.first, it.second)) }))
                    .put("min_gap", tr.minGap).put("weekdays", JSONArray(tr.weekdays.sorted()))
                is Trigger.Self -> JSONObject().put("t", "self").put("label", tr.label).put("max_per_day", tr.maxPerDay)
            }
            return JSONObject().put("key", m.key).put("name", m.name).put("trigger", t).put("expires", m.expires)
                .put("days", JSONArray(listOf(m.dayFrom, m.dayTo))).put("notify", m.notify)
                .put("reminder", m.reminder).put("offline", m.offline)
        }
    }
}

fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name) || !has(name)) null else optString(name).ifBlank { null }
