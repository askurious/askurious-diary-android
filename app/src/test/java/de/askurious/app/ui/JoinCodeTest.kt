package de.askurious.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /**
     * Audit 2026-10-18, AND-03: "Join study" opened an entry whose deep-link intent holds the route's internal address
     * (android-app://androidx.navigation/...); taken as the join link it filled the field and was looked up at once.
     * Only a real join link of a deep link counts, else the route's link (empty from the menu, a foreign link as given).
     */
    @Test
    fun theJoinScreenStartsEmptyUnlessAJoinLinkOpenedIt() {
        val internal = "android-app://androidx.navigation/de.askurious.app.ui.route.join?link={link}"
        assertEquals("", joinLinkOf(internal, ""))
        assertEquals("", joinLinkOf(null, ""))
        assertEquals("https://ask.askurious.de/j/k7qm2x", joinLinkOf("https://ask.askurious.de/j/k7qm2x", ""))
        assertEquals("askurious://join/k7qm2x", joinLinkOf("askurious://join/k7qm2x", ""))
        // a link the deep links do not take came as the route's link: the screen shows "foreign server" for it
        assertEquals("https://evil.example/j/8trq2d", joinLinkOf(internal, "https://evil.example/j/8trq2d"))
        // no join link: another page of the server, an empty custom link
        assertEquals("", joinLinkOf("https://ask.askurious.de/s/k7qm2x", ""))
        assertEquals("", joinLinkOf("askurious://join/", ""))
    }

    /**
     * Audit 2026-10-22, F-02: the activity took the data of any intent as a join link (another app could send
     * android-app://..., content://... or any text), and the join screen looked it up at once. Only join links count.
     */
    @Test
    fun onlyAJoinLinkOpensTheJoinScreenFromAnIntent() {
        assertTrue(isJoinLink("askurious://join/k7qm2x"))
        assertTrue(isJoinLink("https://ask.askurious.de/j/k7qm2x"))
        for (other in listOf("android-app://androidx.navigation/de.askurious.app.ui.Route.About", "content://x/y", "x",
                             "", "https://ask.askurious.de/s/k7qm2x", "askurious://join/")) {
            assertFalse(other, isJoinLink(other))
        }
    }
}
