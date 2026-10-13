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
    private const val EXTRA_DUE = "due"

    fun canExact(context: Context): Boolean {
        val am = context.getSystemService(AlarmManager::class.java)
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
    }

    fun rearm(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)
        val pending = alarmIntent(context, 0L)
        am.cancel(pending)
        val next = Schedule.nextMoment(StudyStore(context).all(), Instant.now(), exitNotified = PromptLog(context).exits()) ?: return
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
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.notif_channel), NotificationManager.IMPORTANCE_HIGH)
        // on the lock screen only the neutral public version (see notify): taking part in a study is nobody's business
        channel.lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        // what the channel is for, in the system's notification settings (audit A26)
        channel.description = context.getString(R.string.notif_channel_desc)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun mayPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /**
     * Posts the notifications of the real moment [at]: the prompts due then and the reminders of earlier prompts
     * that ring then, and an exit notification that is due. The time it really happened (the study's time) goes
     * with the entry.
     */
    fun notify(context: Context, at: Long) {
        if (!mayPost(context)) return
        val store = StudyStore(context)
        val log = PromptLog(context)
        val queue = AnswerQueue(context)
        val studies = store.all()
        for (study in studies) {
            // the study's times that ring at the real moment [at]: (time(at - 1), time(at)]; for real time just [at]
            val from = Instant.ofEpochMilli(study.now(at - 1) + 1)
            val until = Instant.ofEpochMilli(study.now(at) + 1)
            val now = study.now()
            for (p in Schedule.prompts(study, from, until)) {
                val module = study.modules.firstOrNull { it.key == p.module } ?: continue
                if (!Schedule.open(module, p.due, now)) continue
                post(context, study, module, p, notified = now, reminder = false)
                log.notified(study.code, module.key, p.due, now)
            }
            for (p in Schedule.reminders(study, from, until)) {
                val module = study.modules.firstOrNull { it.key == p.module } ?: continue
                // only for a prompt still open that the participant has not opened, nor answered without a connection
                if (!Schedule.open(module, p.due, now)) continue
                if (log.isOpened(study.code, module.key, p.due) || queue.has(study.code, module.key, p.due)) continue
                // the entry keeps the time of the first notification; without one (not posted) the reminder's own
                post(context, study, module, p, notified = log.notifiedAt(study.code, module.key, p.due) ?: now, reminder = true)
            }
        }
        notifyExits(context)
        // an exit moment has passed but the phone does not know the questionnaire is open: a sync finds out, and
        // posts it then (Sync.one)
        val real = System.currentTimeMillis()
        val notified = log.exits()
        if (studies.any { s ->
                s.code !in notified && s.exit?.open == false && Schedule.exitAt(s)?.let { s.now(real) >= it } == true
            }
        ) SyncWorker.sendSoon(context)
    }

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
            val n = builder(context, study.title, text, context.getString(R.string.notif_default)).setContentIntent(tap).build()
            try {
                NotificationManagerCompat.from(context).notify(id, n)
                log.exitNotified(study.code)
            } catch (e: SecurityException) {
                // the permission was taken away after mayPost() checked it: nothing to post
            }
        }
    }

    /**
     * A notification of [title] with [text]; the lock screen shows only [neutral] under the app's name: neither the
     * title of the study nor the researchers' text (which may name the topic) is seen by someone else looking at the
     * phone.
     */
    private fun builder(context: Context, title: String, text: String, neutral: String): NotificationCompat.Builder {
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(neutral)
            .build()
        return NotificationCompat.Builder(context, CHANNEL)
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
        val n = builder(context, study.title, text, neutral)
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
        inBackground { runCatching { Alarms.rearm(context) } }
    }

    private companion object {
        val ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            // AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED (API 31; never sent before)
            "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
        )
    }
}
