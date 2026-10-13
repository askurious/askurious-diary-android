package de.askurious.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The licence page without the hard line breaks of the text files (audit A24). */
class LicenceTextTest {
    @Test
    fun linesOfAParagraphJoinParagraphsStay() {
        val text = "   1. Definitions.\n\n      \"License\" shall mean the terms\n      and conditions.\r\n\r\n  Next one\n"
        assertEquals("1. Definitions.\n\n\"License\" shall mean the terms and conditions.\n\nNext one", unwrapped(text))
    }

    @Test
    fun blankLinesWithSpacesSeparateToo() {
        assertEquals("a b\n\nc", unwrapped("a\nb\n   \nc"))
    }
}
