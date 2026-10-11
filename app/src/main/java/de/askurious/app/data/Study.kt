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
) {
    val running get() = status == "active"

    fun dayIndex(date: LocalDate): Int? =
        firstDay?.let { (date.toEpochDay() - it.toEpochDay()).toInt() + 1 }

    val periodDays: Int? get() = if (firstDay != null && lastDay != null) dayIndex(lastDay) else null

    fun toJson(): JSONObject = JSONObject()
        .put("code", code).put("secret", secret).put("title", title).put("status", status)
        .put("first_day", firstDay?.toString() ?: JSONObject.NULL)
        .put("last_day", lastDay?.toString() ?: JSONObject.NULL)
        .put("modules", JSONArray(modules.map { moduleJson(it) }))

    companion object {
        /** Reads the study as the server sends it (POST /api/diary/v1/study) or as stored on the device. */
        fun fromJson(o: JSONObject, secret: String = o.optString("secret")): Study = Study(
            code = o.getString("code"),
            secret = secret,
            title = o.optString("title"),
            status = o.optString("status", "pending"),
            firstDay = o.optStringOrNull("first_day")?.let(LocalDate::parse),
            lastDay = o.optStringOrNull("last_day")?.let(LocalDate::parse),
            modules = o.optJSONArray("modules")?.let { arr -> (0 until arr.length()).map { module(arr.getJSONObject(it)) } }
                ?: emptyList(),
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
        }
    }
}

fun JSONObject.optStringOrNull(name: String): String? =
    if (isNull(name) || !has(name)) null else optString(name).ifBlank { null }
