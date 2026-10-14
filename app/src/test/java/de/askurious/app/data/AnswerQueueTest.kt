package de.askurious.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What becomes of a queued answer after the server's answer to POST /offline (AnswerQueue.fate, Sync.send). */
class AnswerQueueTest {
    private val item = AnswerQueue.Item(
        "0b9f0c6e-6f2a-4d7e-9a43-6f0f6f1d2c3b", "abc", "m1", null, null, started = 0L, completed = 1L, version = "v", form = "{}",
    )

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
    fun refusalsForGoodPreserveTheAnswer() {
        assertEquals(AnswerQueue.Fate.REVIEW, AnswerQueue.fate(ApiError("done", 409)))
        assertEquals(AnswerQueue.Fate.REVIEW, AnswerQueue.fate(ApiError("expired", 410)))
        assertEquals(AnswerQueue.Fate.REVIEW, AnswerQueue.fate(ApiError("unknown", 400)))
        assertEquals(AnswerQueue.Fate.REVIEW, AnswerQueue.fate(ApiError("ended", 409)))
    }

    @Test
    fun theDailyLimitNeedsClarification() {
        // Refusal is retained for clarification; not an immediate network retry.
        assertEquals(AnswerQueue.Fate.LIMIT, AnswerQueue.fate(ApiError("daily_limit", 409)))
        assertFalse(ApiError("daily_limit", 409).transient)
    }

    @Test
    fun a401OrAPauseLetsTheAnswerWait() {
        assertEquals(AnswerQueue.Fate.WAIT, AnswerQueue.fate(ApiError("unauthorised", 401)))
        assertEquals(AnswerQueue.Fate.WAIT, AnswerQueue.fate(ApiError("paused", 409)))
    }

    @Test
    fun aRefusalThatMayPassKeepsTheAnswerIndefinitely() {
        val first = AnswerQueue.afterRefusal(item, 1_000L)
        assertEquals(1_000L, first?.refusedSince)
        // later refusals keep the time of the first one
        assertEquals(first, AnswerQueue.afterRefusal(first!!, 1_000L + Study.UNAUTHORISED_GRACE_MS))
        assertEquals(first, AnswerQueue.afterRefusal(first, 1_000L + Study.UNAUTHORISED_GRACE_MS + 1))
    }

    @Test
    fun aWrongClockOfThePhoneKeepsTheAnswer() {
        val minute = 60_000L
        assertEquals(AnswerQueue.Fate.CLOCK, AnswerQueue.fate(ApiError("unknown", 400), skew = 6 * minute))
        assertEquals(AnswerQueue.Fate.CLOCK, AnswerQueue.fate(ApiError("unknown", 400), skew = -6 * minute))
        assertEquals(AnswerQueue.Fate.CLOCK, AnswerQueue.fate(ApiError("clock", 400)))
        // a clock that is right: the 400 means what it says
        assertEquals(AnswerQueue.Fate.REVIEW, AnswerQueue.fate(ApiError("unknown", 400), skew = 2 * minute))
        assertEquals(AnswerQueue.Fate.REVIEW, AnswerQueue.fate(ApiError("unknown", 400), skew = null))
    }

    @Test
    fun aChangedQuestionnaireNeedsClarification() {
        assertEquals(AnswerQueue.Fate.CHANGED, AnswerQueue.fate(ApiError("changed", 409)))
    }

    @Test
    fun answersOlderThanSevenDaysAreTooOld() {
        assertFalse(AnswerQueue.tooOld(item, AnswerQueue.MAX_AGE_MS))
        assertTrue(AnswerQueue.tooOld(item, AnswerQueue.MAX_AGE_MS + 1))
    }

    @Test
    fun onlyDurableStorageRepliesAcknowledgeTheAnswer() {
        for (status in listOf("stored", "review")) assertTrue(AnswerQueue.acknowledged(JSONObject().put("status", status)))
        for (status in listOf("active", "completed", "", "ok")) assertFalse(AnswerQueue.acknowledged(JSONObject().put("status", status)))
        assertFalse(AnswerQueue.acknowledged(JSONObject()))
    }

    @Test
    fun everyAnswerGoesWithItsUuid() {
        // the server stores an answer sent twice only once (POST /offline, "uuid")
        val body = item.body()
        assertEquals(item.id, body.getString("uuid"))
        assertEquals(36, body.getString("uuid").length)
        assertFalse(body.has("study"))
        assertFalse(body.has("refused_since"))
    }

    @Test
    fun theStoredAnswerKeepsItsIdAndTheFirstRefusal() {
        val waiting = item.copy(refusedSince = 5L, refusal = "changed")
        assertEquals(waiting, AnswerQueue.Item.fromJson(JSONObject(waiting.toJson().toString())))
        assertEquals(item, AnswerQueue.Item.fromJson(JSONObject(item.toJson().toString())))
    }
}
