package de.askurious.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** The quiet hours valid on a day: the server's diary.quiet_on and set_quiet, on the history it sends. */
class QuietTest {
    private val history = listOf(
        QuietFrom(LocalDate.of(2026, 10, 20), Quiet("22:00", "07:00")),
        QuietFrom(LocalDate.of(2026, 10, 23), null),
        QuietFrom(LocalDate.of(2026, 10, 25), Quiet("12:00", "13:30")),
    )

    @Test
    fun quietOnTakesTheLastEntryFromThatDayOrEarlier() {
        assertNull(QuietFrom.on(history, LocalDate.of(2026, 10, 19)))
        assertEquals(Quiet("22:00", "07:00"), QuietFrom.on(history, LocalDate.of(2026, 10, 20)))
        assertEquals(Quiet("22:00", "07:00"), QuietFrom.on(history, LocalDate.of(2026, 10, 22)))
        assertNull(QuietFrom.on(history, LocalDate.of(2026, 10, 23)))
        assertNull(QuietFrom.on(history, LocalDate.of(2026, 10, 24)))
        assertEquals(Quiet("12:00", "13:30"), QuietFrom.on(history, LocalDate.of(2027, 1, 1)))
        assertNull(QuietFrom.on(emptyList(), LocalDate.of(2026, 10, 24)))
    }

    @Test
    fun aChangeNotYetConfirmedCountsFromItsDayByTheServersRule() {
        val s = Study(
            code = "abc", secret = "s", title = "T", status = "active", firstDay = null, lastDay = null, modules = emptyList(),
            quiet = history, quietPending = QuietFrom(LocalDate.of(2026, 10, 26), Quiet("21:00", "08:00")),
        )
        assertEquals(Quiet("12:00", "13:30"), s.quietOn(LocalDate.of(2026, 10, 25)))
        assertEquals(Quiet("21:00", "08:00"), s.quietOn(LocalDate.of(2026, 10, 26)))
        // the same quiet hours as the last entry: no entry (diary.set_quiet)
        assertEquals(history, QuietFrom.with(history, LocalDate.of(2026, 10, 26), Quiet("12:00", "13:30")))
        // a second change on the same day replaces the first
        val twice = QuietFrom.with(QuietFrom.with(history, LocalDate.of(2026, 10, 26), null), LocalDate.of(2026, 10, 26), Quiet("23:00", "06:00"))
        assertEquals(QuietFrom(LocalDate.of(2026, 10, 26), Quiet("23:00", "06:00")), twice.last())
        assertEquals(history.size + 1, twice.size)
    }

    @Test
    fun seedQuietAndPendingSurviveStorageAndTheServersFormatIsRead() {
        val server = JSONObject(
            """{"code": "abc", "title": "T", "status": "active", "first_day": "2026-10-24", "last_day": "2026-10-30",
            "modules": [{"key": "m1", "name": "M", "trigger": {"t": "random", "per_day": 3, "windows": [["9:00", "21:00"]],
            "min_gap": 60, "weekdays": [1, 2, 3]}, "expires": 30, "reminder": 10, "days": [1, 100000], "notify": "", "offline": true}],
            "seed": 9007199254740991, "quiet": [{"from": "2026-10-25", "q": ["22:00", "07:00"]}, {"from": "2026-10-27", "q": null}]}""",
        )
        val s = Study.fromJson(server, "secret")
        assertEquals(9007199254740991L, s.seed)
        assertEquals(Trigger.Random(3, listOf("09:00" to "21:00"), 60, setOf(1, 2, 3)), s.modules.single().trigger)
        assertEquals(10, s.modules.single().reminder)
        assertEquals(listOf(QuietFrom(LocalDate.of(2026, 10, 25), Quiet("22:00", "07:00")), QuietFrom(LocalDate.of(2026, 10, 27), null)), s.quiet)
        assertNull(s.quietPending)
        val pending = s.copy(quietPending = QuietFrom(LocalDate.of(2026, 10, 28), Quiet("20:00", "06:00")))
        assertEquals(pending, Study.fromJson(JSONObject(pending.toJson().toString())))
    }
}
