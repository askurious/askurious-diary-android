package de.askurious.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The notice for notifications: the dialog while it can still come, the settings once it cannot (audit A2). */
class NotifyNoticeTest {
    @Test
    fun noNoticeWhileNotificationsArrive() {
        assertEquals(NotifyNotice.NONE, notifyNotice(enabled = true, canAsk = true, granted = true, asked = true, rationale = false))
        assertEquals(NotifyNotice.NONE, notifyNotice(enabled = true, canAsk = false, granted = true, asked = false, rationale = false))
    }

    @Test
    fun theDialogBeforeTheFirstAskAndAfterOneDenial() {
        // never asked: no rationale yet, the dialog comes
        assertEquals(NotifyNotice.ASK, notifyNotice(enabled = false, canAsk = true, granted = false, asked = false, rationale = false))
        // denied once: the system asks for a rationale, the dialog still comes
        assertEquals(NotifyNotice.ASK, notifyNotice(enabled = false, canAsk = true, granted = false, asked = true, rationale = true))
    }

    @Test
    fun theSettingsOnceTheDialogCannotHelp() {
        // denied for good: asked before, no rationale any more
        assertEquals(NotifyNotice.SETTINGS, notifyNotice(enabled = false, canAsk = true, granted = false, asked = true, rationale = false))
        // granted, but the app's switch or the channel is off
        assertEquals(NotifyNotice.SETTINGS, notifyNotice(enabled = false, canAsk = true, granted = true, asked = true, rationale = false))
        // before Android 13 there is no dialog: only the switch in the settings
        assertEquals(NotifyNotice.SETTINGS, notifyNotice(enabled = false, canAsk = false, granted = true, asked = false, rationale = false))
    }
}
