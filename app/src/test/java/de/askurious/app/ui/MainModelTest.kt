package de.askurious.app.ui

import de.askurious.app.data.AnswerQueue
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The clock notice of the start page (MainModel.clockOff). */
class MainModelTest {
    @Test
    fun theClockNoticeNeedsWaitingAnswersAndAClockThatIsOff() {
        val off = AnswerQueue.MAX_SKEW_MS + 1
        assertTrue(MainModel.clockOff(1, off))
        assertTrue(MainModel.clockOff(3, -off))
        assertFalse(MainModel.clockOff(0, off))
        assertFalse(MainModel.clockOff(2, AnswerQueue.MAX_SKEW_MS))
        assertFalse(MainModel.clockOff(2, null))
    }
}
