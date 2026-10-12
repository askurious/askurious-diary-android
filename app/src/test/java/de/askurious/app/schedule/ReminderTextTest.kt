package de.askurious.app.schedule

import org.junit.Assert.assertEquals
import org.junit.Test

/** The text of a prompt's notification (audit A26): the reminder keeps the researchers' text. */
class ReminderTextTest {
    private fun text(notify: String, reminder: Boolean) =
        Alarms.reminderText(notify, reminder, if (reminder) "Reminder: a short question is still waiting" else "A short question is waiting") { "Reminder: $it" }

    @Test
    fun theResearchersTextAndItsReminder() {
        assertEquals("How are you right now?", text("How are you right now?", reminder = false))
        assertEquals("Reminder: How are you right now?", text("How are you right now?", reminder = true))
    }

    @Test
    fun withoutATextTheAppsOwn() {
        assertEquals("A short question is waiting", text("", reminder = false))
        assertEquals("Reminder: a short question is still waiting", text("  ", reminder = true))
    }
}
