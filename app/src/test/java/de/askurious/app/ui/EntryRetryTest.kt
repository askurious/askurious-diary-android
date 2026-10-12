package de.askurious.app.ui

import de.askurious.app.data.ApiError
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Try again" on the error screen of an entry only where it can help (audit A8). */
class EntryRetryTest {
    @Test
    fun noConnectionRateLimitAndFaultsMayPass() {
        assertTrue(EntryActivity.retryable(ApiError("offline")))
        assertTrue(EntryActivity.retryable(ApiError("limit", 429)))
        assertTrue(EntryActivity.retryable(ApiError("error", 502)))
        // an answer the app could not read
        assertTrue(EntryActivity.retryable(ApiError("error")))
    }

    @Test
    fun finalAnswersOfferOnlyClosing() {
        assertFalse(EntryActivity.retryable(ApiError("expired", 410)))
        assertFalse(EntryActivity.retryable(ApiError("daily_limit", 409)))
        assertFalse(EntryActivity.retryable(ApiError("paused", 409)))
    }
}
