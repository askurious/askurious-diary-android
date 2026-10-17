package de.askurious.app.data

import de.askurious.app.schedule.Schedule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class EntryTransmissionTest {
    private val sealer = object : Sealer {
        override fun seal(plain: String, aad: String) = plain
        override fun open(sealed: String, aad: String): String = sealed
    }
    private fun ms(s: String) = Instant.parse(s).toEpochMilli()
    private fun study() = Study("s", "secret", "Title", "active", LocalDate.parse("2026-10-17"),
        LocalDate.parse("2026-10-20"), listOf(Module("m", "M", Trigger.Fixed(listOf(LocalTime.of(9, 0)),
            (1..7).toSet()), 60, 1, 10, "")), timezone = "America/Los_Angeles", zoneHistory = listOf(
            ZoneFrom(null, ZoneId.of("UTC")), ZoneFrom(LocalDate.parse("2026-10-18"), ZoneId.of("America/Los_Angeles"))))

    @Test fun sameUuidIsQueuedOnceAndRestored() {
        val memory = MemoryPreferences()
        val queue = AnswerQueue(memory.prefs, sealer)
        repeat(2) { queue.add("s", "m", null, null, 1, 2, "v", "{}", "same") }
        assertEquals(1, AnswerQueue(memory.prefs, sealer).all().size)
        assertEquals("same", queue.all().single().body().getString("uuid"))
    }

    @Test fun uuidCannotCrossStudiesOrModules() {
        val queue = AnswerQueue(MemoryPreferences().prefs, sealer)
        queue.add("s", "m", null, null, 1, 2, "v", "{}", "same")
        assertThrows(IllegalArgumentException::class.java) {
            queue.add("other", "m", null, null, 1, 2, "v", "{}", "same")
        }
        assertEquals("s", queue.all().single().study)
    }

    @Test fun localQuotaCountsOnlySelfStartsOnTheirStartDate() {
        val queue = AnswerQueue(MemoryPreferences().prefs, sealer)
        val start = ms("2026-10-17T23:59:00Z")
        val end = ms("2026-10-18T00:01:00Z")
        queue.add("s", "m", start, null, start, end, "v", "{}")
        assertEquals(0, queue.today(study(), "m", start))
        queue.add("s", "m", null, null, start, end, "v", "{}")
        assertEquals(1, queue.today(study(), "m", start))
        assertEquals(0, queue.today(study(), "m", end))
        assertEquals(0, queue.today(study(), "other", start))
    }

    @Test fun timezoneChangeAppliesTomorrowAndSurvivesStorage() {
        val s = Study.fromJson(study().toJson())
        val prompts = Schedule.prompts(s, Instant.parse("2026-10-17T00:00:00Z"),
            Instant.parse("2026-10-19T00:00:00Z"), ZoneId.of("America/Los_Angeles"))
        assertEquals(listOf(ms("2026-10-17T09:00:00Z"), ms("2026-10-18T16:00:00Z")), prompts.map { it.due })
        assertEquals(ZoneId.of("UTC"), s.zoneAt(Instant.parse("2026-10-18T06:59:00Z")))
        assertEquals(ZoneId.of("America/Los_Angeles"), s.zoneAt(Instant.parse("2026-10-18T07:00:00Z")))
    }

    @Test fun slowSyncCannotReactivatePendingLeave() {
        val store = StudyStore(MemoryPreferences().prefs, sealer)
        val s = study()
        store.put(s)
        store.update(s.copy(status = "left", leavePending = true))
        store.synced(s, null)
        assertEquals("left", store.get(s.code)!!.status)
        assertTrue(store.get(s.code)!!.leavePending)
    }

    @Test fun failedUploadDefersLeaveUntilSuccessfulRetry() = runBlocking {
        val calls = mutableListOf<String>()
        assertFalse(transmitLeave({ calls += "upload failed"; false }, { calls += "leave"; true }))
        assertEquals(listOf("upload failed"), calls)
        assertTrue(transmitLeave({ calls += "stored"; true }, { calls += "leave"; true }))
        assertEquals(listOf("upload failed", "stored", "leave"), calls)
    }
}
