package de.askurious.app.data

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class AnswerQueueDurabilityTest {
    private fun add(queue: AnswerQueue) = queue.add("abc", "m1", null, null, 1L, 2L, "v", "{\"answer\":[\"original\"]}")

    @Test fun savedAnswerSurvivesANewProcess() {
        val prefs = FakePrefs()
        val item = add(AnswerQueue(prefs, FakeSealer()))
        assertEquals(1, prefs.commits)
        assertEquals(0, prefs.applies)
        val restarted = FakePrefs().apply { values.putAll(prefs.diskValues) }
        assertEquals(listOf(item), AnswerQueue(restarted, FakeSealer()).all())
    }

    @Test fun failedCommitDoesNotReportOrCacheTheNewAnswer() {
        val prefs = FakePrefs()
        val queue = AnswerQueue(prefs, FakeSealer())
        val first = add(queue)
        prefs.commitSucceeds = false
        assertThrows(IOException::class.java) { add(queue) }
        assertEquals(listOf(first), queue.all())
        val restarted = FakePrefs().apply { values.putAll(prefs.diskValues) }
        assertEquals(listOf(first), AnswerQueue(restarted, FakeSealer()).all())
        assertThrows(IOException::class.java) { queue.remove(first.id) }
        assertEquals(listOf(first), queue.all())
    }

    @Test fun encryptionFailureLeavesTheExistingAnswerUntouched() {
        val prefs = FakePrefs()
        val queue = AnswerQueue(prefs, FakeSealer())
        val first = add(queue)
        val unavailable = object : Sealer {
            override fun seal(plain: String, aad: String): String = throw java.security.GeneralSecurityException("unavailable")
            override fun open(sealed: String, aad: String): String? = FakeSealer().open(sealed, aad)
        }
        assertThrows(IOException::class.java) { add(AnswerQueue(prefs, unavailable)) }
        assertEquals(listOf(first), queue.all())
        val fresh = FakePrefs().apply { values.putAll(prefs.diskValues) }
        assertEquals(listOf(first), AnswerQueue(fresh, FakeSealer()).all())
    }

    @Test fun unreadableOrPartlyCorruptQueuesCannotBeOverwritten() {
        val malformed = AnswerQueue.Item("id", "abc", "m1", null, null, 1L, 2L, "v", "{}")
            .toJson().put("form", "not an object")
        for (raw in listOf("k1:lost", "[{\"broken\":true}]", "[$malformed]")) {
            val prefs = FakePrefs().apply { values["items"] = raw }
            val queue = AnswerQueue(prefs, FakeSealer())
            assertThrows(IOException::class.java) { add(queue) }
            assertThrows(IOException::class.java) { queue.removeStudy("abc") }
            // Legacy plain text may be encrypted on read, but its original is preserved.
            val stored = prefs.values["items"] as String
            assertEquals(raw, if (stored.startsWith("k1:queue:")) FakeSealer().open(stored, "queue") else stored)
        }
    }

    @Test fun refusalStateAndOriginalSurviveRestartAndExplicitRemoval() {
        val prefs = FakePrefs()
        val queue = AnswerQueue(prefs, FakeSealer())
        val first = add(queue)
        for (reason in listOf("changed", "daily_limit", "expired", "unauthorised", "clock")) {
            queue.recordDelivery(first, ApiError(reason, if (reason == "unauthorised") 401 else 409), now = 10L)
            val fresh = FakePrefs().apply { values.putAll(prefs.values) }
            val item = AnswerQueue(fresh, FakeSealer()).all().single()
            assertEquals(first.body().toString(), item.body().toString())
            assertEquals(reason, item.refusal)
        }
        queue.removeStudy("abc")
        val fresh = FakePrefs().apply { values.putAll(prefs.values) }
        assertTrue(AnswerQueue(fresh, FakeSealer()).all().isEmpty())
    }

    @Test fun deliveryFailuresNeverDeleteAndAcknowledgementsDo() {
        val prefs = FakePrefs()
        val queue = AnswerQueue(prefs, FakeSealer())
        val item = add(queue)
        for (error in listOf(ApiError("offline"), ApiError("limit", 429), ApiError("error", 503),
                             ApiError("done", 409), ApiError("unknown", 400), ApiError("unconfirmed", 409))) {
            queue.recordDelivery(queue.all().single(), error)
            val restarted = FakePrefs().apply { values.putAll(prefs.values) }
            assertEquals(item.body().toString(), AnswerQueue(restarted, FakeSealer()).all().single().body().toString())
        }
        queue.recordDelivery(queue.all().single(), null)
        assertTrue(queue.all().isEmpty())
    }

    @Test fun resubmissionAfterActivityRecreationUsesTheSameOriginal() {
        val prefs = FakePrefs()
        val queue = AnswerQueue(prefs, FakeSealer())
        val item = add(queue)
        assertEquals(item, queue.add("abc", "m1", null, null, 1L, 3L, "v", "{}", item.id))
        assertEquals(listOf(item), queue.all())
    }
}
