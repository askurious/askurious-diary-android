package de.askurious.app.ui

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
 * The notice for notifications. [enabled]: they reach the participant (permission, the app's switch and the channel
 * of the prompts). [canAsk]: Android 13 or later, where the permission has a dialog. [granted]: the permission is
 * granted (then the switch or the channel is off, which no dialog changes). [asked]: the app asked once before.
 * [rationale]: the system's shouldShowRequestPermissionRationale. Asked once and no rationale any more means denied
 * for good: the system shows no dialog then, the request returns at once. Plain Kotlin, so the unit tests can run it.
 */
fun notifyNotice(enabled: Boolean, canAsk: Boolean, granted: Boolean, asked: Boolean, rationale: Boolean): NotifyNotice = when {
    enabled -> NotifyNotice.NONE
    !canAsk || granted -> NotifyNotice.SETTINGS
    asked && !rationale -> NotifyNotice.SETTINGS
    else -> NotifyNotice.ASK
}
