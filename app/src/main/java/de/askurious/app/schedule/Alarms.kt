package de.askurious.app.schedule

import android.Manifest
import android.app.AlarmManager
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
import de.askurious.app.data.StudyStore
import de.askurious.app.ui.EntryActivity
import java.time.Instant

/**
 * One alarm at a time: the earliest next prompt over all studies. When it rings, PromptReceiver posts the
 * notifications of that moment and sets the next alarm. Any change (sync, joining, leaving, boot, time zone)
 * calls [rearm], which computes the next prompt again from the stored studies.
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
        val next = Schedule.next(StudyStore(context).all(), Instant.now()).firstOrNull() ?: return
        val pi = alarmIntent(context, next.due)
        try {
            if (canExact(context)) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.due, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.due, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.due, pi)
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
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Posts the notifications of the prompts due at [due]; the time it really happened goes with the entry. */
    fun notify(context: Context, due: Long) {
        val store = StudyStore(context)
        val now = System.currentTimeMillis()
        val prompts = store.all().flatMap { Schedule.prompts(it, Instant.ofEpochMilli(due), Instant.ofEpochMilli(due + 1)) }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) return
        for (p in prompts) {
            val study = store.get(p.study) ?: continue
            val module = study.modules.firstOrNull { it.key == p.module } ?: continue
            if (!Schedule.open(module, p.due, now)) continue
            val tap = PendingIntent.getActivity(
                context, p.id,
                EntryActivity.intent(context, study.code, module.key, p.due, now),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val text = module.notify.ifBlank { context.getString(R.string.notif_default, study.title) }
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(study.title)
                .setContentText(text)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .apply { if (module.expires > 0) setTimeoutAfter(p.due + module.expires * 60_000L - now) }
                .build()
            NotificationManagerCompat.from(context).notify(p.id, n)
        }
    }

    fun cancel(context: Context, id: Int) = NotificationManagerCompat.from(context).cancel(id)

    internal fun dueOf(intent: Intent): Long = intent.getLongExtra(EXTRA_DUE, 0L)
}

class PromptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val due = Alarms.dueOf(intent)
        if (due > 0) Alarms.notify(context, due)
        Alarms.rearm(context)
    }
}

/** Sets the alarm again after a restart, an update of the app, or a change of time or time zone. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Alarms.rearm(context)
    }
}
