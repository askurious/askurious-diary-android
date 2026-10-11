package de.askurious.app.schedule

import de.askurious.app.data.Module
import de.askurious.app.data.Study
import de.askurious.app.data.Trigger
import org.junit.Assert.assertEquals
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

    private fun fixed(key: String, vararg times: String, weekdays: Set<Int> = (1..7).toSet(), from: Int = 1) = Module(
        key, key, Trigger.Fixed(times.map(LocalTime::parse), weekdays), expires = 60, dayFrom = from, dayTo = Int.MAX_VALUE, notify = "",
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
}
