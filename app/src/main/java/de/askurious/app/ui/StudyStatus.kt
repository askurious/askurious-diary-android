package de.askurious.app.ui

import de.askurious.app.data.Study
import de.askurious.app.data.Trigger
import java.time.LocalDate

/**
 * Where a study stands for the participant, as the card and the detail page say it (with an icon): one state each,
 * so that no card says two things at once (audit A17, A18, A20). Plain Kotlin, so the unit tests can run it.
 */
enum class StudyState {
    /** The first questionnaire (consent, entry part) is not done. */
    PENDING,

    /** Joined, the study period begins later. */
    BEFORE_START,

    /** In the study period. */
    RUNNING,

    /**
     * The phone's day lies after the study period while the server still says "active" (no sync yet, or a fast-forward
     * person): "Study period over – updating …" instead of "Day 17 of 14" (audit A17).
     */
    PERIOD_OVER,

    PAUSED,

    /** Over, and the exit questionnaire is still open: the card asks for it instead of thanking (audit A18). */
    EXIT_OPEN,

    FINISHED,
    LEFT,

    /** Left without a connection: the app tells the researchers at the next sync. */
    LEFT_PENDING,

    /** Ended by the researchers (also early for this person, or a study the server no longer knows). */
    ENDED,
}

/** The state of [study] on its day [today] (the study's date: virtual for a fast-forward person). */
fun studyState(study: Study, today: LocalDate): StudyState = when (study.status) {
    "pending" -> StudyState.PENDING
    "active" -> {
        val first = study.firstDay
        val day = study.dayIndex(today)
        val total = study.periodDays
        when {
            first != null && today.isBefore(first) -> StudyState.BEFORE_START
            day != null && total != null && day > total -> StudyState.PERIOD_OVER
            else -> StudyState.RUNNING
        }
    }
    "paused" -> StudyState.PAUSED
    "finished" -> if (study.exit?.open == true) StudyState.EXIT_OPEN else StudyState.FINISHED
    "left" -> if (study.leavePending) StudyState.LEFT_PENDING else StudyState.LEFT
    else -> StudyState.ENDED
}

/** Whether a study in [state] still asks something of the participant: it stands in the upper group of the list. */
fun current(state: StudyState): Boolean = state in setOf(
    StudyState.PENDING, StudyState.BEFORE_START, StudyState.RUNNING, StudyState.PERIOD_OVER, StudyState.PAUSED, StudyState.EXIT_OPEN,
)

/**
 * The studies in two groups (audit A20): those that still ask something (running, not yet begun, paused, an open exit
 * questionnaire; an ended study with an open exit questionnaire too) on top, then the ended ones under "Ended". Each
 * group keeps the order of joining. [today] gives a study's date (virtual for a fast-forward person).
 */
fun grouped(studies: List<Study>, today: (Study) -> LocalDate): Pair<List<Study>, List<Study>> =
    studies.partition { current(studyState(it, today(it))) || it.exit?.open == true }

/** The words for the day of a time on the card: "today, 09:40", "tomorrow, 08:15", else the date (audit A25). */
enum class DayWord { TODAY, TOMORROW, DATE }

fun dayWord(day: LocalDate, today: LocalDate): DayWord = when (day) {
    today -> DayWord.TODAY
    today.plusDays(1) -> DayWord.TOMORROW
    else -> DayWord.DATE
}

/**
 * Whether the card gives the time of the next question as "about" ("today, about 09:40"): while the app may not set
 * exact alarms ("Alarms & reminders" off), Android rings a few minutes late, for a fast-forward person hours of study
 * time (audit A11).
 */
fun approximate(exactAlarms: Boolean): Boolean = !exactAlarms

/** How often a module asks, for the detail page: per day when it runs every day, else per week. */
data class Frequency(val perDay: Int, val perWeek: Int) {
    val daily: Boolean get() = perDay > 0
}

/** Null for a module the participant starts. */
fun frequency(trigger: Trigger): Frequency? = when (trigger) {
    is Trigger.Fixed -> if (trigger.weekdays.size >= 7) Frequency(trigger.times.size, 0) else Frequency(0, trigger.times.size * trigger.weekdays.size)
    is Trigger.Random -> if (trigger.weekdays.size >= 7) Frequency(trigger.perDay, 0) else Frequency(0, trigger.perDay * trigger.weekdays.size)
    is Trigger.Self -> null
}
