package de.askurious.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The join code from a QR code, a link or typed text: the hyphen of test codes must survive. */
class JoinCodeTest {
    @Test
    fun typedCodesKeepTheirHyphenAndLoseSpaces() {
        assertEquals("k7qm2x", joinCode("k7qm2x"))
        assertEquals("k7qm2x-t3f9a", joinCode("k7qm2x-t3f9a"))
        assertEquals("k7qm2x-t3f9a", joinCode("  k7qm 2x-t3f9a \n"))
        assertEquals("k7qm2x-t3f9a", joinCode("K7QM2X-T3F9A"))
    }

    @Test
    fun joinLinksOfTheParticipantsAddress() {
        assertEquals("k7qm2x", joinCode("https://ask.askurious.de/j/k7qm2x"))
        assertEquals("k7qm2x-t3f9a", joinCode("https://ask.askurious.de/j/k7qm2x-t3f9a"))
        assertEquals("k7qm2x-t3f9a", joinCode("https://ask.askurious.de/j/k7qm2x-t3f9a/"))
        assertEquals("k7qm2x-t3f9a", joinCode("https://ask.askurious.de/j/k7qm2x-t3f9a?src=qr"))
        assertEquals("k7qm2x", joinCode("http://localhost:5060/j/k7qm2x"))
    }

    @Test
    fun appLinks() {
        assertEquals("k7qm2x", joinCode("askurious://join/k7qm2x"))
        assertEquals("k7qm2x-t3f9a", joinCode("askurious://join/k7qm2x-t3f9a"))
    }

    @Test
    fun nothingToJoin() {
        assertNull(joinCode(""))
        assertNull(joinCode("   "))
        assertNull(joinCode("https://ask.askurious.de/j/"))
    }
}
