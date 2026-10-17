package de.askurious.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The deep links of the join screen (JOIN_LINKS) give the code and the server that joinCode and joinServer read. */
class RoutesTest {
    private fun link(pattern: String) = pattern.replace("{code}", "k7qm2x-t3f9a")

    @Test
    fun everyJoinLinkGivesItsCode() {
        for (pattern in JOIN_LINKS) assertEquals(pattern, "k7qm2x-t3f9a", joinCode(link(pattern)))
    }

    @Test
    fun theAppLinkNamesTheParticipantsAddressTheSchemeLinkNone() {
        assertEquals("https://ask.askurious.de", joinServer(link(JOIN_LINKS[0]), allowHttp = false))
        assertNull(joinServer(link(JOIN_LINKS[1]), allowHttp = false))
    }
}
