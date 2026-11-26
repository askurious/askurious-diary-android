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
    }

    /**
     * Security audit 2026-11-26, SEC-09: the app's own scheme is gone. It is not verified as the App Links are, so
     * another app could register it too and take the invitation codes sent with it; the server never wrote such links.
     */
    @Test
    fun theAppsOwnSchemeIsNoJoinLinkAnyMore() {
        assertNull(joinCode("askurious://join/k7qm2x"))
        assertNull(joinCode("askurious://join/k7qm2x-t3f9a"))
        assertFalse(isJoinLink("askurious://join/k7qm2x"))
        assertNull(scannedJoinCode("askurious://join/k7qm2x-a8c3d9ef"))
        assertEquals("", joinLinkOf("askurious://join/k7qm2x", ""))
        assertTrue(JOIN_LINKS.none { it.startsWith("askurious") })
    }

    /**
     * SEC-09: the code of a link is one as the server makes them, or the link is no join link. "https://<host>/j/.."
     * named another address of the server (GET /api/diary/v1/join/..), and a text of any length was looked up at once.
     */
    @Test
    fun theCodeOfALinkIsACodeOrTheLinkIsNone() {
        for (link in listOf(
            "https://askurious.de/j/..", "https://askurious.de/j/%2e%2e", "https://askurious.de/j/.", "https://askurious.de/j/a_b",
            "https://askurious.de/j/a-b-c", "https://askurious.de/j/" + "a".repeat(33),
        )) {
            assertNull(link, joinCode(link))
            assertNull(link, joinServer(link, allowHttp = true))
            assertFalse(link, isJoinLink(link))
            assertNull(link, scannedJoinCode(link))
        }
        // capitals in a link are the same code
        assertEquals("k7qm2x", joinCode("https://askurious.de/j/K7QM2X"))
        assertEquals("https://askurious.de", joinServer("https://askurious.de/j/K7QM2X", allowHttp = false))
    }

    @Test
    fun nothingToJoin() {
        assertNull(joinCode(""))
        assertNull(joinCode("   "))
        assertNull(joinCode("https://ask.askurious.de/j/"))
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
        // a typed code means the app's own server
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
        assertTrue(isJoinLink("https://ask.askurious.de/j/k7qm2x"))
        for (other in listOf("android-app://androidx.navigation/de.askurious.app.ui.Route.About", "content://x/y", "x",
                             "", "https://ask.askurious.de/s/k7qm2x", "askurious://join/")) {
            assertFalse(other, isJoinLink(other))
        }
    }

    /**
     * A scanned QR code (UX audit 2026-10-24, AND-06): a join link or a code alone gives its join code; any other link
     * or text gives none, so the join screen says so instead of doing nothing or looking up a part of it.
     */
    @Test
    fun aScannedQrCodeWithoutAJoinCodeIsNoJoinCode() {
        assertEquals("k7qm2x", scannedJoinCode("https://askurious.de/j/k7qm2x"))
        assertEquals("k7qm2x-a8c3d9ef", scannedJoinCode("https://askurious.de/j/k7qm2x-a8c3d9ef"))
        assertEquals("k7qm2x-t3f9a", scannedJoinCode(" K7QM2X-T3F9A "))
        // a link of another server with /j/ stays a join link: the join screen refuses that server by name
        assertEquals("k7qm2x", scannedJoinCode("https://example.org/j/k7qm2x"))
        assertNull(scannedJoinCode("https://askurious.de/"))
        assertNull(scannedJoinCode("https://askurious.de/s/k7qm2x"))
        assertNull(scannedJoinCode("https://example.org/menu"))
        assertNull(scannedJoinCode("askurious://join"))
        assertNull(scannedJoinCode("WIFI:S:Lab;T:WPA;P:secret;;"))
        assertNull(scannedJoinCode("mailto:team@example.org"))
        assertNull(scannedJoinCode("Hello, world!"))
        assertNull(scannedJoinCode("k7qm2x--t3f9a"))
        assertNull(scannedJoinCode(""))
    }
}
