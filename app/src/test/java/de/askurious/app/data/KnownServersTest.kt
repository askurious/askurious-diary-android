package de.askurious.app.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Audit 2026-10-13, AN-1: a join link names its server, but only a server of Askurious or of one of its brands is
 * asked anything; any other is refused before the app talks to it.
 */
class KnownServersTest {
    private val release = Api.servers("https://askurious.de, https://ask.askurious.de,https://polltergeist.de,https://ask.polltergeist.de")

    @Test
    fun theServersOfAskuriousAndItsBrandsAreKnown() {
        for (s in listOf("https://askurious.de", "https://ask.askurious.de", "https://ASK.askurious.de:443", "https://ask.polltergeist.de")) {
            assertTrue(s, Api.isKnown(s, release))
        }
    }

    @Test
    fun anyOtherServerIsNot() {
        for (s in listOf(
            "https://askurious-studie.example", "https://ask.askurious.de.example.org", "https://evil.askurious.de",
            "http://ask.askurious.de", "https://ask.askurious.de:8443", "http://localhost:5080", "ftp://askurious.de", "",
        )) {
            assertFalse(s, Api.isKnown(s, release))
        }
    }

    @Test
    fun theBuildsListTheirServers() {
        // the unit tests run the debug build: the servers of Askurious and the Flask server on the computer
        assertTrue(Api.isKnown("https://ask.askurious.de"))
        assertTrue(Api.isKnown("https://polltergeist.de"))
        assertTrue(Api.isKnown("http://localhost:5080"))
        assertTrue(Api.isKnown(Api.defaultServer))
        assertFalse(Api.isKnown("https://askurious-studie.example"))
    }

    @Test
    fun aForeignServerIsRefusedBeforeItIsAsked() {
        val e = runCatching { runBlocking { Api.join("https://askurious-studie.example", "abc12") } }.exceptionOrNull()
        assertEquals(Api.FOREIGN, (e as ApiError).key)
        val f = runCatching { runBlocking { Api.enrol("https://askurious-studie.example", "abc12") } }.exceptionOrNull()
        assertEquals(Api.FOREIGN, (f as ApiError).key)
    }

    @Test
    fun originsAreNormalised() {
        assertEquals("https://ask.askurious.de", Api.origin("https://Ask.Askurious.de:443/j/abc"))
        assertEquals("http://localhost:5080", Api.origin("http://localhost:5080/"))
        assertNull(Api.origin("askurious://join/abc"))
        assertNull(Api.origin("not a url"))
    }
}
