package de.askurious.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

/** The address of an entry: a release build opens only its own server (Api.absolute, Api.sameOrigin). */
class ApiTest {
    private val base = "https://ask.askurious.de"

    @Test
    fun aPathGoesUnderTheBase() {
        assertEquals("$base/p/abc?t=1", Api.absolute("/p/abc?t=1", base, debug = false))
    }

    @Test
    fun aReleaseBuildTakesOnlyItsOwnServer() {
        assertEquals("$base/p/abc", Api.absolute("$base/p/abc", base, debug = false))
        assertEquals("https://ASK.askurious.de:443/p/abc", Api.absolute("https://ASK.askurious.de:443/p/abc", base, debug = false))
        for (other in listOf(
            "https://evil.example/p/abc",
            "http://ask.askurious.de/p/abc",
            "https://ask.askurious.de:8443/p/abc",
            "https://ask.askurious.de.evil.example/p/abc",
            "//evil.example/p/abc",
            "javascript:alert(1)",
        )) {
            assertThrows(other, ApiError::class.java) { Api.absolute(other, base, debug = false) }
        }
    }

    @Test
    fun aDebugBuildKeepsOnlyThePath() {
        assertEquals("http://localhost:5060/p/abc?t=1", Api.absolute("http://192.168.1.5:5000/p/abc?t=1", "http://localhost:5060", debug = true))
    }

    @Test
    fun sameOriginComparesSchemeHostAndPort() {
        assertTrue(Api.sameOrigin(URI("https://ask.askurious.de/"), URI(base)))
        assertTrue(Api.sameOrigin(URI("http://localhost:5060/x"), URI("http://localhost:5060")))
        assertFalse(Api.sameOrigin(URI("http://localhost:5061/x"), URI("http://localhost:5060")))
        assertFalse(Api.sameOrigin(URI("about:blank"), URI(base)))
        assertFalse(Api.sameOrigin(URI("data:text/html,x"), URI(base)))
    }

    /** An answer above the limit is an error, not a string held in memory (audit 2026-10-28, V04). */
    @Test
    fun anAnswerIsReadUpToItsLimit() {
        assertEquals("{\"ok\":1}", Api.readCapped("{\"ok\":1}".byteInputStream(), 100, 200))
        assertEquals("äöü", Api.readCapped("äöü".toByteArray().inputStream(), 6, 200))
        val e = assertThrows(ApiError::class.java) { Api.readCapped(ByteArray(101).inputStream(), 100, 200) }
        assertEquals(200, e.status)
    }
}
