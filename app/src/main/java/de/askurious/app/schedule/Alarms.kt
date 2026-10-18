package de.askurious.app.schedule

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import de.askurious.app.R
import de.askurious.app.data.AnswerQueue
import de.askurious.app.data.Module
import de.askurious.app.data.PromptLog
import de.askurious.app.data.Study
import de.askurious.app.data.StudyStore
import de.askurious.app.data.SyncWorker
import de.askurious.app.ui.EntryActivity
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * One alarm at a time: the earliest next prompt, reminder or exit notification over all studies
 * (Schedule.nextMoment). When it rings, PromptReceiver posts the notifications of that moment and sets the next
 * alarm. Any change (sync, joining, leaving, boot, time zone) calls [rearm], which computes the next moment again
 * from the stored studies. The alarm rings in real time; for a fast-forward person (Study.clock) the moment is the
 * real one of the virtual prompt time, and everything sent to the server is virtual.
 */
object Alarms {
    const val CHANNEL = "prompts"

    /**
     * The same notifications without a sound, for a study whose sound the participant switched off (Study.sound). A
     * channel's sound cannot change once it exists, so the choice picks the channel.
     */
    const val CHANNEL_SILENT = "prompts_silent"
    private const val EXTRA_DUE = "due"

    fun canExact(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    /**
     * Sets the one alarm anew from now. When the alarm about to be replaced was due already but has not rung yet (an
     * inexact alarm rings minutes late, audit 2026-10-18, AND-02), what it was set for is posted first
     * ([notifyRinging] from its moment, [PromptLog.armedFor]): the next one is looked for after now only. Prompts the
     * phone never set an alarm for (those of the hours before a study was joined) are not posted.
     */
    fun rearm(context: Context) {
        val log = PromptLog(context)
        catchUpFrom(log.armedFor(), System.currentTimeMillis())?.let { runCatching { notifyRinging(context, it) } }
        val am = context.getSystemService(AlarmManager::class.java)
        val pending = alarmIntent(context, 0L)
        am.cancel(pending)
        val next = Schedule.nextMoment(StudyStore(context).all(), Instant.now(), exitNotified = log.exits())
        log.armed(next)
        if (next == null) return
        val pi = alarmIntent(context, next)
        try {
            if (canExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pi)
        }
    }

    private fun alarmIntent(context: Context, due: Long): PendingIntent =
        PendingIntent.getBroadcast(
            context, 0,
            Intent(context, PromptReceiver::class.java).putExtra(EXTRA_DUE, due),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    fun createChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_HIGH)
        // on the lock screen only the neutral public version (see notify): taking part in a study is nobody's business
        channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        // what the channel is for, in the system's notification settings (audit A26)
        channel.description = context.getString(R.string.notif_channel_desc)
        manager.createNotificationChannel(channel)
        val silent = NotificationChannel(CHANNEL_SILENT, context.getString(R.string.notif_channel_silent), NotificationManager.IMPORTANCE_HIGH)
        silent.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        silent.description = context.getString(R.string.notif_channel_desc)
        silent.setSound(null, null)
        manager.createNotificationChannel(silent)
    }

    /** The channel of [study]'s notifications: with or without the sound (Study.sound). */
    fun channelOf(study: Study): String = if (study.sound) CHANNEL else CHANNEL_SILENT

    private fun mayPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * Posts the notifications of the alarm of the real moment [at] and an exit notification that is due. The alarm may
     * ring late (an inexact one minutes late, Doze longer): every prompt and reminder that rang since [at] is posted
     * too, once ([notifyRinging], AND-02). The time it really happened (the study's time) goes with the entry.
     */
    fun notify(context: Context, at: Long) {
        if (!mayPost(context)) return
        val studies = notifyRinging(context, at) ?: return
        notifyExits(context)
        // an exit moment has passed but the phone does not know the questionnaire is open: a sync finds out, and
        // posts it then (Sync.one)
        val real = System.currentTimeMillis()
        val notified = PromptLog(context).exits()
        if (studies.any { s ->
                s.code !in notified && s.exit?.open == false && Schedule.exitAt(s)?.let { s.now(real) >= it } == true
            }
        ) SyncWorker.sendSoon(context)
    }

    /**
     * Posts the prompts and reminders that rang from the real moment [at] (the alarm's) up to now and were not posted
     * yet (PromptLog), as long as they are open and were not opened or answered (audit 2026-10-18, AND-02: only the
     * very moment of the alarm counted, and a prompt between it and the late ring, or one whose alarm [rearm] replaced
     * before it rang, was never notified). Returns the studies, null when nothing may be posted.
     */
    private fun notifyRinging(context: Context, at: Long): List<Study>? {
        if (!mayPost(context)) return null
        val log = PromptLog(context)
        val queue = AnswerQueue(context)
        val studies = StudyStore(context).all()
        val real = System.currentTimeMillis()
        for (study in studies) {
            val now = study.now(real)
            val (prompts, reminders) = ringing(study, at, real)
            for (p in prompts) {
                val module = study.modules.firstOrNull { it.key == p.module } ?: continue
                if (!pending(study, module, p, now, log, queue) || log.notifiedAt(study.code, module.key, p.due) != null) continue
                post(context, study, module, p, notified = now, reminder = false)
                log.notified(study.code, module.key, p.due, now)
                // posted after its reminder's moment already: that one would only repeat it
                if (p.remindRing?.let { it <= real } == true) log.reminded(study.code, module.key, p.due)
            }
            for (p in reminders) {
                val module = study.modules.firstOrNull { it.key == p.module } ?: continue
                // only for a prompt still open that the participant has not opened, nor answered without a connection
                if (!pending(study, module, p, now, log, queue) || log.isReminded(study.code, module.key, p.due)) continue
                // the entry keeps the time of the first notification; without one (not posted) the reminder's own
                post(context, study, module, p, notified = log.notifiedAt(study.code, module.key, p.due) ?: now, reminder = true)
                log.reminded(study.code, module.key, p.due)
            }
        }
        return studies
    }

    /** Whether [p] still waits for the participant: open, neither opened nor answered (also not without a connection). */
    private fun pending(study: Study, module: Module, p: Prompt, now: Long, log: PromptLog, queue: AnswerQueue): Boolean =
        Schedule.open(module, p.due, now) && "${p.module}|${p.due}" !in study.completedPrompts &&
            !log.isOpened(study.code, module.key, p.due) && !log.isCompleted(study.code, module.key, p.due) &&
            !queue.has(study.code, module.key, p.due)

    /**
     * The moment from which [rearm] posts what the replaced alarm was set for: [armedFor] (the moment of the alarm
     * set last) when it has passed at the real [now] (the alarm is due but has not rung), else null. Plain Kotlin for
     * the tests.
     */
    fun catchUpFrom(armedFor: Long?, now: Long): Long? = armedFor?.takeIf { it in 1..now }

    /**
     * The prompts and the reminders of [study] that ring in the real window from [at] (an alarm's moment) up to and
     * with max([at], [real]), at most [MAX_LATE_MS] back, in the study's time (virtual for a fast-forward person).
     * Plain Kotlin for the tests.
     */
    fun ringing(study: Study, at: Long, real: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Pair<List<Prompt>, List<Prompt>> {
        val until = maxOf(at, real)
        val from = Instant.ofEpochMilli(study.now(maxOf(at, until - MAX_LATE_MS)))
        val to = Instant.ofEpochMilli(study.now(until) + 1)
        return Schedule.prompts(study, from, to, zone) to Schedule.reminders(study, from, to, zone)
    }

    /** An alarm that rings later than this (the phone was off for days) posts nothing older than this. */
    private const val MAX_LATE_MS = 24L * 60 * 60 * 1000

    /**
     * Posts the exit notification of every study for which it is due (Schedule.exitDue): once per study, when its
     * exit questionnaire is open and its moment has passed. Called when the alarm rings and after every sync.
     */
    fun notifyExits(context: Context) {
        if (!mayPost(context)) return
        val log = PromptLog(context)
        val notified = log.exits()
        val now = System.currentTimeMillis()
        for (study in StudyStore(context).all()) {
            val exit = study.exit ?: continue
            if (!Schedule.exitDue(study, now, study.code in notified)) continue
            val id = "${study.code}|exit".hashCode()
            val tap = PendingIntent.getActivity(
                context, id, EntryActivity.intent(context, study.code, EntryActivity.EXIT),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val name = exit.name.ifBlank { context.getString(R.string.exit_default) }
            // with the last day it can be answered, when the server names one (audit A26)
            val text = exit.until?.let {
                context.getString(R.string.notif_exit_until, name, it.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)))
            } ?: context.getString(R.string.notif_exit, name)
            val n = builder(context, channelOf(study), study.title, text, context.getString(R.string.notif_default)).setContentIntent(tap).build()
            try {
                NotificationManagerCompat.from(context).notify(id, n)
                log.exitNotified(study.code)
            } catch (e: SecurityException) {
                // the permission was taken away after mayPost() checked it: nothing to post
            }
        }
    }

    /**
     * A notification of [title] with [text] in [channel]; the lock screen shows only [neutral] under the app's name: neither the
     * title of the study nor the researchers' text (which may name the topic) is seen by someone else looking at the
     * phone.
     */
    private fun builder(context: Context, channel: String, title: String, text: String, neutral: String): NotificationCompat.Builder {
        val public = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(neutral)
            .build()
        return NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            // not gone with the tap: the entry removes it once its page has loaded, so that a page that did not load
            // (no connection) can be opened again from it (audit A8)
            .setAutoCancel(false)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
    }

    /**
     * One notification, under the id of the prompt: a reminder replaces the first notification instead of adding a
     * second one. Tapping opens the entry; [reminder] goes with it to the server ("reminded").
     */
    private fun post(context: Context, study: Study, module: Module, p: Prompt, notified: Long, reminder: Boolean) {
        val tap = PendingIntent.getActivity(
            context, p.id,
            EntryActivity.intent(context, study.code, module.key, p.due, notified, reminded = reminder),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val neutral = context.getString(if (reminder) R.string.notif_reminder else R.string.notif_default)
        // the reminder keeps the researchers' text, marked as a reminder (audit A26); the lock screen stays neutral
        val text = reminderText(module.notify, reminder, neutral) { context.getString(R.string.notif_reminder_of, it) }
        // the notification goes when the prompt expires: at the real moment of its (study's time) expiry
        val n = builder(context, channelOf(study), study.title, text, neutral)
            .setContentIntent(tap)
            .apply { if (module.expires > 0) setTimeoutAfter(study.realAt(p.due + module.expires * 60_000L) - System.currentTimeMillis()) }
            .build()
        try {
            NotificationManagerCompat.from(context).notify(p.id, n)
        } catch (e: SecurityException) {
            // the permission was taken away after notify() checked it: nothing to post
        }
    }

    /**
     * The text of a prompt's notification: the researchers' text, for the reminder after "Reminder:"; without a text
     * of theirs the app's own ([neutral]). Plain Kotlin for the unit tests.
     */
    fun reminderText(notify: String, reminder: Boolean, neutral: String, marked: (String) -> String): String = when {
        notify.isBlank() -> neutral
        reminder -> marked(notify)
        else -> notify
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    internal fun dueOf(intent: Intent): Long = intent.getLongExtra(EXTRA_DUE, 0L)
}

/**
 * Runs [work] off the main thread and keeps the receiver alive until it is done (goAsync): reading the studies opens
 * them with the Keystore, which must not hold up the main thread (audit 2026-10-13, AN-8).
 */
private fun BroadcastReceiver.inBackground(work: () -> Unit) {
    val pending = goAsync()
    Thread {
        try {
            work()
        } finally {
            pending.finish()
        }
    }.start()
}

class PromptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val at = Alarms.dueOf(intent)
        inBackground {
            // nothing stored may crash the app at every alarm (DiaryApp)
            if (at > 0) runCatching { Alarms.notify(context, at) }
            runCatching { Alarms.rearm(context) }
        }
    }
}

/** Sets the alarm again after a restart, an update of the app, or a change of time or time zone. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // the receiver is exported for these system broadcasts; anything else (an intent another app sends
        // directly) is ignored
        if (intent.action !in ACTIONS) return
        val action = intent.action
        inBackground {
            runCatching { Alarms.rearm(context) }
            // another time zone (or clock): the server learns it at once, not at the next sync hours later, and sends
            // the schedule in the new zone (audit 2026-10-18, AND-07); the sync waits for a connection
            if (syncs(action)) runCatching { SyncWorker.sendSoon(context) }
        }
    }

    internal companion object {
        /** Whether the system broadcast [action] also starts a sync: a change of time zone or of the clock (AND-07). */
        fun syncs(action: String?): Boolean = action == Intent.ACTION_TIMEZONE_CHANGED || action == Intent.ACTION_TIME_CHANGED

        private val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            // AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED (API 31; never sent before)
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}
