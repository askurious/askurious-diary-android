package de.askurious.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

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

/**
 * The exit questionnaire of a study (server: "exit" of POST /study). [open]: it can be answered now; the server sets
 * it after the person's study period ended (finished or ended) until [until], and only while it is not answered.
 */
data class Exit(val name: String, val open: Boolean, val until: LocalDate?) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("open", open).put("until", until?.toString() ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject?): Exit? = o?.let {
            Exit(it.optString("name"), it.optBoolean("open", false), it.optStringOrNull("until")?.let { d -> runCatching { LocalDate.parse(d) }.getOrNull() })
        }
    }
}

/** One enrolment of this device in a diary study. [secret] is the only key to it; the server keeps its hash. */
data class Study(
    val code: String,
    val secret: String,
    val title: String,
    /**
     * pending (first questionnaire not done) | active | paused (by the researchers) | finished | left | ended (by the
     * researchers, also early for this person)
     */
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
    /**
     * The pauses the researchers set for this person: from and to (local dates, both included; to null = still
     * paused). No prompt on these days, also after the person is active again.
     */
    val pauses: List<Pair<LocalDate, LocalDate?>> = emptyList(),
    /** The exit questionnaire, null when the study has none. */
    val exit: Exit? = null,
    /** The clock of a fast-forward test person, null for real time. */
    val clock: Clock? = null,
    /**
     * The server of the study ("https://ask.askurious.de"): the one its join link named, else [Api.defaultServer]. Kept
     * only on the device; every call for the study goes there.
     */
    val server: String = Api.defaultServer,
    /** The colours of the study's pages for the app's screens around them; null: the app's own look. */
    val look: Look? = null,
) {
    val running get() = status == "active"

    /** The study's time (epoch milliseconds) at the real moment [real]: virtual for a fast-forward person. */
    fun now(real: Long = System.currentTimeMillis()): Long = clock?.virtual(real) ?: real

    /** The study's time at the real [real] as an Instant. */
    fun at(real: Instant): Instant = if (clock == null) real else Instant.ofEpochMilli(clock.virtual(real.toEpochMilli()))

    /** The real moment at which the study's time reaches [time] (epoch milliseconds). */
    fun realAt(time: Long): Long = clock?.real(time) ?: time

    /** The study's date today, in [zone]. */
    fun today(zone: ZoneId = ZoneId.systemDefault(), real: Long = System.currentTimeMillis()): LocalDate =
        Instant.ofEpochMilli(now(real)).atZone(zone).toLocalDate()

    /** Whether [day] lies in a pause of the researchers: no prompts on it. */
    fun pausedOn(day: LocalDate): Boolean = pauses.any { (from, to) -> !day.isBefore(from) && (to == null || !day.isAfter(to)) }

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
        .put("pauses", JSONArray(pauses.map { (from, to) -> JSONArray(listOf(from.toString(), to?.toString() ?: JSONObject.NULL)) }))
        .put("exit", exit?.toJson() ?: JSONObject.NULL)
        .put("clock", clock?.toJson() ?: JSONObject.NULL)
        .put("server", server)
        .put("look", look?.toJson() ?: JSONObject.NULL)

    companion object {
        /** How long the server may answer 401 before the app takes the study as deleted: three days. */
        const val UNAUTHORISED_GRACE_MS = 3L * 24 * 3600 * 1000

        /**
         * Reads the study as the server sends it (POST /api/diary/v1/study) or as stored on the device; the server
         * sends neither "unauthorised_since", "leave_pending" nor "quiet_pending", so a fresh copy starts without them;
         * "pauses", "exit" and "clock" have the same shape in both. Defensive: a date that is no date counts as none,
         * and a module that cannot be read is left out (the server may send something newer than this app knows); only
         * a missing "code" throws. The server sends no "server" either: [server] names it (the study's own, kept on the
         * phone), else the stored one counts, and a study stored before there was one has [Api.defaultServer].
         */
        fun fromJson(o: JSONObject, secret: String = o.optString("secret"), server: String? = null): Study = Study(
            code = o.getString("code"),
            secret = secret,
            title = o.optString("title"),
            status = o.optString("status", "pending"),
            firstDay = o.optStringOrNull("first_day")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            lastDay = o.optStringOrNull("last_day")?.let { runCatching { LocalDate.parse(it) }.getOrNull() },
            modules = o.optJSONArray("modules")?.let { arr ->
                (0 until arr.length()).mapNotNull { k -> runCatching { module(arr.getJSONObject(k)) }.getOrNull() }
            } ?: emptyList(),
            unauthorisedSince = if (o.isNull("unauthorised_since")) null else o.optLong("unauthorised_since"),
            leavePending = o.optBoolean("leave_pending", false),
            seed = o.optLong("seed", 0L),
            quiet = o.optJSONArray("quiet")?.let { arr ->
                (0 until arr.length()).mapNotNull { k -> arr.optJSONObject(k)?.let(QuietFrom::fromJson) }
            } ?: emptyList(),
            quietPending = o.optJSONObject("quiet_pending")?.let(QuietFrom::fromJson),
            pauses = o.optJSONArray("pauses")?.let { arr -> (0 until arr.length()).mapNotNull { k -> pause(arr.optJSONArray(k)) } }
                ?: emptyList(),
            exit = Exit.fromJson(o.optJSONObject("exit")),
            clock = Clock.fromJson(o.optJSONObject("clock")),
            server = server ?: o.optStringOrNull("server") ?: Api.defaultServer,
            look = runCatching { Look.fromJson(o.optJSONObject("look")) }.getOrNull(),
        )

        /** ["YYYY-MM-DD", "YYYY-MM-DD" or null]; anything else is no pause. */
        private fun pause(a: JSONArray?): Pair<LocalDate, LocalDate?>? {
            if (a == null || a.length() < 1) return null
            val from = runCatching { LocalDate.parse(a.getString(0)) }.getOrNull() ?: return null
            val to = if (a.length() < 2 || a.isNull(1)) null else runCatching { LocalDate.parse(a.getString(1)) }.getOrNull() ?: return null
            return from to to
        }

        private fun module(o: JSONObject): Module {
            val t = o.getJSONObject("trigger")
            val weekdayList = t.optJSONArray("weekdays")
            val weekdays = weekdayList?.let { a -> (0 until a.length()).mapNotNull { k -> a.optInt(k, 0).takeIf { it in 1..7 } }.toSet() }
                ?: (1..7).toSet()
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
                        times = times?.let { a ->
                            (0 until a.length()).mapNotNull { k -> runCatching { LocalTime.parse(a.getString(k)) }.getOrNull() }
                        } ?: emptyList(),
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
