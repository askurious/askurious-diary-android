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
    fun personalInvitationCodesKeepTheirEightCharacters() {
        assertEquals("k7qm2x-a8c3d9ef", joinCode("k7qm2x-a8c3d9ef"))
        assertEquals("k7qm2x-a8c3d9ef", joinCode(" K7QM2X-A8C3 D9EF "))
        assertEquals("k7qm2x-a8c3d9ef", joinCode("https://ask.askurious.de/j/k7qm2x-a8c3d9ef"))
        assertEquals("k7qm2x-a8c3d9ef", joinCode("askurious://join/k7qm2x-a8c3d9ef"))
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
        // the host of the custom scheme is no code
        assertNull(joinCode("askurious://join"))
        assertNull(joinCode("askurious://join/"))
    }

    @Test
    fun theServerComesFromTheJoinLink() {
        assertEquals("https://ask.askurious.de", joinServer("https://ask.askurious.de/j/k7qm2x", allowHttp = false))
        assertEquals("https://example.org:8443", joinServer("https://Example.org:8443/j/k7qm2x?x=1", allowHttp = false))
        // the default port is no part of the name
        assertEquals("https://ask.askurious.de", joinServer("https://ask.askurious.de:443/j/k7qm2x", allowHttp = false))
        // http only for a debug build (a server on the computer)
        assertNull(joinServer("http://localhost:5060/j/k7qm2x", allowHttp = false))
        assertEquals("http://localhost:5060", joinServer("http://localhost:5060/j/k7qm2x", allowHttp = true))
    }

    @Test
    fun noServerWithoutAJoinLink() {
        // a typed code and the custom scheme mean the app's own server
        assertNull(joinServer("k7qm2x", allowHttp = true))
        assertNull(joinServer("askurious://join/k7qm2x", allowHttp = true))
        // a link to another page of a server is no join link
        assertNull(joinServer("https://ask.askurious.de/s/k7qm2x", allowHttp = false))
        assertNull(joinServer("https://ask.askurious.de/j/", allowHttp = false))
        assertNull(joinServer("ftp://ask.askurious.de/j/k7qm2x", allowHttp = true))
    }
}
