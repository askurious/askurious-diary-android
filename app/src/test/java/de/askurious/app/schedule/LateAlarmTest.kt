package de.askurious.app.schedule

import de.askurious.app.data.Module
import de.askurious.app.data.Study
import de.askurious.app.data.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * An alarm that rings late, or is set anew before it rang, loses no prompt (audit 2026-10-18, AND-02). Without the
 * permission for exact alarms (the default from Android 14) the alarm rings minutes late; only the prompts of its very
 * moment were posted, and [Alarms.rearm] set the next alarm from now on, so a prompt between the two was never
 * notified. [Alarms.ringing] gives every prompt and reminder that rang since the alarm's moment, [Alarms.rearm]
 * posts them from the moment of the alarm it replaces when that has passed ([Alarms.catchUpFrom]); PromptLog keeps
 * each from being posted twice.
 */
class LateAlarmTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private fun fixed(key: String, h: Int, m: Int, reminder: Int = 0) =
        Module(key, key, Trigger.Fixed(listOf(LocalTime.of(h, m)), (1..7).toSet()), 60, 1, 14, "", reminder = reminder)
    private val m1 = fixed("m1", 12, 0)
    private val m2 = fixed("m2", 12, 5)
    private val m3 = fixed("m3", 12, 10)
    private val study = Study("abc", "s", "T", "active", LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 27), listOf(m1, m2, m3))
    private fun t(s: String) = Instant.parse(s).toEpochMilli()

    @Test
    fun anAlarmThatRingsLatePostsEveryPromptSinceItsMoment() {
        val at = t("2026-10-15T10:00:00Z")        // 12:00 in Berlin, the moment of the alarm
        val real = t("2026-10-15T10:07:00Z")      // it rings seven minutes late
        val (prompts, _) = Alarms.ringing(study, at, real, zone)
        assertEquals(listOf("m1", "m2"), prompts.map { it.module })
        // and the next alarm is the prompt after now
        assertEquals(t("2026-10-15T10:10:00Z"), Schedule.nextMoment(listOf(study), Instant.ofEpochMilli(real), zone))
    }

    @Test
    fun settingTheAlarmAnewPostsThePromptItWasDueFor() {
        // the alarm was set for the 12:00 prompt; the app starts 489 ms after that moment, before the inexact alarm
        // rang, and rearm() replaces it: it first posts from the moment the replaced alarm was set for
        val armedFor = t("2026-10-15T10:00:00Z")
        val real = t("2026-10-15T10:00:00.489Z")
        val from = Alarms.catchUpFrom(armedFor, real)!!
        assertEquals(listOf("m1"), Alarms.ringing(study, from, real, zone).first.map { it.module })
    }

    @Test
    fun anAlarmNotDueYetOrNeverSetPostsNothingOnRearm() {
        // joined at 13:30 with prompts at 12:00 to 12:10 earlier that day: no alarm was ever set for them, nothing is
        // posted (the server does not count prompts before the entry part either)
        assertNull(Alarms.catchUpFrom(null, t("2026-10-15T11:30:00Z")))
        // the alarm is set for a later moment: nothing has rung meanwhile
        assertNull(Alarms.catchUpFrom(t("2026-10-15T16:00:00Z"), t("2026-10-15T11:30:00Z")))
    }

    @Test
    fun anAlarmHoursLateStillBringsTheOpenPromptsSinceItsMoment() {
        val real = t("2026-10-15T11:30:00Z")      // 13:30: the alarm of 12:00 rings only now
        // whether they still may be answered (expiry) is checked when posting
        assertEquals(listOf("m1", "m2", "m3"), Alarms.ringing(study, t("2026-10-15T10:00:00Z"), real, zone).first.map { it.module })
        // a moment of now alone: only what rings now
        assertEquals(emptyList<String>(), Alarms.ringing(study, real, real, zone).first.map { it.module })
    }

    @Test
    fun aReminderThatRangMeanwhileIsFoundToo() {
        val s = study.copy(modules = listOf(fixed("m1", 12, 0, reminder = 5)))
        val (prompts, reminders) = Alarms.ringing(s, t("2026-10-15T10:00:00Z"), t("2026-10-15T10:07:00Z"), zone)
        assertEquals(listOf("m1"), prompts.map { it.module })
        assertEquals(listOf(t("2026-10-15T10:05:00Z")), reminders.map { it.remindAt })
    }
}
