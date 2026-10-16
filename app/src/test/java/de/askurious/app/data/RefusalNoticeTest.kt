package de.askurious.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The notice of refused answers on the study card (UX audit 2026-10-16, B5): "Close" hides it for the answers refused
 * so far, a new refusal brings it back, and a discarded answer leaves the queue.
 */
class RefusalNoticeTest {
    private fun refused(queue: AnswerQueue, study: String): AnswerQueue.Item {
        val item = queue.add(study, "m1", null, null, 1L, 2L, "v", "{}")
        queue.replace(item.copy(refusal = "expired"))
        return item
    }

    @Test fun closingHidesTheRefusalsSoFarOnly() {
        val queue = AnswerQueue(FakePrefs(), FakeSealer())
        val first = refused(queue, "abc")
        val other = refused(queue, "xyz")
        queue.dismissRefused("abc")
        assertEquals(setOf(first.id), queue.seenRefusals())
        // a further refusal of the same study is not seen yet
        val second = refused(queue, "abc")
        assertTrue(second.id !in queue.seenRefusals())
        assertTrue(other.id !in queue.seenRefusals())
    }

    @Test fun aDiscardedAnswerLeavesTheQueueAndTheSeenList() {
        val queue = AnswerQueue(FakePrefs(), FakeSealer())
        val first = refused(queue, "abc")
        queue.dismissRefused("abc")
        queue.remove(first.id)
        assertEquals(emptyList<AnswerQueue.Item>(), queue.of("abc"))
        // ids of answers no longer kept are dropped the next time a notice is closed
        queue.dismissRefused("abc")
        assertEquals(emptySet<String>(), queue.seenRefusals())
    }
}
