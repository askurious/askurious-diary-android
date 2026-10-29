package de.askurious.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/** An answer of the server this app cannot fully read costs nothing but the unreadable part (Study.fromJson). */
class StudyJsonTest {
    @Test
    fun unreadableDatesModulesAndTimesAreLeftOut() {
        val o = JSONObject(
            """
            {"code": "abc", "title": "T", "status": "active", "first_day": "2026-13-45", "last_day": "soon",
             "modules": [
               {"key": "m1", "name": "Morning", "trigger": {"t": "fixed", "times": ["08:00", "25:99", 7], "weekdays": [1, 9, "x"]}},
               {"name": "no key", "trigger": {"t": "fixed", "times": ["09:00"]}},
               "not a module",
               {"key": "m2", "name": "Self", "trigger": {"t": "self", "max_per_day": 2}}
             ]}
            """.trimIndent()
        )
        val s = Study.fromJson(o, "secret")
        assertNull(s.firstDay)
        assertNull(s.lastDay)
        assertEquals(listOf("m1", "m2"), s.modules.map { it.key })
        val fixed = s.modules[0].trigger as Trigger.Fixed
        assertEquals(listOf(LocalTime.of(8, 0)), fixed.times)
        assertEquals(setOf(1), fixed.weekdays)
        assertTrue(s.modules[1].trigger is Trigger.Self)
    }

    @Test
    fun summaryOpenEndSoundAndAlsoSelfStartedModulesAreKept() {
        val o = JSONObject(
            """
            {"code": "abc", "title": "T", "status": "active", "first_day": "2026-10-01", "last_day": null,
             "summary": " Three short questions a day. ", "open_end": true,
             "modules": [
               {"key": "m1", "name": "Mood", "trigger": {"t": "fixed", "times": ["08:00"]}, "self": {"label": "Now", "max_per_day": 3}},
               {"key": "m2", "name": "Self", "trigger": {"t": "self", "label": "Report", "max_per_day": 2}, "self": {"label": "x"}}
             ]}
            """.trimIndent()
        )
        val s = Study.fromJson(o, "secret")
        assertEquals("Three short questions a day.", s.summary)
        assertTrue(s.openEnd)
        assertTrue(s.sound)
        assertEquals(Trigger.Self("Now", 3), s.modules[0].selfStart)
        assertEquals(Trigger.Self("Now", 3), s.modules[0].selfTrigger)
        // a self module keeps its own trigger; "self" is only for prompted modules
        assertNull(s.modules[1].selfStart)
        assertEquals(Trigger.Self("Report", 2), s.modules[1].selfTrigger)
        val again = Study.fromJson(s.copy(sound = false).toJson())
        assertEquals(s.copy(sound = false), again)
    }

    @Test
    fun aStudyOfAnOlderServerHasNoSummaryAndAnEnd() {
        val s = Study.fromJson(JSONObject("""{"code": "abc", "status": "active", "first_day": "2026-10-01", "last_day": "2026-10-14"}"""), "s")
        assertEquals("", s.summary)
        assertEquals(false, s.openEnd)
        assertEquals(14, s.periodDays)
    }

    /** What is left of a self module's daily maximum (UI/UX audit 2026-10-29, AND-05): the server's count of today, the waiting answers on top. */
    @Test
    fun selfReportsLeftToday() {
        val today = java.time.LocalDate.now().toString()
        val o = JSONObject(
            """
            {"code": "abc", "title": "T", "status": "active", "first_day": "2026-10-01", "self_date": "$today",
             "modules": [
               {"key": "m1", "name": "Self", "trigger": {"t": "self", "max_per_day": 3}, "self_today": 2},
               {"key": "m2", "name": "Free", "trigger": {"t": "self", "max_per_day": 0}, "self_today": 9}
             ]}
            """.trimIndent()
        )
        val s = Study.fromJson(o, "secret")
        assertEquals(1, s.selfLeft(s.modules[0], waiting = 0))
        assertEquals(0, s.selfLeft(s.modules[0], waiting = 1))
        assertNull(s.selfLeft(s.modules[1], waiting = 0))  // no maximum: nothing to count down
        // a count of another day no longer applies
        val yesterday = Study.fromJson(s.copy(selfDate = "2000-01-01").toJson())
        assertEquals(3, yesterday.selfLeft(yesterday.modules[0], waiting = 0))
        assertEquals(s, Study.fromJson(s.toJson()))
    }
}
