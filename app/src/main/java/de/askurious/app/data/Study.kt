package de.askurious.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalTime

/** When a module is due: at fixed times on some weekdays, or whenever the participant wants to report. */
sealed class Trigger {
    /** [weekdays]: ISO numbers, 1 = Monday .. 7 = Sunday. */
    data class Fixed(val times: List<LocalTime>, val weekdays: Set<Int>) : Trigger()
    data class Self(val label: String, val maxPerDay: Int) : Trigger()
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
    /** minutes after the prompt for a second notification if it was not opened; 0 = none (fixed modules only) */
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
) {
    val running get() = status == "active"

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

    companion object {
        /** How long the server may answer 401 before the app takes the study as deleted: three days. */
        const val UNAUTHORISED_GRACE_MS = 3L * 24 * 3600 * 1000

        /**
         * Reads the study as the server sends it (POST /api/diary/v1/study) or as stored on the device; the server
         * sends neither "unauthorised_since" nor "leave_pending", so a fresh copy starts without them.
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
        )

        private fun module(o: JSONObject): Module {
            val t = o.getJSONObject("trigger")
            val trigger = if (t.optString("t") == "self") {
                Trigger.Self(t.optString("label"), t.optInt("max_per_day", 0))
            } else {
                val times = t.optJSONArray("times")
                val days = t.optJSONArray("weekdays")
                Trigger.Fixed(
                    times = times?.let { a -> (0 until a.length()).map { LocalTime.parse(a.getString(it)) } } ?: emptyList(),
                    weekdays = days?.let { a -> (0 until a.length()).map { a.getInt(it) }.toSet() } ?: (1..7).toSet(),
                )
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
                reminder = if (trigger is Trigger.Fixed) o.optInt("reminder", 0).coerceAtLeast(0) else 0,
                offline = o.optBoolean("offline", false),
            )
        }

        private fun moduleJson(m: Module): JSONObject {
            val t = when (val tr = m.trigger) {
                is Trigger.Fixed -> JSONObject().put("t", "fixed")
                    .put("times", JSONArray(tr.times.map { it.toString() }))
                    .put("weekdays", JSONArray(tr.weekdays.sorted()))
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
