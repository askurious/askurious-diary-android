package de.askurious.app.schedule

import de.askurious.app.data.Quiet
import de.askurious.app.data.Trigger
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * The random prompts must be the server's to the minute (app/diary.py, random_times): the vectors are the server's
 * tests/diary_random_vectors.json, copied unchanged.
 */
class RandomTimesTest {
    private val vectors: JSONObject by lazy {
        JSONObject(javaClass.classLoader!!.getResourceAsStream("diary_random_vectors.json")!!.bufferedReader().use { it.readText() })
    }

    private fun trigger(o: JSONObject): Trigger.Random {
        val w = o.getJSONArray("windows")
        return Trigger.Random(
            perDay = o.getInt("per_day"),
            windows = (0 until w.length()).map { w.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } },
            minGap = o.optInt("min_gap", 0),
            weekdays = (1..7).toSet(),
        )
    }

    @Test
    fun checkValuesOfTheHashAndTheGenerator() {
        val check = vectors.getJSONObject("check")
        assertEquals(check.getString("fnv_m1"), RandomTimes.fnv("m1").toString())
        val (value, state) = RandomTimes.splitmix(0uL)
        assertEquals(check.getString("splitmix_0_value"), value.toString())
        assertEquals(check.getString("splitmix_0_state"), state.toString())
    }

    @Test
    fun everyVectorGivesTheServersTimes() {
        val cases = vectors.getJSONArray("cases")
        assertTrue(cases.length() > 0)
        for (k in 0 until cases.length()) {
            val c = cases.getJSONObject(k)
            val quiet = if (c.isNull("quiet")) null else Quiet.fromJson(c.getJSONArray("quiet"))
            val times = RandomTimes.times(c.getLong("seed"), c.getString("key"), LocalDate.parse(c.getString("day")), trigger(c.getJSONObject("trigger")), quiet)
            val expected = c.getJSONArray("times").let { a -> (0 until a.length()).map { a.getString(it) } }
            assertEquals("case $k", expected, times.map { it.toString() })
        }
    }

    @Test
    fun freeMinutesLeaveOutTheQuietHoursAcrossMidnight() {
        assertEquals(listOf(0 to 120, 1200 to 1380), RandomTimes.free(listOf("20:00" to "02:00"), Quiet("23:00", "00:00")))
        assertEquals(listOf(540 to 720, 810 to 1260), RandomTimes.free(listOf("09:00" to "21:00"), Quiet("12:00", "13:30")))
        assertEquals(emptyList<Pair<Int, Int>>(), RandomTimes.free(listOf("10:00" to "10:00"), null))
    }

    @Test
    fun noTimesWithoutFreeMinutesOrPrompts() {
        val t = Trigger.Random(3, listOf("09:00" to "21:00"), 60, (1..7).toSet())
        assertEquals(emptyList<Any>(), RandomTimes.times(1, "m1", LocalDate.of(2026, 10, 24), t, Quiet("08:00", "22:00")))
        assertEquals(emptyList<Any>(), RandomTimes.times(1, "m1", LocalDate.of(2026, 10, 24), t.copy(perDay = 0), null))
    }

    @Test
    fun timesKeepTheGapAndStayInTheWindows() {
        val t = Trigger.Random(5, listOf("08:00" to "12:00", "14:00" to "22:00"), 45, (1..7).toSet())
        for (d in 0L until 60) {
            val times = RandomTimes.times(987654321L, "m1", LocalDate.of(2026, 1, 1).plusDays(d), t, null).map { it.hour * 60 + it.minute }
            assertEquals(5, times.size)
            assertTrue(times.all { it in 480 until 720 || it in 840 until 1320 })
            // the gap holds within the free minutes (here also on the clock, the break only adds to it)
            assertTrue(times.zipWithNext().all { (a, b) -> b - a >= 45 })
        }
    }
}
