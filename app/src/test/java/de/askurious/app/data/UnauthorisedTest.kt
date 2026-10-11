package de.askurious.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A 401 ends a study only when it keeps coming for more than three days (Study.unauthorised, Sync.one). */
class UnauthorisedTest {
    private val hour = 3600_000L
    private val study = Study(
        code = "abc", secret = "s", title = "T", status = "active",
        firstDay = null, lastDay = null, modules = emptyList(),
    )

    @Test
    fun theFirst401OnlyNotesTheTime() {
        val s = study.unauthorised(1_000L)
        assertEquals("active", s.status)
        assertEquals(1_000L, s.unauthorisedSince)
    }

    @Test
    fun repeated401sWithinThreeDaysKeepTheStudyAndTheFirstTime() {
        val s = study.unauthorised(0L).unauthorised(24 * hour).unauthorised(72 * hour)
        assertEquals("active", s.status)
        assertEquals(0L, s.unauthorisedSince)
    }

    @Test
    fun a401AfterMoreThanThreeDaysEndsTheStudy() {
        val s = study.unauthorised(0L).unauthorised(72 * hour + 1)
        assertEquals("ended", s.status)
    }

    @Test
    fun aFreshStudyFromTheServerStartsWithout401() {
        // Sync.one replaces the stored study by the server's answer, which never carries the field
        assertNull(study.copy(title = "new").unauthorisedSince)
        val s = study.unauthorised(0L)
        assertEquals(study, s.copy(unauthorisedSince = null))
    }
}
