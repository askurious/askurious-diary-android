package de.askurious.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What becomes of a queued answer after the server's answer to POST /offline (AnswerQueue.fate, Sync.send). */
class AnswerQueueTest {
    @Test
    fun storedAnswersLeaveTheQueue() {
        assertEquals(AnswerQueue.Fate.SENT, AnswerQueue.fate(null))
    }

    @Test
    fun noNetworkRateLimitAndServerFaultsKeepTheAnswer() {
        assertEquals(AnswerQueue.Fate.KEEP, AnswerQueue.fate(ApiError("offline")))
        assertEquals(AnswerQueue.Fate.KEEP, AnswerQueue.fate(ApiError("limit", 429)))
        assertEquals(AnswerQueue.Fate.KEEP, AnswerQueue.fate(ApiError("error", 500)))
        assertEquals(AnswerQueue.Fate.KEEP, AnswerQueue.fate(ApiError("error", 503)))
    }

    @Test
    fun refusalsForGoodDropTheAnswer() {
        assertEquals(AnswerQueue.Fate.DROP, AnswerQueue.fate(ApiError("done", 409)))
        assertEquals(AnswerQueue.Fate.DROP, AnswerQueue.fate(ApiError("expired", 410)))
        assertEquals(AnswerQueue.Fate.DROP, AnswerQueue.fate(ApiError("unknown", 400)))
        assertEquals(AnswerQueue.Fate.DROP, AnswerQueue.fate(ApiError("unauthorised", 401)))
    }

    @Test
    fun aChangedQuestionnaireDropsTheAnswerAndThePage() {
        assertEquals(AnswerQueue.Fate.CHANGED, AnswerQueue.fate(ApiError("changed", 409)))
    }

    @Test
    fun answersOlderThanSevenDaysAreTooOld() {
        val item = AnswerQueue.Item("i", "abc", "m1", null, null, started = 0L, completed = 1L, version = "v", form = "{}")
        assertFalse(AnswerQueue.tooOld(item, AnswerQueue.MAX_AGE_MS))
        assertTrue(AnswerQueue.tooOld(item, AnswerQueue.MAX_AGE_MS + 1))
    }
}
