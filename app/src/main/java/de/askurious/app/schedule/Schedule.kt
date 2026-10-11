package de.askurious.app.schedule

import de.askurious.app.data.Module
import de.askurious.app.data.Study
import de.askurious.app.data.Trigger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * One prompt: [module] of [study] is due at [due] (epoch milliseconds). [reminder]: minutes after [due] for a second
 * notification when the prompt was not opened; 0 = none.
 */
data class Prompt(val study: String, val module: String, val due: Long, val expires: Int, val reminder: Int = 0) {
    /** A stable number for the alarm and the notification of this prompt; the reminder shares it, so it replaces it. */
    val id: Int get() = "$study|$module|$due".hashCode()

    /** When the reminder of this prompt rings (epoch milliseconds), null when the module has none. */
    val remindAt: Long? get() = if (reminder > 0) due + reminder * 60_000L else null
}

/**
 * The prompts of the fixed and random modules, computed on the device in local time (so a change of time zone or
 * daylight saving time moves them with the person). The server computes the same list from the same definition for
 * the export and to check that a prompt was due (app/diary.py, prompts()). A random module draws its times per day
 * from the person's seed and the quiet hours valid on that day (RandomTimes, Study.quietOn).
 */
object Schedule {
    /** The prompts of [study] with a due time in [from, until), in zone [zone], ordered by time. */
    fun prompts(study: Study, from: Instant, until: Instant, zone: ZoneId = ZoneId.systemDefault()): List<Prompt> {
        if (!study.running) return emptyList()
        val first = study.firstDay ?: return emptyList()
        val last = study.lastDay ?: return emptyList()
        val out = mutableListOf<Prompt>()
        var date: LocalDate = maxOf(first, from.atZone(zone).toLocalDate())
        val end = minOf(last, until.atZone(zone).toLocalDate())
        while (!date.isAfter(end)) {
            val day = study.dayIndex(date)!!
            for (m in study.modules) {
                if (day < m.dayFrom || day > m.dayTo) continue
                val times = when (val t = m.trigger) {
                    is Trigger.Fixed -> if (date.dayOfWeek.value in t.weekdays) t.times else continue
                    is Trigger.Random ->
                        if (date.dayOfWeek.value in t.weekdays) RandomTimes.times(study.seed, m.key, date, t, study.quietOn(date)) else continue
                    is Trigger.Self -> continue
                }
                for (time in times) {
                    val due = ZonedDateTime.of(date, time, zone).toInstant()
                    if (!due.isBefore(from) && due.isBefore(until)) out += Prompt(study.code, m.key, due.toEpochMilli(), m.expires, m.reminder)
                }
            }
            date = date.plusDays(1)
        }
        return out.sortedBy { it.due }
    }

    /** The next prompts after [now] over all studies: all those due at the earliest due time (they share one alarm). */
    fun next(studies: List<Study>, now: Instant, zone: ZoneId = ZoneId.systemDefault()): List<Prompt> {
        val until = now.plusSeconds(8L * 24 * 3600)
        val all = studies.flatMap { prompts(it, now.plusMillis(1), until, zone) }
        val first = all.minOfOrNull { it.due } ?: return emptyList()
        return all.filter { it.due == first }
    }

    /**
     * The prompts of [study] whose reminder rings in [from, until), ordered by that moment. A reminder belongs to a
     * prompt up to [Module.reminder] minutes earlier, so the prompts are looked for that much before [from].
     */
    fun reminders(study: Study, from: Instant, until: Instant, zone: ZoneId = ZoneId.systemDefault()): List<Prompt> {
        val longest = study.modules.filter { it.trigger.prompted }.maxOfOrNull { it.reminder } ?: 0
        if (longest <= 0) return emptyList()
        val lo = from.toEpochMilli()
        val hi = until.toEpochMilli()
        return prompts(study, from.minusMillis(longest * 60_000L), until, zone)
            .filter { p -> p.remindAt.let { it != null && it >= lo && it < hi } }
            .sortedBy { it.remindAt }
    }

    /**
     * The next moment after [now] at which the one alarm must ring: the earliest prompt or reminder over all
     * studies; null when there is none in the next eight days. Whether a reminder is still needed (prompt opened,
     * expired) is decided when it rings, so this stays a function of the schedule alone.
     */
    fun nextMoment(studies: List<Study>, now: Instant, zone: ZoneId = ZoneId.systemDefault()): Long? {
        val prompt = next(studies, now, zone).firstOrNull()?.due
        val until = now.plusSeconds(8L * 24 * 3600)
        val reminder = studies.flatMap { reminders(it, now.plusMillis(1), until, zone) }.mapNotNull { it.remindAt }.minOrNull()
        return listOfNotNull(prompt, reminder).minOrNull()
    }

    /** Whether a prompt due at [due] for [module] may still be answered at [now]. */
    fun open(module: Module, due: Long, now: Long): Boolean = module.expires <= 0 || now < due + module.expires * 60_000L
}
