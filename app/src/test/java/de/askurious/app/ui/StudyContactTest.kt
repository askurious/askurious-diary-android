package de.askurious.app.ui

import org.junit.Assert.*
import org.junit.Test

class StudyContactTest {
    @Test fun recognisesContactsWithoutSendingAnything() {
        assertEquals("mailto:team@example.org", contactUri("team@example.org"))
        assertEquals("tel:+49241123456", contactUri("+49 (241) 123456"))
        assertEquals("https://example.org/contact", contactUri("https://example.org/contact"))
        assertEquals("mailto:team@example.org", contactUri("mailto:team@example.org"))
    }
    @Test fun arbitrarySchemesAndFreeTextOnlyOfferCopy() {
        for (text in listOf("javascript:alert(1)", "intent://example", "file:///private", "Team, room 12", "https://", "https://user:secret@example.org", "mailto:team@example.org?body=private")) {
            assertNull(text, contactUri(text))
        }
    }
}
