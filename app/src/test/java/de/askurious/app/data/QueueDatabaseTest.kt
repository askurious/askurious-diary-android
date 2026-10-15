package de.askurious.app.data

import android.content.Context
import java.io.IOException
import org.json.JSONArray
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = android.app.Application::class)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
class QueueDatabaseTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("queue", Context.MODE_PRIVATE)
    private val sealer = FakeSealer()
    private fun queue() = AnswerQueue(context, sealer)
    private fun add(id: String, study: String = "abc", due: Long? = null) =
        queue().add(study, "m1", due, null, 1L, 2L, "v", "{\"answer\":[\"original\"]}", id)

    @Before fun reset() {
        QueueDatabase.closeForTest(); context.deleteDatabase("answers.db"); prefs.edit().clear().commit()
        SealedText(prefs, "items", sealer, "queue").remove(durable = true)
    }
    @After fun close() { QueueDatabase.closeForTest() }

    /** Abgleich 2026-10-15, T07-N-2: an unreadable queue no longer blocks removing a study, and can be discarded. */
    @Test fun anUnreadableQueueLetsStudiesGoAndCanBeDiscarded() {
        add("one", study = "abc")
        add("two", study = "xyz")
        QueueDatabase.closeForTest()
        val lost = object : Sealer {  // the index key cannot be opened any more (lost Keystore key)
            override fun seal(plain: String, aad: String) = sealer.seal(plain, aad)
            override fun open(sealed: String, aad: String): String? = null
        }
        val broken = AnswerQueue(context, lost)
        assertThrows(IOException::class.java) { broken.all() }
        assertFalse(broken.forgetStudy("abc"))  // noted, not thrown: the study can still go
        broken.discardUnreadable()
        QueueDatabase.closeForTest()
        assertTrue(queue().all().isEmpty())
        add("new", study = "xyz")  // new answers can be kept again
        assertEquals(listOf("new"), queue().all().map { it.id })
    }

    @Test fun aStudyRemovedWhileTheQueueWasUnreadableLosesItsAnswersLater() {
        add("one", study = "abc")
        add("two", study = "xyz")
        QueueDatabase.closeForTest()
        val lost = object : Sealer {
            override fun seal(plain: String, aad: String) = sealer.seal(plain, aad)
            override fun open(sealed: String, aad: String): String? = null
        }
        assertFalse(AnswerQueue(context, lost).forgetStudy("abc"))
        QueueDatabase.closeForTest()
        queue().retryForget()  // readable again: the noted study's answers go, the others stay
        assertEquals(listOf("two"), queue().all().map { it.id })
    }

    @Test fun migrationIsDurableAndRemovedAnswersNeverResurrect() {
        val item = AnswerQueue.Item("old", "abc", "m1", null, null, 1L, 2L, "v", "{}")
        val raw = sealer.seal(JSONArray(listOf(item.toJson())).toString(), "queue")
        SealedText(prefs, "items", sealer, "queue").write(JSONArray(listOf(item.toJson())).toString(), durable = true)
        assertEquals(listOf(item), queue().all())
        assertFalse(prefs.contains("items"))
        queue().remove(item.id)
        // Simulate a crash after DB commit but before removal of the legacy preference.
        prefs.edit().putString("items", raw).commit()
        QueueDatabase.closeForTest()
        assertTrue(queue().all().isEmpty())
        assertFalse(prefs.contains("items"))
    }

    @Test fun malformedLegacyTailCannotBePartiallyImported() {
        val raw = "[{\"broken\":true}]"
        SealedText(prefs, "items", sealer, "queue").write(raw, durable = true)
        assertThrows(IOException::class.java) { add("new") }
        assertEquals(raw, sealer.open(prefs.getString("items", null)!!, "queue"))
    }

    @Test fun indexedQueriesAndSingleRowWritesDoNotReencryptTheQueue() {
        var seals = 0
        var opens = 0
        val counting = object : Sealer {
            override fun seal(plain: String, aad: String): String { seals++; return sealer.seal(plain, aad) }
            override fun open(sealed: String, aad: String): String? { opens++; return sealer.open(sealed, aad) }
        }
        val q = AnswerQueue(context, counting)
        repeat(500) { q.add("abc", "m1", it.toLong(), null, 1, 2, "v", "{}", "id-$it") }
        val beforeSeals = seals
        val beforeOpens = opens
        assertTrue(q.has("abc", "m1", 499))
        q.remove("id-1")
        assertEquals(beforeSeals, seals)
        assertEquals(beforeOpens, opens)
        q.replace(q.get("id-2")!!.copy(refusal = "changed"))
        assertEquals(beforeSeals + 1, seals)
        QueueDatabase.closeForTest()
        assertEquals(499, queue().all().size)
        assertNull(queue().get("id-1"))
        assertEquals("changed", queue().get("id-2")!!.refusal)
    }

    @Test fun duplicateIdsAndStudyRemovalPreserveOtherAnswers() {
        val original = add("same")
        assertEquals(original, add("same", "other"))
        add("other", "other")
        queue().removeStudy("abc")
        assertEquals(listOf("other"), queue().all().map { it.id })
        queue().replace(original.copy(refusal = "changed"))
        assertNull(queue().get("same"))
    }

    @Test fun failedEncryptionCannotChangeExistingRows() {
        val first = add("one")
        val failing = object : Sealer {
            override fun seal(plain: String, aad: String): String = throw IOException("unavailable")
            override fun open(sealed: String, aad: String): String? = sealer.open(sealed, aad)
        }
        assertThrows(IOException::class.java) { AnswerQueue(context, failing).add("abc", "m1", null, null, 1, 2, "v", "{}", "two") }
        assertEquals(listOf(first), queue().all())
    }

    @Test fun aPartlyEncryptedMigrationRollsBackAndRetainsTheEntireSource() {
        val items = listOf("one", "two").map { AnswerQueue.Item(it, "abc", "m1", null, null, 1L, 2L, "v", "{}") }
        SealedText(prefs, "items", sealer, "queue").write(JSONArray(items.map { it.toJson() }).toString(), durable = true)
        val failing = object : Sealer {
            override fun seal(plain: String, aad: String): String {
                if (aad == "queue-row:two") throw IOException("encryption failed")
                return sealer.seal(plain, aad)
            }
            override fun open(sealed: String, aad: String): String? = sealer.open(sealed, aad)
        }
        assertThrows(IOException::class.java) { AnswerQueue(context, failing).all() }
        assertTrue(prefs.contains("items"))
        QueueDatabase.closeForTest()
        assertEquals(items, queue().all())
    }

    @Test fun corruptDatabaseIsRetainedInsteadOfSilentlyRecreated() {
        add("one")
        QueueDatabase.closeForTest()
        val file = context.getDatabasePath("answers.db")
        val corrupt = "corrupt original database".toByteArray()
        file.writeBytes(corrupt)
        assertThrows(IOException::class.java) { queue().all() }
        assertArrayEquals(corrupt, file.readBytes())
    }

    @Test fun dailyQueriesRespectDstAndOnlyDecryptNearbyDays() {
        val q = queue()
        fun time(value: String) = java.time.Instant.parse(value).toEpochMilli()
        for ((id, completed) in listOf("old" to "2026-09-01T10:00:00Z", "before" to "2026-10-24T21:59:59Z",
                "first" to "2026-10-24T22:30:00Z", "last" to "2026-10-25T22:30:00Z", "after" to "2026-10-25T23:00:00Z")) {
            q.add("abc", "m1", null, null, time(completed), time(completed), "v", "{}", id)
        }
        assertEquals(2, q.today("abc", "m1", time("2026-10-25T12:00:00Z"), java.time.ZoneId.of("Europe/Berlin")))
        assertEquals(1, q.today("abc", "m1", time("2026-10-24T12:00:00Z"), java.time.ZoneId.of("Europe/Berlin")))
        var opens = 0
        val selective = object : Sealer {
            override fun seal(plain: String, aad: String) = sealer.seal(plain, aad)
            override fun open(sealed: String, aad: String): String? {
                if (aad == "queue-row:old") fail("historical answer should not be decrypted")
                opens++
                return sealer.open(sealed, aad)
            }
        }
        assertEquals(2, AnswerQueue(context, selective).today("abc", "m1", time("2026-10-25T12:00:00Z"), java.time.ZoneId.of("Europe/Berlin")))
        assertTrue(opens < 6)
    }
}
