package de.askurious.app.ui

import de.askurious.app.data.Study
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The ids of the home-screen shortcuts and which of them lead nowhere any more (Shortcuts). */
class ShortcutsTest {
    private fun study(code: String, status: String, leavePending: Boolean = false) = Study(
        code = code, secret = "s", title = "T", status = status, firstDay = null, lastDay = null, modules = emptyList(),
        leavePending = leavePending,
    )

    @Test
    fun theIdNamesTheStudy() {
        assertEquals("abc", Shortcuts.codeOf(Shortcuts.id("abc", "m1")))
        assertNull(Shortcuts.codeOf("something|else"))
        assertNull(Shortcuts.codeOf("report||m1"))
    }

    @Test
    fun shortcutsOfStudiesGoneOrOverAreStale() {
        val ids = listOf(
            Shortcuts.id("run", "m1"), Shortcuts.id("pause", "m1"), Shortcuts.id("left", "m1"),
            Shortcuts.id("leaving", "m1"), Shortcuts.id("over", "m1"), Shortcuts.id("gone", "m1"), "other",
        )
        val studies = listOf(
            study("run", "active"), study("pause", "paused"), study("left", "left"),
            study("leaving", "active", leavePending = true), study("over", "finished"),
        )
        assertEquals(
            listOf(Shortcuts.id("left", "m1"), Shortcuts.id("leaving", "m1"), Shortcuts.id("over", "m1"), Shortcuts.id("gone", "m1")),
            Shortcuts.stale(ids, studies),
        )
    }
}
