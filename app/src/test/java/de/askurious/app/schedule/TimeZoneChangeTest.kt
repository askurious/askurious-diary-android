package de.askurious.app.schedule

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A change of time zone or of the clock starts a sync besides setting the alarm anew (audit 2026-10-18, AND-07): the
 * server learns the new zone at once and sends the schedule in it, instead of the prompts following the old zone until
 * the next sync, hours later.
 */
class TimeZoneChangeTest {
    @Test
    fun aNewTimeZoneOrClockStartsASync() {
        assertTrue(BootReceiver.syncs("android.intent.action.TIMEZONE_CHANGED"))
        assertTrue(BootReceiver.syncs("android.intent.action.TIME_SET"))
    }

    @Test
    fun aRestartOrAnUpdateOnlySetsTheAlarm() {
        assertFalse(BootReceiver.syncs("android.intent.action.BOOT_COMPLETED"))
        assertFalse(BootReceiver.syncs("android.intent.action.MY_PACKAGE_REPLACED"))
        assertFalse(BootReceiver.syncs(null))
    }
}
