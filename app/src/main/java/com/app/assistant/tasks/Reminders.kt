package com.app.assistant.tasks

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
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
            Result.success()
        }.getOrElse { Result.retry() }
    }
}
