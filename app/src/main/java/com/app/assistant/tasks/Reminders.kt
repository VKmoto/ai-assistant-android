package com.app.assistant.tasks

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.PendingIntent
import android.app.NotificationManager
import android.content.Context
import android.content.BroadcastReceiver
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.app.assistant.R
import com.app.assistant.data.TasksApi
import com.app.assistant.data.TasksConfig
import com.app.assistant.repository.SettingsRepository
import com.app.assistant.viewmodel.MainViewModelFactory
import java.util.concurrent.TimeUnit

/**
 * Локальные напоминания без FCM.
 *
 * Сервер отдаёт due-напоминания (`GET /api/v1/reminders/due`), а телефон раз в 15 минут
 * забирает их через WorkManager и показывает системное уведомление. Плюс: без Google-
 * проекта и без сети в момент показа; минус: точность до 15 минут (минимальный период
 * WorkManager). Точные AlarmManager-будильники добавим, если попросите.
 *
 * Сервер отмечает `local_sent_at` отдельно от `remote_sent_at`, поэтому воркер
 * MAX/Telegram/email не «съедает» напоминание до того, как телефон его заберёт.
 */
/**
 * Отметки «уведомление уже показано».
 *
 * Точный будильник (AlarmManager) и страховочный воркер (WorkManager) смотрят на одно и
 * то же напоминание, поэтому владелец получал его дважды. Кто первый показал — тот
 * ставит отметку, второй молчит.
 */
object ShownReminders {
    private const val PREFS = "tasks_reminders_shown"

    fun wasShown(context: Context, id: String): Boolean {
        val at = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(id, 0L)
        // Отметка живёт двое суток: этого хватает, чтобы гасить дубли, и не растёт файл.
        return at > 0 && System.currentTimeMillis() - at < 2L * 24 * 60 * 60 * 1000
    }

    fun mark(context: Context, id: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putLong(id, System.currentTimeMillis()).apply()
    }
}

object ReminderScheduler {
    private const val WORK_NAME = "tasks-reminders"
    const val CHANNEL_ID = "tasks-reminders"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Напоминания задач", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Напоминания из таск-менеджера"
            },
        )
    }

    fun ensureScheduled(context: Context) {
        ensureChannel(context)
        val request = PeriodicWorkRequestBuilder<ReminderWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request,
        )
    }

    /**
     * Точный будильник на конкретное напоминание.
     *
     * WorkManager просыпается раз в 15 минут, поэтому «в 09:00» могло прозвучать на
     * минуту-две позже (жалоба владельца 2026-11-27). AlarmManager с
     * setExactAndAllowWhileIdle срабатывает минута в минуту; если система запретила
     * точные будильники (Android 12+), падаем на setAndAllowWhileIdle — лучше
     * приблизительно, чем никак.
     */
    /** Снять будильники, поставленные на задачу до сдвига срока. */
    fun cancelExact(context: Context, key: String) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = PendingIntent.getBroadcast(
            context,
            key.hashCode(),
            // action обязан совпасть с тем, что ставил scheduleExact: PendingIntent
            // сравнивается по action+data, иначе cancel() тихо ничего не снимет.
            Intent(context, ReminderAlarmReceiver::class.java).apply {
                action = "bbx.tasks.REMINDER:$key"
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        manager.cancel(pending)
    }

    fun scheduleExact(context: Context, reminderId: String, title: String, fireAtMs: Long) {
        ensureChannel(context)
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderAlarmReceiver::class.java).apply {
            putExtra("id", reminderId)
            putExtra("title", title)
            action = "bbx.tasks.REMINDER:$reminderId"
        }
        val pending = PendingIntent.getBroadcast(
            context,
            reminderId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !manager.canScheduleExactAlarms()) {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtMs, pending)
            } else {
                manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtMs, pending)
            }
        } catch (e: SecurityException) {
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtMs, pending)
        }
    }
}

class ReminderAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val title = intent.getStringExtra("title") ?: return
        val id = intent.getStringExtra("id") ?: title
        if (ShownReminders.wasShown(context, id)) return
        ShownReminders.mark(context, id)
        ReminderScheduler.ensureChannel(context)
        NotificationManagerCompat.from(context).notify(
            id.hashCode(),
            NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_live_modes)
                .setContentTitle("Напоминание")
                .setContentText(title)
                .setStyle(NotificationCompat.BigTextStyle().bigText(title))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .build(),
        )
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            // После перезагрузки будильники теряются — воркер заново запланирует
            // точные будильники на будущие напоминания.
            ReminderScheduler.ensureScheduled(context)
        }
    }
}

class ReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val settings = SettingsRepository(applicationContext)
        val base = settings.getTasksUrl().trimEnd('/')
        val token = settings.getTasksToken()
        if (base.isEmpty() || token.isEmpty()) return Result.success()

        return runCatching {
            val due = TasksApi.dueReminders(MainViewModelFactory.okHttpClient, TasksConfig(base, token))
            due.forEach { item ->
                // Будильник мог уже показать это напоминание — тогда второй раз не шумим.
                if (ShownReminders.wasShown(applicationContext, item.id)) return@forEach
                ShownReminders.mark(applicationContext, item.id)
                NotificationManagerCompat.from(applicationContext).notify(
                    item.id.hashCode(),
                    NotificationCompat.Builder(applicationContext, ReminderScheduler.CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_live_modes)
                        .setContentTitle("Напоминание")
                        .setContentText(item.title)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(item.title))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setAutoCancel(true)
                        .build(),
                )
            }
            // Точные будильники на будущие напоминания: воркер просыпается редко,
            // а AlarmManager сработает в нужную минуту.
            runCatching {
                TasksApi.upcomingReminders(MainViewModelFactory.okHttpClient, TasksConfig(base, token))
                    .forEach { r ->
                        ReminderScheduler.scheduleExact(applicationContext, "task_${r.taskId}", r.title, r.fireAtMs)
                    }
            }
            Result.success()
        }.getOrElse { Result.retry() }
    }
}
