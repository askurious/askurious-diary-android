package de.askurious.app.schedule

import de.askurious.app.data.Study
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The prompts must be the server's to the minute (app/diary.py, prompts): the server checks with them that a prompt
 * was due. The vectors are the server's tests/diary_schedule_vectors.json, copied unchanged; they hold studies as the
 * server sends them (fixed and random modules, weekdays, days of a module, quiet hours changing, pauses, the changes
 * of summer time, a zone far from Berlin) and the prompts the server computes for them. A failing case here means the
 * app and the server disagree: a prompt would ring that the server does not count, or one it counts would not ring.
 */
class ScheduleVectorsTest {
    private val vectors: JSONObject by lazy {
        JSONObject(javaClass.classLoader!!.getResourceAsStream("diary_schedule_vectors.json")!!.bufferedReader().use { it.readText() })
    }

    @Test
    fun everyCaseGivesTheServersPrompts() {
        val cases = vectors.getJSONArray("cases")
        assertTrue(cases.length() > 0)
        for (k in 0 until cases.length()) {
            val c = cases.getJSONObject(k)
            val study = Study.fromJson(c.getJSONObject("study"), server = "https://example.org")
            val prompts = Schedule.prompts(study, Instant.EPOCH, Instant.parse("2100-01-01T00:00:00Z"), ZoneId.of(c.getString("tz")))
            val expected = c.getJSONArray("prompts").let { a ->
                (0 until a.length()).map { a.getJSONArray(it).let { p -> p.getString(0) to p.getString(1) } }
            }
            assertEquals(c.getString("name"), expected, prompts.map { it.module to Instant.ofEpochMilli(it.due).toString() })
        }
    }

    @Test
    fun theVectorsCarryTheOldestAppVersion() {
        val study = Study.fromJson(vectors.getJSONArray("cases").getJSONObject(0).getJSONObject("study"), server = "https://example.org")
        assertEquals(0, study.minAppVersion)
        assertTrue(!study.needsUpdate(1))
        assertTrue(study.copy(minAppVersion = 5).needsUpdate(4))
        assertTrue(!study.copy(minAppVersion = 5).needsUpdate(5))
        // kept on the phone with the study
        assertEquals(5, Study.fromJson(study.copy(minAppVersion = 5).toJson()).minAppVersion)
    }
}
