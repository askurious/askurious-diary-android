package de.askurious.app.ui

import de.askurious.app.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** The time of the next question on the card: exact only with exact alarms (audit A11). */
class PromptTimeTest {
    @Test
    fun exactAlarmsGiveTheTime() {
        assertEquals(R.string.next_prompt, nextPromptText(exact = true))
    }

    @Test
    fun withoutExactAlarmsTheTimeIsAnAbout() {
        assertEquals(R.string.next_prompt_about, nextPromptText(exact = false))
    }
}
