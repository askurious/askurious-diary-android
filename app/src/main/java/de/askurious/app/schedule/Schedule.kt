package de.askurious.app.schedule

import de.askurious.app.data.Clock
import de.askurious.app.data.Module
import de.askurious.app.data.Study
import de.askurious.app.data.Trigger
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * One prompt: [module] of [study] is due at [due] (epoch milliseconds, in the study's time: virtual for a fast-forward
 * person, [clock]). [reminder]: minutes after [due] for a second notification when the prompt was not opened; 0 = none.
 */
data class Prompt(
    val study: String, val module: String, val due: Long, val expires: Int, val reminder: Int = 0, val clock: Clock? = null,
) {
    /** A stable number for the alarm and the notification of this prompt; the reminder shares it, so it replaces it. */
    val id: Int get() = "$study|$module|$due".hashCode()

    /** When the reminder of this prompt is due (epoch milliseconds, study's time), null when the module has none. */
    val remindAt: Long? get() = if (reminder > 0) due + reminder * 60_000L else null

    /** The real moment the prompt rings. */
    val ring: Long get() = clock?.real(due) ?: due

    /** The real moment the reminder rings, null when the module has none. */
    val remindRing: Long? get() = remindAt?.let { clock?.real(it) ?: it }
}

/**
 * The prompts of the fixed and random modules, computed in the server's per-day time zone history.
 * A newly reported zone applies from the following study day; daylight saving follows that zone's rules. The server computes the same list from the same definition for
 * the export and to check that a prompt was due (app/diary.py, prompts()). A random module draws its times per day
 * from the person's seed and the quiet hours valid on that day (RandomTimes, Study.quietOn). No prompt on the days of a
 * pause the researchers set (Study.pausedOn), and none while the person is paused (status "paused"). None due before
 * [Study.promptsFrom]: the server does not count the prompts of the first day before the entry part was completed.
 *
 * [prompts] and [reminders] take and give the study's time (virtual for a fast-forward person, Study.clock); [next],
 * [nextMoment] and [exitDue] take the real now and map it per study.
 */
object Schedule {
    /** The prompts of [study] with a due time in [from, until) (study's time), in zone [zone], ordered by time. */
    fun prompts(study: Study, from: Instant, until: Instant, zone: ZoneId = ZoneId.systemDefault()): List<Prompt> {
        if (!study.running) return emptyList()
        val first = study.firstDay ?: return emptyList()
        // an open-ended study has no last day: its prompts go on until the researchers end it
        val last = study.lastDay ?: if (study.openEnd) LocalDate.MAX else return emptyList()
        val out = mutableListOf<Prompt>()
        var date: LocalDate = maxOf(first, from.atZone(study.zoneAt(from, zone)).toLocalDate().minusDays(2))
        val end = minOf(last, until.atZone(study.zoneAt(until, zone)).toLocalDate().plusDays(2))
        while (!date.isAfter(end)) {
            if (study.pausedOn(date)) {
                date = date.plusDays(1)
                continue
            }
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
                    val due = ZonedDateTime.of(date, time, study.zoneOn(date, zone)).toInstant()
                    // not before the moment from which the server counts this person's prompts (Study.promptsFrom)
                    if (study.promptsFrom != null && due.toEpochMilli() < study.promptsFrom) continue
                    if (!due.isBefore(from) && due.isBefore(until)) out += Prompt(study.code, m.key, due.toEpochMilli(), m.expires, m.reminder, study.clock)
                }
            }
            date = date.plusDays(1)
        }
        return out.sortedBy { it.due }
    }

    /**
     * The next prompts after the real [now] over all studies (each looked for in its own time, eight study days
     * ahead): all those that ring at the earliest real moment (they share one alarm).
     */
    fun next(studies: List<Study>, now: Instant, zone: ZoneId = ZoneId.systemDefault()): List<Prompt> {
        val all = studies.flatMap { s ->
            val v = s.at(now)
            prompts(s, v.plusMillis(1), v.plusSeconds(LOOK_AHEAD_S), zone)
        }
        val first = all.minOfOrNull { it.ring } ?: return emptyList()
        return all.filter { it.ring == first }
    }

    /** Only today's unanswered, still valid prompts; opened does not mean answered. */
    fun available(study: Study, now: Instant, zone: ZoneId = ZoneId.systemDefault()): List<Prompt> {
        val v = study.at(now)
        val effectiveZone = study.zoneAt(v, zone)
        val start = v.atZone(effectiveZone).toLocalDate().atStartOfDay(effectiveZone).toInstant()
        return prompts(study, start.minusSeconds(24 * 3600), v.plusMillis(1), zone).filter { p ->
            val m = study.modules.first { it.key == p.module }
            (m.expires > 0 || p.due >= start.toEpochMilli()) && open(m, p.due, v.toEpochMilli()) && "${p.module}|${p.due}" !in study.completedPrompts
        }
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
     * The next real moment after [now] at which the one alarm must ring: the earliest prompt, reminder or exit
     * notification over all studies; null when there is none in the next eight study days. Whether a reminder is
     * still needed (prompt opened, expired) and whether the exit questionnaire is open is decided when it rings, so
     * this stays a function of the schedule alone. [exitNotified]: codes of the studies whose exit notification was
     * posted (or whose exit questionnaire was opened) already.
     */
    fun nextMoment(
        studies: List<Study>, now: Instant, zone: ZoneId = ZoneId.systemDefault(), exitNotified: Set<String> = emptySet(),
    ): Long? {
        val prompt = next(studies, now, zone).firstOrNull()?.ring
        val reminder = studies.flatMap { s ->
            val v = s.at(now)
            reminders(s, v.plusMillis(1), v.plusSeconds(LOOK_AHEAD_S), zone)
        }.mapNotNull { it.remindRing }.minOrNull()
        val exit = studies.filter { it.code !in exitNotified }
            .mapNotNull { s -> exitAt(s, zone)?.let { s.realAt(it) } }
            .filter { it > now.toEpochMilli() }
            .minOrNull()
        return listOfNotNull(prompt, reminder, exit).minOrNull()
    }

    /**
     * When the exit questionnaire of [study] is notified (epoch milliseconds, study's time): at [EXIT_TIME] on the day
     * after the last day; null without an exit questionnaire or study period, or when the person left or never
     * finished the entry part.
     */
    fun exitAt(study: Study, zone: ZoneId = ZoneId.systemDefault()): Long? {
        if (study.exit == null || study.status == "left" || study.status == "pending") return null
        val last = study.lastDay ?: return null
        return ZonedDateTime.of(last.plusDays(1), EXIT_TIME, study.zoneOn(last.plusDays(1), zone)).toInstant().toEpochMilli()
    }

    /**
     * Whether the exit notification of [study] is to be posted at the real [now]: the exit questionnaire is open,
     * its moment ([exitAt]) has passed and it was not notified yet ([notified]). One that was missed (phone off, no
     * sync yet) comes at the next chance.
     */
    fun exitDue(study: Study, now: Long, notified: Boolean, zone: ZoneId = ZoneId.systemDefault()): Boolean {
        if (notified || study.exit?.open != true) return false
        val at = exitAt(study, zone) ?: return false
        return study.now(now) >= at
    }

    /** How far [next] and [nextMoment] look ahead: eight days of the study's time. */
    private const val LOOK_AHEAD_S = 8L * 24 * 3600

    /** The local time of the exit notification on the day after the last day. */
    val EXIT_TIME: LocalTime = LocalTime.of(10, 0)

    /** Whether a prompt due at [due] for [module] may still be answered at [now] (both study's time). */
    fun open(module: Module, due: Long, now: Long): Boolean = module.expires <= 0 || now < due + module.expires * 60_000L
}
