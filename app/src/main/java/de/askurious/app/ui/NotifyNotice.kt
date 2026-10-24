package de.askurious.app.ui

import de.askurious.app.data.Study
import de.askurious.app.schedule.Alarms

/** What the notice on the start page offers when notifications do not reach the participant. */
enum class NotifyNotice {
    /** Notifications arrive: no notice. */
    NONE,

    /** The system dialog can still ask for the permission. */
    ASK,

    /** Only the app's notification settings can help: the dialog will not come again, or it cannot help. */
    SETTINGS,
}

/**
 * The notice for notifications. [enabled]: they reach the participant (the permission and the app's switch; a channel
 * switched off counts per study, [studyChannelOff]). [canAsk]: Android 13 or later, where the permission has a
 * dialog. [granted]: the permission is granted (then the switch is off, which no dialog changes). [asked]: the app
 * asked once before.
 * [rationale]: the system's shouldShowRequestPermissionRationale. Asked once and no rationale any more means denied
 * for good: the system shows no dialog then, the request returns at once. Plain Kotlin, so the unit tests can run it.
 */
fun notifyNotice(enabled: Boolean, canAsk: Boolean, granted: Boolean, asked: Boolean, rationale: Boolean): NotifyNotice = when {
    enabled -> NotifyNotice.NONE
    !canAsk || granted -> NotifyNotice.SETTINGS
    asked && !rationale -> NotifyNotice.SETTINGS
    else -> NotifyNotice.ASK
}

/**
 * The channels switched off among [importance] (channel id to its importance, null for a channel that does not exist):
 * importance "none" (0, NotificationManagerCompat.IMPORTANCE_NONE) is a channel the participant switched off.
 */
fun channelsOff(importance: Map<String, Int?>): Set<String> = importance.filterValues { it == 0 }.keys

/**
 * Whether the notifications of [study] are switched off by its own channel ([off] from [channelsOff]): a study with
 * sound uses Alarms.CHANNEL, one without Alarms.CHANNEL_SILENT (Alarms.channelOf), so only the studies of the channel
 * switched off warn.
 */
fun studyChannelOff(study: Study, off: Set<String>): Boolean = Alarms.channelOf(study) in off
