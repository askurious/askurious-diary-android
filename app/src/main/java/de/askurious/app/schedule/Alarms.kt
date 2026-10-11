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
import de.askurious.app.ui.EntryActivity
import java.time.Instant

/**
 * One alarm at a time: the earliest next prompt or reminder over all studies (Schedule.nextMoment). When it rings,
 * PromptReceiver posts the notifications of that moment and sets the next alarm. Any change (sync, joining,
 * leaving, boot, time zone) calls [rearm], which computes the next moment again from the stored studies.
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
        val next = Schedule.nextMoment(StudyStore(context).all(), Instant.now()) ?: return
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
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * Posts the notifications of the moment [at]: the prompts due then and the reminders of earlier prompts that
     * ring then. The time it really happened goes with the entry.
     */
    fun notify(context: Context, at: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val store = StudyStore(context)
        val log = PromptLog(context)
        val queue = AnswerQueue(context)
        val now = System.currentTimeMillis()
        val from = Instant.ofEpochMilli(at)
        val until = Instant.ofEpochMilli(at + 1)
        val studies = store.all()
        for (p in studies.flatMap { Schedule.prompts(it, from, until) }) {
            val study = studies.firstOrNull { it.code == p.study } ?: continue
            val module = study.modules.firstOrNull { it.key == p.module } ?: continue
            if (!Schedule.open(module, p.due, now)) continue
            post(context, study, module, p, notified = now, reminder = false)
            log.notified(study.code, module.key, p.due, now)
        }
        for (p in studies.flatMap { Schedule.reminders(it, from, until) }) {
            val study = studies.firstOrNull { it.code == p.study } ?: continue
            val module = study.modules.firstOrNull { it.key == p.module } ?: continue
            // only for a prompt still open that the participant has not opened, nor answered without a connection
            if (!Schedule.open(module, p.due, now)) continue
            if (log.isOpened(study.code, module.key, p.due) || queue.has(study.code, module.key, p.due)) continue
            // the entry keeps the time of the first notification; without one (not posted) the reminder's own
            post(context, study, module, p, notified = log.notifiedAt(study.code, module.key, p.due) ?: now, reminder = true)
        }
    }

    /**
     * One notification, under the id of the prompt: a reminder replaces the first notification instead of adding a
     * second one. Tapping opens the entry; [reminder] goes with it to the server ("reminded").
     */
    private fun post(context: Context, study: Study, module: Module, p: Prompt, notified: Long, reminder: Boolean) {
        val now = System.currentTimeMillis()
        val tap = PendingIntent.getActivity(
            context, p.id,
            EntryActivity.intent(context, study.code, module.key, p.due, notified, reminded = reminder),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val neutral = context.getString(if (reminder) R.string.notif_reminder else R.string.notif_default)
        val text = if (reminder) neutral else module.notify.ifBlank { neutral }
        // the lock screen shows only this neutral version: neither the title of the study nor the researchers'
        // text (which may name the topic) is seen by someone else looking at the phone
        val public = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(neutral)
            .build()
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(study.title)
            .setContentText(text)
            .setContentIntent(tap)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .apply { if (module.expires > 0) setTimeoutAfter(p.due + module.expires * 60_000L - now) }
            .build()
        try {
            NotificationManagerCompat.from(context).notify(p.id, n)
        } catch (e: SecurityException) {
            // the permission was taken away after notify() checked it: nothing to post
        }
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    internal fun dueOf(intent: Intent): Long = intent.getLongExtra(EXTRA_DUE, 0L)
}

class PromptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val at = Alarms.dueOf(intent)
        if (at > 0) Alarms.notify(context, at)
        Alarms.rearm(context)
    }
}

/** Sets the alarm again after a restart, an update of the app, or a change of time or time zone. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // the receiver is exported for these system broadcasts; anything else (an intent another app sends
        // directly) is ignored
        if (intent.action !in ACTIONS) return
        Alarms.rearm(context)
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
