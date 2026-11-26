package com.app.assistant.tts

import android.content.Context
import com.app.assistant.repository.SettingsRepository

/**
 * Единственный офлайн-движок синтеза на приложение.
 *
 * Зачем: экран настроек и основной экран создавали по своему OfflineTtsManager. Каждый
 * поднимал собственный native-движок sherpa-onnx на ту же модель, и после прослушивания
 * голоса в настройках основной движок мог не инициализироваться — ответ приходил, а
 * озвучки не было (жалоба владельца 2026-11-26: превью работает, ответ — тишина).
 * Один экземпляр убирает и конфликт, и двойную загрузку 64-МБ модели.
 */
object OfflineTtsHolder {
    @Volatile
    private var instance: OfflineTtsManager? = null

    fun get(
        context: Context,
        settingsRepository: SettingsRepository,
        onSpeakingStateChanged: (Boolean) -> Unit,
    ): OfflineTtsManager {
        instance?.let { return it }
        return synchronized(this) {
            instance ?: OfflineTtsManager(context, settingsRepository, onSpeakingStateChanged)
                .also { instance = it }
        }
    }

    fun shutdown() {
        synchronized(this) {
            instance?.shutdown()
            instance = null
        }
    }
}
