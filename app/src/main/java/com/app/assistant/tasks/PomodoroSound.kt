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
    fun play(context: Context) {
        runCatching {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            RingtoneManager.getRingtone(context.applicationContext, uri)?.play()
        }
    }
}
