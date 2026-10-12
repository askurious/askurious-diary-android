package de.askurious.app.ui

import de.askurious.app.data.Exit
import de.askurious.app.data.Study
import de.askurious.app.data.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

/** What the card and the detail page say about a study (UI audit 2026-10-12, A11, A17, A18, A20, A25). */
class StudyStatusTest {
    private val day = LocalDate.of(2026, 10, 12)

    private fun study(code: String = "s", status: String = "active", first: LocalDate? = day.minusDays(3), last: LocalDate? = day.plusDays(10), exit: Exit? = null, leavePending: Boolean = false) =
        Study(code, "secret", "Titel", status, first, last, emptyList(), exit = exit, leavePending = leavePending)

    @Test
    fun aRunningDayWithinThePeriod() {
        assertEquals(StudyState.RUNNING, studyState(study(), day))
        // the last day still counts
        assertEquals(StudyState.RUNNING, studyState(study(first = day.minusDays(13), last = day), day))
    }

    @Test
    fun noDaySeventeenOfFourteen() {
        // 14 days, today would be day 17: the period is over, the server has not said so yet (A17)
        val s = study(first = day.minusDays(16), last = day.minusDays(3))
        assertEquals(17, s.dayIndex(day))
        assertEquals(14, s.periodDays)
        assertEquals(StudyState.PERIOD_OVER, studyState(s, day))
    }

    @Test
    fun beforeTheFirstDay() {
        assertEquals(StudyState.BEFORE_START, studyState(study(first = day.plusDays(2), last = day.plusDays(15)), day))
    }

    @Test
    fun anOpenExitQuestionnaireIsNoThanks() {
        val open = Exit("", true, day.plusDays(5))
        assertEquals(StudyState.EXIT_OPEN, studyState(study(status = "finished", exit = open), day))
        assertEquals(StudyState.FINISHED, studyState(study(status = "finished", exit = open.copy(open = false)), day))
        assertEquals(StudyState.FINISHED, studyState(study(status = "finished"), day))
    }

    @Test
    fun theOtherStates() {
        assertEquals(StudyState.PENDING, studyState(study(status = "pending", first = null, last = null), day))
        assertEquals(StudyState.PAUSED, studyState(study(status = "paused"), day))
        assertEquals(StudyState.LEFT, studyState(study(status = "left"), day))
        assertEquals(StudyState.LEFT_PENDING, studyState(study(status = "left", leavePending = true), day))
        assertEquals(StudyState.ENDED, studyState(study(status = "ended"), day))
    }

    @Test
    fun currentStudiesOnTopEndedBelowInTheOrderOfJoining() {
        val ended = study("a", status = "ended")
        val running = study("b")
        val finished = study("c", status = "finished")
        val exitOpen = study("d", status = "finished", exit = Exit("", true, null))
        val endedWithExit = study("e", status = "ended", exit = Exit("", true, null))
        val paused = study("f", status = "paused")
        val (top, bottom) = grouped(listOf(ended, running, finished, exitOpen, endedWithExit, paused)) { day }
        assertEquals(listOf("b", "d", "e", "f"), top.map { it.code })
        assertEquals(listOf("a", "c"), bottom.map { it.code })
    }

    @Test
    fun todayTomorrowOrTheDate() {
        assertEquals(DayWord.TODAY, dayWord(day, day))
        assertEquals(DayWord.TOMORROW, dayWord(day.plusDays(1), day))
        assertEquals(DayWord.DATE, dayWord(day.plusDays(2), day))
        assertEquals(DayWord.DATE, dayWord(day.minusDays(1), day))
    }

    @Test
    fun exactAlarmsGiveTheTimeElseAnAbout() {
        assertEquals(false, approximate(exactAlarms = true))
        assertEquals(true, approximate(exactAlarms = false))
    }

    @Test
    fun howOftenAModuleAsks() {
        val everyDay = (1..7).toSet()
        assertEquals(Frequency(2, 0), frequency(Trigger.Fixed(listOf(LocalTime.of(9, 0), LocalTime.of(20, 0)), everyDay)))
        assertEquals(Frequency(0, 6), frequency(Trigger.Fixed(listOf(LocalTime.of(9, 0), LocalTime.of(20, 0)), setOf(1, 3, 5))))
        assertEquals(Frequency(3, 0), frequency(Trigger.Random(3, emptyList(), 60, everyDay)))
        assertEquals(Frequency(0, 2), frequency(Trigger.Random(1, emptyList(), 60, setOf(6, 7))))
        assertNull(frequency(Trigger.Self("Melden", 0)))
    }
}
