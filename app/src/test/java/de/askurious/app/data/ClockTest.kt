package de.askurious.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The virtual clock of a fast-forward test person: v = t0 + (real − t0) · factor, alarms at the real moment. */
class ClockTest {
    private val t0 = Instant.parse("2026-10-24T08:00:00Z").toEpochMilli()
    private val clock = Clock(t0, 144)

    @Test
    fun oneStudyDayPassesIn24HoursDividedByTheFactor() {
        assertEquals(t0, clock.virtual(t0))
        // 10 real minutes are one study day at ×144
        assertEquals(t0 + 24L * 3600 * 1000, clock.virtual(t0 + 10L * 60 * 1000))
        assertEquals(t0 + 144L, clock.virtual(t0 + 1))
    }

    @Test
    fun theRealMomentNeverComesBeforeTheVirtualOne() {
        assertEquals(t0, clock.real(t0))
        assertEquals(t0 + 10L * 60 * 1000, clock.real(t0 + 24L * 3600 * 1000))
        // 145 virtual ms after t0 are reached 2 real ms after it (1 ms gives only 144)
        assertEquals(t0 + 2, clock.real(t0 + 145))
        assertEquals(t0 + 1, clock.real(t0 + 144))
        for (v in listOf(t0 - 1000, t0 - 1, t0 + 1, t0 + 143, t0 + 99_999_999)) {
            val r = clock.real(v)
            assertTrue(clock.virtual(r) >= v)
            assertTrue(clock.virtual(r - 1) < v)
        }
    }

    @Test
    fun fromJsonTakesOnlyARealFastForward() {
        assertEquals(clock, Clock.fromJson(JSONObject().put("t0", t0).put("factor", 144)))
        assertNull(Clock.fromJson(JSONObject().put("t0", t0).put("factor", 1)))
        assertNull(Clock.fromJson(null))
        assertNull(Clock.fromJson(JSONObject()))
    }

    @Test
    fun aStudyWithAClockLivesInVirtualTimeAndOneWithoutInRealTime() {
        val berlin = ZoneId.of("Europe/Berlin")
        val plain = Study("abc", "s", "T", "active", LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 30), emptyList())
        val fast = plain.copy(clock = clock)
        val real = t0 + 25L * 60 * 1000 // 2.5 study days later
        assertEquals(real, plain.now(real))
        assertEquals(LocalDate.of(2026, 10, 24), plain.today(berlin, real))
        assertEquals(t0 + 60L * 3600 * 1000, fast.now(real))
        assertEquals(LocalDate.of(2026, 10, 26), fast.today(berlin, real))
        assertEquals(real, fast.realAt(fast.now(real)))
        // stored and read again, the clock stays
        assertEquals(fast, Study.fromJson(fast.toJson()))
    }
}
