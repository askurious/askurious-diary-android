package de.askurious.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** What a sync stores when the participant acted meanwhile (Sync.merged), and when bringing the app up syncs (Sync.due). */
class SyncTest {
    private val stored = Study(
        code = "abc", secret = "s", title = "T", status = "active",
        firstDay = null, lastDay = null, modules = emptyList(),
    )
    private val fromServer = stored.copy(title = "T2")

    @Test
    fun theServersAnswerIsStoredWhenNothingChanged() {
        assertEquals(fromServer, Sync.merged(fromServer, stored, null))
    }

    @Test
    fun aLeaveDuringTheSyncIsNotOverwritten() {
        val left = stored.copy(status = "left", leavePending = true)
        val merged = Sync.merged(fromServer, left, null)!!
        assertEquals("left", merged.status)
        assertTrue(merged.leavePending)
        assertEquals("T2", merged.title)
        // told to the server already: stays left, nothing pending
        val told = Sync.merged(fromServer, stored.copy(status = "left"), null)!!
        assertEquals("left", told.status)
        assertFalse(told.leavePending)
    }

    @Test
    fun quietHoursChangedAgainStayPending() {
        val sent = QuietFrom(LocalDate.of(2026, 10, 20), Quiet("22:00", "07:00"))
        val again = QuietFrom(LocalDate.of(2026, 10, 20), null)
        assertEquals(again, Sync.merged(fromServer, stored.copy(quietPending = again), sent)!!.quietPending)
        // the sent change itself is the server's now
        assertNull(Sync.merged(fromServer, stored.copy(quietPending = sent), sent)!!.quietPending)
    }

    @Test
    fun nothingIsStoredForAStudyGoneOrJoinedAgain() {
        assertNull(Sync.merged(fromServer, null, null))
        // joined again meanwhile: the answer for the old secret must not replace the new participation
        assertNull(Sync.merged(fromServer, stored.copy(secret = "new"), null))
    }

    @Test
    fun bringingTheAppUpSyncsAtMostEveryFiveMinutes() {
        val gap = Sync.RESUME_GAP_MS
        assertTrue(Sync.due(last = 0L, now = 1_000L, running = false))
        assertFalse(Sync.due(last = 1_000L, now = 1_000L + gap - 1, running = false))
        assertTrue(Sync.due(last = 1_000L, now = 1_000L + gap, running = false))
        // never a second one while one runs
        assertFalse(Sync.due(last = 0L, now = 1_000L, running = true))
        // the clock set back: counts as long ago
        assertTrue(Sync.due(last = 10_000L, now = 1_000L, running = false))
    }
}
