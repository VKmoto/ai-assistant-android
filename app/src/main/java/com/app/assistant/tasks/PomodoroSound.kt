package com.app.assistant.tasks

import android.content.Context
import android.media.RingtoneManager

/**
 * Звуковой сигнал смены фазы помодоро.
 *
 * Свой файл приложить было нельзя, поэтому берём системный звук уведомления: он есть
 * на любом устройстве, не требует ресурсов и не спорит с настройками громкости
 * владельца (если уведомления выключены — сигнала не будет, и это ожидаемо).
 */
object PomodoroSound {
    private const val PREFS = "app_prefs"
    private const val KEY = "pomodoro_sound_uri"

    /** Список системных мелодий: будильники и уведомления — владелец выбирает сам. */
    data class Choice(val title: String, val uri: String)

    fun choices(context: Context): List<Choice> {
        val manager = RingtoneManager(context)
        manager.setType(RingtoneManager.TYPE_ALARM or RingtoneManager.TYPE_NOTIFICATION)
        val cursor = manager.cursor ?: return emptyList()
        val list = mutableListOf<Choice>()
        while (cursor.moveToNext()) {
            val title = cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX) ?: continue
            val uri = manager.getRingtoneUri(cursor.position)?.toString() ?: continue
            list.add(Choice(title, uri))
        }
        return list
    }

    private fun currentUri(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null)

    fun save(context: Context, uri: String?) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, uri).apply()
    }

    fun play(context: Context) {
        playUri(context, currentUri(context))
    }

    fun playUri(context: Context, uri: String?) {
        runCatching {
            // По умолчанию — будильник: он громче уведомления и не тонет в потоке
            // сообщений (жалоба владельца 2026-11-27).
            val target: android.net.Uri = uri?.let { android.net.Uri.parse(it) }
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            RingtoneManager.getRingtone(context.applicationContext, target)?.play()
        }
    }
}
