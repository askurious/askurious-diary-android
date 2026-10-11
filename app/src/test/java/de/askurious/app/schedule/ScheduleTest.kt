package de.askurious.app.schedule

import de.askurious.app.data.Module
import de.askurious.app.data.Quiet
import de.askurious.app.data.QuietFrom
import de.askurious.app.data.Study
import de.askurious.app.data.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** The same cases as the server's tests/test_diary.py (prompts()): the phone and the export must agree. */
class ScheduleTest {
    private val berlin = ZoneId.of("Europe/Berlin")

    private fun study(vararg modules: Module) = Study(
        code = "abc", secret = "s", title = "T", status = "active",
        firstDay = LocalDate.of(2026, 10, 24), lastDay = LocalDate.of(2026, 10, 26), modules = modules.toList(),
    )

    private fun fixed(key: String, vararg times: String, weekdays: Set<Int> = (1..7).toSet(), from: Int = 1, reminder: Int = 0) = Module(
        key, key, Trigger.Fixed(times.map(LocalTime::parse), weekdays), expires = 60, dayFrom = from, dayTo = Int.MAX_VALUE, notify = "",
        reminder = reminder,
    )

    @Test
    fun promptsFollowLocalTimeAcrossTheChangeOfTheClocks() {
        val s = study(fixed("m1", "09:00", from = 2))
        val due = Schedule.prompts(s, Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"), berlin)
        assertEquals(listOf("2026-10-25T08:00:00Z", "2026-10-26T08:00:00Z"), due.map { Instant.ofEpochMilli(it.due).toString() })
    }

    @Test
    fun weekdaysAndSelfModulesAreRespected() {
        // 24 Oct 2026 is a Saturday (6), 25 a Sunday (7), 26 a Monday (1)
        val self = Module("m2", "m2", Trigger.Self("Report", 3), 0, 1, Int.MAX_VALUE, "")
        val s = study(fixed("m1", "10:00", "18:00", weekdays = setOf(1)), self)
        val due = Schedule.prompts(s, Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"), berlin)
        assertEquals(listOf("2026-10-26T09:00:00Z", "2026-10-26T17:00:00Z"), due.map { Instant.ofEpochMilli(it.due).toString() })
    }

    @Test
    fun nextGivesAllPromptsOfTheEarliestMomentAndNothingForEndedStudies() {
        val a = study(fixed("m1", "09:00"))
        val b = a.copy(code = "def")
        val next = Schedule.next(listOf(a, b, a.copy(code = "gone", status = "left")), Instant.parse("2026-10-24T08:00:00Z"), berlin)
        assertEquals(setOf("abc", "def"), next.map { it.study }.toSet())
        assertEquals(Instant.parse("2026-10-25T08:00:00Z").toEpochMilli(), next.first().due)
    }

    @Test
    fun remindersRingTheirMinutesAfterThePromptAndKeepItsId() {
        val s = study(fixed("m1", "09:00", reminder = 20), fixed("m2", "12:00"))
        val r = Schedule.reminders(s, Instant.parse("2026-10-24T00:00:00Z"), Instant.parse("2026-10-25T00:00:00Z"), berlin)
        // 24 Oct 2026 09:00 in Berlin is 07:00Z; only m1 has a reminder
        assertEquals(listOf("2026-10-24T07:20:00Z"), r.map { Instant.ofEpochMilli(it.remindAt!!).toString() })
        val prompt = Schedule.prompts(s, Instant.parse("2026-10-24T00:00:00Z"), Instant.parse("2026-10-25T00:00:00Z"), berlin).first()
        assertEquals(prompt.id, r.first().id)
    }

    @Test
    fun aReminderOfAPromptBeforeTheWindowIsFound() {
        // the prompt at 07:00Z lies before the window, its reminder at 07:20Z inside it
        val s = study(fixed("m1", "09:00", reminder = 20))
        val r = Schedule.reminders(s, Instant.parse("2026-10-24T07:10:00Z"), Instant.parse("2026-10-24T08:00:00Z"), berlin)
        assertEquals(1, r.size)
        assertEquals(Instant.parse("2026-10-24T07:00:00Z").toEpochMilli(), r.first().due)
    }

    private fun random(key: String, weekdays: Set<Int> = (1..7).toSet(), from: Int = 1, reminder: Int = 0) = Module(
        key, key, Trigger.Random(3, listOf("09:00" to "21:00"), 60, weekdays), expires = 60, dayFrom = from, dayTo = Int.MAX_VALUE,
        notify = "", reminder = reminder,
    )

    private fun local(p: Prompt) = Instant.ofEpochMilli(p.due).atZone(berlin)

    @Test
    fun randomModulesGivePromptsOnTheirDaysAtTheDrawnTimes() {
        // 24 Oct 2026 Saturday (6), 25 Sunday (7, the clocks change), 26 Monday (1); the module from day 2, not on Mondays
        val s = study(random("m1", weekdays = (1..7).toSet() - 1, from = 2)).copy(seed = 1234567890123L)
        val due = Schedule.prompts(s, Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"), berlin)
        assertEquals(3, due.size)
        assertEquals(setOf(LocalDate.of(2026, 10, 25)), due.map { local(it).toLocalDate() }.toSet())
        val t = s.modules.single().trigger as Trigger.Random
        assertEquals(RandomTimes.times(s.seed, "m1", LocalDate.of(2026, 10, 25), t, null), due.map { local(it).toLocalTime() })
        assertEquals(60, due.first().expires)
    }

    @Test
    fun randomPromptsFollowTheQuietHoursOfEachDay() {
        val quiet = Quiet("09:00", "15:00")
        val s = study(random("m1")).copy(seed = 42L, quiet = listOf(QuietFrom(LocalDate.of(2026, 10, 25), quiet)))
        val due = Schedule.prompts(s, Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"), berlin)
        val t = s.modules.single().trigger as Trigger.Random
        for (d in listOf(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 25), LocalDate.of(2026, 10, 26))) {
            val q = if (d.isBefore(LocalDate.of(2026, 10, 25))) null else quiet
            assertEquals(RandomTimes.times(42L, "m1", d, t, q), due.filter { local(it).toLocalDate() == d }.map { local(it).toLocalTime() })
        }
        // from the 25th nothing before 15:00
        assertTrue(due.filter { local(it).toLocalDate() >= LocalDate.of(2026, 10, 25) }.all { local(it).hour >= 15 })
        // a change not yet confirmed counts from its day too
        val pending = s.copy(quietPending = QuietFrom(LocalDate.of(2026, 10, 26), null))
        val after = Schedule.prompts(pending, Instant.parse("2026-10-26T00:00:00Z"), Instant.parse("2026-10-27T00:00:00Z"), berlin)
        assertEquals(RandomTimes.times(42L, "m1", LocalDate.of(2026, 10, 26), t, null), after.map { local(it).toLocalTime() })
    }

    @Test
    fun randomModulesHaveReminders() {
        val s = study(random("m1", reminder = 15)).copy(seed = 7L)
        val day = Schedule.prompts(s, Instant.parse("2026-10-24T00:00:00Z"), Instant.parse("2026-10-25T00:00:00Z"), berlin)
        val r = Schedule.reminders(s, Instant.parse("2026-10-24T00:00:00Z"), Instant.parse("2026-10-25T00:00:00Z"), berlin)
        assertEquals(day.map { it.due + 15 * 60_000L }, r.map { it.remindAt })
    }

    @Test
    fun nextMomentIsTheEarlierOfPromptAndReminder() {
        val s = study(fixed("m1", "09:00", "18:00", reminder = 30))
        // after the 09:00 prompt (07:00Z): its reminder at 07:30Z comes before the 18:00 prompt
        assertEquals(Instant.parse("2026-10-24T07:30:00Z").toEpochMilli(), Schedule.nextMoment(listOf(s), Instant.parse("2026-10-24T07:05:00Z"), berlin))
        // after that reminder: the next prompt
        assertEquals(Instant.parse("2026-10-24T16:00:00Z").toEpochMilli(), Schedule.nextMoment(listOf(s), Instant.parse("2026-10-24T07:30:00Z"), berlin))
        // without reminders it is the next prompt, as before
        val plain = study(fixed("m1", "09:00", "18:00"))
        assertEquals(Instant.parse("2026-10-24T16:00:00Z").toEpochMilli(), Schedule.nextMoment(listOf(plain), Instant.parse("2026-10-24T07:05:00Z"), berlin))
        assertNull(Schedule.nextMoment(listOf(s.copy(status = "left")), Instant.parse("2026-10-24T07:05:00Z"), berlin))
    }

    @Test
    fun noPromptsOnTheDaysOfAPauseAlsoAfterItEnded() {
        val s = study(fixed("m1", "09:00")).copy(pauses = listOf(LocalDate.of(2026, 10, 25) to LocalDate.of(2026, 10, 25)))
        val due = Schedule.prompts(s, Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"), berlin)
        assertEquals(listOf("2026-10-24T07:00:00Z", "2026-10-26T08:00:00Z"), due.map { Instant.ofEpochMilli(it.due).toString() })
        // a pause still running (no end) keeps every day from its start free, random modules too
        val open = study(fixed("m1", "09:00"), random("m2")).copy(seed = 3L, pauses = listOf(LocalDate.of(2026, 10, 25) to null))
        val left = Schedule.prompts(open, Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"), berlin)
        assertEquals(setOf(LocalDate.of(2026, 10, 24)), left.map { local(it).toLocalDate() }.toSet())
        // while the person is paused there are no prompts at all
        assertTrue(Schedule.prompts(s.copy(status = "paused"), Instant.parse("2026-10-01T00:00:00Z"), Instant.parse("2026-11-01T00:00:00Z"), berlin).isEmpty())
    }

    @Test
    fun pausesAreReadFromTheServer() {
        val o = study().toJson().put("pauses", org.json.JSONArray("[[\"2026-10-25\", \"2026-10-26\"], [\"2026-10-28\", null], [\"x\"]]"))
        val s = Study.fromJson(o)
        assertEquals(listOf(LocalDate.of(2026, 10, 25) to LocalDate.of(2026, 10, 26), LocalDate.of(2026, 10, 28) to null), s.pauses)
        assertTrue(s.pausedOn(LocalDate.of(2026, 10, 26)))
        assertTrue(!s.pausedOn(LocalDate.of(2026, 10, 27)))
        assertTrue(s.pausedOn(LocalDate.of(2027, 1, 1)))
    }

    @Test
    fun aFastForwardPersonGetsThePromptsOfVirtualTimeAtTheirRealMoments() {
        // t0 = 24 Oct 00:00 Berlin; at ×144 one study day is 10 real minutes
        val t0 = Instant.parse("2026-10-23T22:00:00Z").toEpochMilli()
        val clock = de.askurious.app.data.Clock(t0, 144)
        val s = study(fixed("m1", "09:00", reminder = 30)).copy(clock = clock)
        val next = Schedule.next(listOf(s), Instant.ofEpochMilli(t0), berlin).single()
        // due in virtual time (what goes to the server), rung in real time
        assertEquals(Instant.parse("2026-10-24T07:00:00Z").toEpochMilli(), next.due)
        assertEquals(t0 + 9L * 3600 * 1000 / 144, next.ring)
        assertEquals(next.ring, Schedule.nextMoment(listOf(s), Instant.ofEpochMilli(t0), berlin))
        // after the prompt: its reminder 30 virtual minutes later, 12.5 real seconds
        assertEquals(t0 + (9L * 3600 + 30 * 60) * 1000 / 144, Schedule.nextMoment(listOf(s), Instant.ofEpochMilli(next.ring), berlin))
        // after the reminder: day 2 at 09:00 (the clocks change on the 25th: 08:00Z)
        val later = Schedule.next(listOf(s), Instant.ofEpochMilli(t0 + (9L * 3600 + 30 * 60) * 1000 / 144), berlin).single()
        assertEquals(Instant.parse("2026-10-25T08:00:00Z").toEpochMilli(), later.due)
        assertEquals(clock.real(later.due), later.ring)
        // the same study without a clock is not moved
        assertEquals(next.due, Schedule.next(listOf(s.copy(clock = null)), Instant.ofEpochMilli(t0), berlin).single().ring)
    }

    @Test
    fun theExitIsNotifiedAtTenOnTheDayAfterTheLastDay() {
        val exit = de.askurious.app.data.Exit("Abschluss", open = false, until = LocalDate.of(2026, 11, 9))
        val s = study().copy(exit = exit)
        // last day 26 Oct: 27 Oct 10:00 in Berlin is 09:00Z
        val at = Instant.parse("2026-10-27T09:00:00Z").toEpochMilli()
        assertEquals(at, Schedule.exitAt(s, berlin))
        assertEquals(at, Schedule.nextMoment(listOf(s.copy(status = "finished")), Instant.parse("2026-10-26T20:00:00Z"), berlin))
        // notified already: no alarm for it
        assertNull(Schedule.nextMoment(listOf(s.copy(status = "finished")), Instant.parse("2026-10-26T20:00:00Z"), berlin, setOf("abc")))
        assertNull(Schedule.exitAt(s.copy(status = "left"), berlin))
        assertNull(Schedule.exitAt(study(), berlin))
        // posted only when open, from its moment on, and once
        val open = s.copy(status = "finished", exit = exit.copy(open = true))
        assertTrue(!Schedule.exitDue(open, at - 1, notified = false, zone = berlin))
        assertTrue(Schedule.exitDue(open, at, notified = false, zone = berlin))
        assertTrue(Schedule.exitDue(open, at + 5L * 24 * 3600 * 1000, notified = false, zone = berlin))
        assertTrue(!Schedule.exitDue(open, at, notified = true, zone = berlin))
        assertTrue(!Schedule.exitDue(s.copy(status = "finished"), at, notified = false, zone = berlin))
        // a fast-forward person: at the real moment of 27 Oct 10:00 virtual time
        val clock = de.askurious.app.data.Clock(Instant.parse("2026-10-23T22:00:00Z").toEpochMilli(), 144)
        val fast = open.copy(clock = clock)
        assertTrue(!Schedule.exitDue(fast, clock.real(at) - 1, notified = false, zone = berlin))
        assertTrue(Schedule.exitDue(fast, clock.real(at), notified = false, zone = berlin))
        assertEquals(clock.real(at), Schedule.nextMoment(listOf(fast), Instant.ofEpochMilli(clock.t0), berlin))
    }
}
