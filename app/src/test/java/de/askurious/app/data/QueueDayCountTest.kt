package de.askurious.app.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The daily count of self-started answers (max_per_day, AnswerQueue.today with a Study): by the local date of
 * `started` in the study's zone at that moment, opening only the answers started near the day.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class QueueDayCountTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("queue", Context.MODE_PRIVATE)
    private val sealer = FakeSealer()

    private class Counting(private val inner: Sealer) : Sealer {
        val rows = mutableListOf<String>()
        override fun seal(plain: String, aad: String) = inner.seal(plain, aad)
        override fun open(sealed: String, aad: String): String? {
            if (aad.startsWith("queue-row:")) rows.add(aad.removePrefix("queue-row:"))
            return inner.open(sealed, aad)
        }
    }

    private fun ms(s: String) = Instant.parse(s).toEpochMilli()

    private fun study(vararg zones: ZoneFrom) = Study("abc", "secret", "Title", "active", LocalDate.parse("2026-10-01"),
        LocalDate.parse("2026-11-30"), emptyList(), timezone = zones.last().zone.id, zoneHistory = zones.toList())

    private fun study(zone: String) = study(ZoneFrom(null, ZoneId.of(zone)))

    private fun self(q: AnswerQueue, id: String, started: String, module: String = "m1", completed: String = started) =
        q.add("abc", module, null, null, ms(started), ms(completed), "v", "{}", id)

    @Before fun reset() {
        QueueDatabase.closeForTest(); context.deleteDatabase("answers.db"); prefs.edit().clear().commit()
        SealedText(prefs, "items", sealer, "queue").remove(durable = true)
    }

    @After fun close() { QueueDatabase.closeForTest() }

    @Test fun manyAnswersOpenOnlyThoseStartedNearTheDay() {
        val q = AnswerQueue(context, sealer)
        val first = Instant.parse("2026-10-18T08:00:00Z")
        // 700 answers: 7 days x 10 modules x 10 per day
        for (day in 0L until 7) for (m in 0 until 10) for (n in 0 until 10) {
            val at = first.plusSeconds(day * 86_400 + n * 3_600L).toString()
            self(q, "d$day-m$m-n$n", at, module = "m$m")
        }
        val counting = Counting(sealer)
        val now = ms("2026-10-21T20:00:00Z")
        assertEquals(10, AnswerQueue(context, counting).today(study("Europe/Berlin"), "m3", now))
        // only module m3 of the days 20 to 22 (UTC) are opened, not the whole study
        assertTrue("${counting.rows.size} rows opened", counting.rows.size <= 30)
        assertTrue(counting.rows.all { it.contains("-m3-") })
    }

    @Test fun theDayEndsAtLocalMidnightAndCountsByStartNotCompletion() {
        val q = AnswerQueue(context, sealer)
        self(q, "late", "2026-10-20T21:59:00Z", completed = "2026-10-20T22:05:00Z")  // 23:59 in Berlin, sent after midnight
        self(q, "early", "2026-10-20T22:01:00Z")  // 00:01 of the next day
        self(q, "noon", "2026-10-20T12:00:00Z")
        q.add("abc", "m1", ms("2026-10-20T12:00:00Z"), null, ms("2026-10-20T12:00:00Z"), ms("2026-10-20T12:01:00Z"), "v", "{}", "due")
        val s = study("Europe/Berlin")
        assertEquals(2, q.today(s, "m1", ms("2026-10-20T21:59:30Z")))  // late and noon; the answer to a prompt does not count
        assertEquals(1, q.today(s, "m1", ms("2026-10-20T22:00:00Z")))  // only early
    }

    @Test fun theDayOfTheDstChangeHas25Hours() {
        val q = AnswerQueue(context, sealer)
        self(q, "before", "2026-10-24T21:59:59Z")  // 23:59:59 CEST on the 24th
        self(q, "first", "2026-10-24T22:30:00Z")  // 00:30 CEST on the 25th
        self(q, "repeated", "2026-10-25T01:30:00Z")  // 02:30 CET, the second 02:30 of the night
        self(q, "last", "2026-10-25T22:59:00Z")  // 23:59 CET on the 25th
        self(q, "after", "2026-10-25T23:00:00Z")  // 00:00 CET on the 26th
        val s = study("Europe/Berlin")
        assertEquals(3, q.today(s, "m1", ms("2026-10-25T12:00:00Z")))
        assertEquals(1, q.today(s, "m1", ms("2026-10-24T12:00:00Z")))
        assertEquals(1, q.today(s, "m1", ms("2026-10-26T12:00:00Z")))
    }

    @Test fun zonesFarFromUtcReachTheNeighbouringUtcDays() {
        val q = AnswerQueue(context, sealer)
        self(q, "east", "2026-10-23T10:30:00Z")  // 00:30 on the 24th at +14
        self(q, "west", "2026-10-25T11:30:00Z")  // 23:30 on the 24th at -12
        assertEquals(1, q.today(study("Pacific/Kiritimati"), "m1", ms("2026-10-24T00:00:00Z")))
        assertEquals(1, q.today(study("Etc/GMT+12"), "m1", ms("2026-10-25T00:00:00Z")))
    }

    @Test fun aChangeOfZoneTakesEachAnswersOwnZone() {
        val q = AnswerQueue(context, sealer)
        // UTC until the 17th, Los Angeles from the 18th (local midnight there = 07:00 UTC)
        val s = study(ZoneFrom(null, ZoneId.of("UTC")), ZoneFrom(LocalDate.parse("2026-10-18"), ZoneId.of("America/Los_Angeles")))
        self(q, "utc", "2026-10-17T23:59:00Z")  // the 17th in UTC
        self(q, "gap", "2026-10-18T03:00:00Z")  // UTC still: the 18th (zone changes at 07:00 UTC)
        self(q, "la", "2026-10-18T20:00:00Z")  // 13:00 in Los Angeles, the 18th
        assertEquals(1, q.today(s, "m1", ms("2026-10-17T12:00:00Z")))
        assertEquals(2, q.today(s, "m1", ms("2026-10-18T20:30:00Z")))
    }

    /** A queue of schema 1 (no start_day): upgraded, every row gets its tag once, and the count stays exact. */
    @Test fun aQueueOfSchemaOneIsUpgradedAndBackfilled() {
        val key = ByteArray(32) { it.toByte() }
        fun tag(kind: String, value: String) = Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            Base64.getEncoder().encodeToString(doFinal("$kind:$value".toByteArray(Charsets.UTF_8)))
        }
        val file = context.getDatabasePath("answers.db").also { it.parentFile?.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE metadata(name TEXT PRIMARY KEY, value TEXT NOT NULL)")
            db.execSQL("CREATE TABLE answers(id TEXT PRIMARY KEY, study TEXT NOT NULL, module TEXT NOT NULL, prompt TEXT NOT NULL, day TEXT NOT NULL, body TEXT NOT NULL)")
            db.execSQL("CREATE INDEX answers_prompt ON answers(study,module,prompt)")
            db.execSQL("CREATE INDEX answers_day ON answers(study,module,prompt,day)")
            db.execSQL("INSERT INTO metadata(name,value) VALUES('index',?)",
                arrayOf(sealer.seal(Base64.getEncoder().encodeToString(key), "queue-index")))
            for ((id, started) in listOf("old" to "2026-09-01T10:00:00Z", "today" to "2026-10-20T10:00:00Z", "also" to "2026-10-20T11:00:00Z")) {
                val item = AnswerQueue.Item(id, "abc", "m1", null, null, ms(started), ms(started), "v", "{}")
                db.insert("answers", null, ContentValues().apply {
                    put("id", id); put("study", tag("study", "abc")); put("module", tag("module", "m1"))
                    put("prompt", tag("prompt", "self"))
                    put("day", tag("day", Instant.ofEpochMilli(item.completed).atZone(ZoneOffset.UTC).toLocalDate().toString()))
                    put("body", sealer.seal(item.toJson().toString(), "queue-row:$id"))
                })
            }
            db.version = 1
        }
        val s = study("Europe/Berlin")
        assertEquals(2, AnswerQueue(context, sealer).today(s, "m1", ms("2026-10-20T12:00:00Z")))
        assertEquals(3, AnswerQueue(context, sealer).all().size)
        val counting = Counting(sealer)
        assertEquals(2, AnswerQueue(context, counting).today(s, "m1", ms("2026-10-20T12:00:00Z")))
        assertEquals(listOf("today", "also"), counting.rows)  // the old answer is no longer opened
        QueueDatabase.closeForTest()
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            assertEquals(2, db.version)
            db.rawQuery("SELECT COUNT(*) FROM answers WHERE start_day IS NULL", null).use { it.moveToFirst(); assertEquals(0, it.getInt(0)) }
        }
    }

    /** A row that cannot be opened during the backfill keeps no tag and is still offered to the count. */
    @Test fun aRowWithoutTagIsStillCounted() {
        val q = AnswerQueue(context, sealer)
        self(q, "one", "2026-10-20T10:00:00Z")
        QueueDatabase.closeForTest()
        SQLiteDatabase.openDatabase(context.getDatabasePath("answers.db").path, null, SQLiteDatabase.OPEN_READWRITE).use { db ->
            db.execSQL("UPDATE answers SET start_day=NULL")
        }
        assertEquals(1, AnswerQueue(context, sealer).today(study("Europe/Berlin"), "m1", ms("2026-10-20T12:00:00Z")))
    }
}
