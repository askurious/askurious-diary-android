package de.askurious.app.schedule

import de.askurious.app.data.Module
import de.askurious.app.data.Study
import de.askurious.app.data.Trigger
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** One prompt: [module] of [study] is due at [due] (epoch milliseconds). */
data class Prompt(val study: String, val module: String, val due: Long, val expires: Int) {
    /** A stable number for the alarm and the notification of this prompt. */
    val id: Int get() = "$study|$module|$due".hashCode()
}

/**
 * The prompts of the fixed modules, computed on the device in local time (so a change of time zone or daylight
 * saving time moves them with the person). The server computes the same list from the same definition for the
 * export (app/diary.py, prompts()).
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
                val t = m.trigger as? Trigger.Fixed ?: continue
                if (day < m.dayFrom || day > m.dayTo || date.dayOfWeek.value !in t.weekdays) continue
                for (time in t.times) {
                    val due = ZonedDateTime.of(date, time, zone).toInstant()
                    if (!due.isBefore(from) && due.isBefore(until)) out += Prompt(study.code, m.key, due.toEpochMilli(), m.expires)
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

    /** Whether a prompt due at [due] for [module] may still be answered at [now]. */
    fun open(module: Module, due: Long, now: Long): Boolean = module.expires <= 0 || now < due + module.expires * 60_000L
}
