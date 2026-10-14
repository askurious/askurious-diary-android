package de.askurious.app.schedule

import de.askurious.app.data.Module
import de.askurious.app.data.Study
import de.askurious.app.data.Trigger
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class OpenPromptsTest {
    private val zone = ZoneId.of("Europe/Berlin")
    private val module = Module("m1", "Moment", Trigger.Fixed(listOf(LocalTime.of(9,0),LocalTime.of(12,0)),(1..7).toSet()), 60, 1, 14, "")
    private val study = Study("abc","secret","Study","active",LocalDate.of(2026,10,14),LocalDate.of(2026,10,27),listOf(module))
    @Test fun onlyOpenUnansweredPromptsAreAvailable() {
        val now = Instant.parse("2026-10-14T07:30:00Z")
        val prompts = Schedule.available(study, now, zone)
        assertEquals(1, prompts.size)
        val done = study.copy(completedPrompts=setOf("m1|${prompts.single().due}"))
        assertTrue(Schedule.available(done,now,zone).isEmpty())
        assertTrue(Schedule.available(study,Instant.parse("2026-10-14T08:00:00Z"),zone).isEmpty())
        assertTrue(Schedule.available(study.copy(status="paused"),now,zone).isEmpty())
    }
    @Test fun noExpiryEndsAtMidnightButLongExpiryMayCrossMidnight() {
        val overnight = module.copy(trigger=Trigger.Fixed(listOf(LocalTime.of(23,30)),(1..7).toSet()),expires=120)
        val now=Instant.parse("2026-10-14T22:15:00Z") // 00:15 on 15 October
        assertEquals(1,Schedule.available(study.copy(modules=listOf(overnight)),now,zone).size)
        assertTrue(Schedule.available(study.copy(modules=listOf(overnight.copy(expires=0))),now,zone).isEmpty())
    }
    @Test fun openEndedStudyStillSchedulesPrompts() {
        val open=study.copy(lastDay=null,openEnd=true)
        assertEquals(1,Schedule.available(open,Instant.parse("2026-10-14T07:30:00Z"),zone).size)
        assertFalse(Schedule.next(listOf(open),Instant.parse("2026-10-14T07:30:00Z"),zone).isEmpty())
    }
}
