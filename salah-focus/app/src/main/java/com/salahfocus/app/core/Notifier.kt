package com.salahfocus.app.core

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.salahfocus.app.MainActivity

object Notifier {
    private const val CHANNEL = "prayer"
    private const val ALARM_CODE = 100

    /** Schedules the single next reminder. Each fired alarm schedules the one after it. */
    fun reschedule(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmIntent(app, null, null)
        am.cancel(pi)
        val prefs = Prefs(app)
        if (!prefs.remindersEnabled) return
        val alert = Schedule.nextAlert(prefs, System.currentTimeMillis()) ?: return
        val withExtras = alarmIntent(app, alert.type, alert.event.prayer)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (canExact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alert.at, withExtras)
        else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, alert.at, withExtras)
    }

    private fun alarmIntent(context: Context, type: AlertType?, prayer: Prayer?): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
        if (type != null && prayer != null) {
            intent.putExtra("type", type.name).putExtra("prayer", prayer.name)
        }
        return PendingIntent.getBroadcast(
            context, ALARM_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    fun show(context: Context, type: AlertType, prayer: Prayer) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val prefs = Prefs(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "تذكير الصلاة", NotificationManager.IMPORTANCE_HIGH),
        )
        val (title, text) = when (type) {
            AlertType.START -> "اقترب وقت صلاة ${prayer.ar}" to
                "بقي ${prefs.minutesBefore} دقائق، وتم حجب برامج التواصل حتى تنتهي من الصلاة."
            AlertType.TIME -> "حان الآن وقت صلاة ${prayer.ar}" to "قم إلى الصلاة، أرحنا بها."
            AlertType.END -> "تقبّل الله صلاتك" to "تم فتح برامج التواصل من جديد."
        }
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .build()
        nm.notify(type.ordinal + 1, n)
    }
}
