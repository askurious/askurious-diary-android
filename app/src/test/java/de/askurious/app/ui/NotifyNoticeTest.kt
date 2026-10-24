package de.askurious.app.ui

import de.askurious.app.data.Study
import de.askurious.app.schedule.Alarms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /**
     * A notification category switched off counts only for the studies that use it (UX audit 2026-10-24, AND-02): a
     * study without sound uses the silent one, so switching off the one with sound leaves it alone, and the other way.
     */
    @Test
    fun aChannelSwitchedOffWarnsOnlyTheStudiesThatUseIt() {
        val loud = Study(code = "a", secret = "s", title = "A", status = "active", firstDay = null, lastDay = null, modules = emptyList(), sound = true)
        val quiet = loud.copy(code = "b", sound = false)
        val soundOff = channelsOff(mapOf(Alarms.CHANNEL to 0, Alarms.CHANNEL_SILENT to 4))
        assertEquals(setOf(Alarms.CHANNEL), soundOff)
        assertTrue(studyChannelOff(loud, soundOff))
        assertFalse(studyChannelOff(quiet, soundOff))
        val silentOff = channelsOff(mapOf(Alarms.CHANNEL to 3, Alarms.CHANNEL_SILENT to 0))
        assertFalse(studyChannelOff(loud, silentOff))
        assertTrue(studyChannelOff(quiet, silentOff))
        // a channel that does not exist (yet) is not switched off
        assertEquals(emptySet<String>(), channelsOff(mapOf(Alarms.CHANNEL to null, Alarms.CHANNEL_SILENT to null)))
    }
}
