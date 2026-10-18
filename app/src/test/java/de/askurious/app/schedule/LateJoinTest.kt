package de.askurious.app.schedule

import de.askurious.app.data.Module
import de.askurious.app.data.Study
import de.askurious.app.data.Trigger
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * Joined late in the day (audit 2026-10-18, follow-up of AND-02): the server counts no prompt due before the entry
 * part was completed (review 2026-10-15, G1) and refused the answers of those with 400, while the app offered them.
 * The server now names the moment from which the prompts count ("prompts_from", the minute after the last one it
 * leaves out); an older server sends none and the app keeps the whole schedule.
 */
class LateJoinTest {
    private val zone = ZoneId.of("UTC")
    private val times = listOf(LocalTime.of(9, 0), LocalTime.of(14, 0), LocalTime.of(19, 0))
    private val module = Module("m1", "Moment", Trigger.Fixed(times, (1..7).toSet()), 0, 1, 14, "", reminder = 30)
    private val day = LocalDate.of(2026, 5, 4)
    private val study = Study("abc", "secret", "Study", "active", day, day.plusDays(2), listOf(module))
    private fun at(h: Int, m: Int = 0) = Instant.parse("2026-05-04T%02d:%02d:00Z".format(h, m))
    private val cut = at(19, 1).toEpochMilli()  // joined at 20:00: the server leaves out 9, 14 and 19

    @Test fun promptsBeforeTheJoinAreNotOfferedNorReminded() {
        val late = study.copy(promptsFrom = cut)
        val now = at(20, 5)
        assertEquals(3, Schedule.available(study, now, zone).size)  // before: offered, then refused with 400
        assertTrue(Schedule.available(late, now, zone).isEmpty())
        assertTrue(Schedule.reminders(late, at(0), at(23, 59), zone).isEmpty())
        // the next days are untouched
        val next = Schedule.next(listOf(late), now, zone).single()
        assertEquals(Instant.parse("2026-05-05T09:00:00Z").toEpochMilli(), next.due)
        assertEquals(6, Schedule.prompts(late, at(0), Instant.parse("2026-05-07T00:00:00Z"), zone).size)
    }

    @Test fun aPromptInTheMinuteOfTheJoinStillCounts() {
        val inMinute = study.copy(promptsFrom = at(9, 1).toEpochMilli())  // joined at 14:00:40
        assertEquals(listOf(at(14), at(19)).map { it.toEpochMilli() },
            Schedule.prompts(inMinute, at(0), at(23, 59), zone).map { it.due })
    }

    @Test fun theMomentIsKeptOnThePhoneAndAnOlderServerSendsNone() {
        val fromServer = Study.fromJson(JSONObject().put("code", "abc").put("prompts_from", cut))
        assertEquals(cut, fromServer.promptsFrom)
        assertEquals(cut, Study.fromJson(fromServer.toJson()).promptsFrom)
        assertNull(Study.fromJson(JSONObject().put("code", "abc")).promptsFrom)
        assertNull(Study.fromJson(JSONObject().put("code", "abc").put("prompts_from", JSONObject.NULL)).promptsFrom)
        assertNull(Study.fromJson(JSONObject().put("code", "abc")).let { Study.fromJson(it.toJson()) }.promptsFrom)
    }
}
