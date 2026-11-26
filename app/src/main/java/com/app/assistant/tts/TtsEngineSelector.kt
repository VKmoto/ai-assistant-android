package com.app.assistant.tts

import android.content.Context
import com.app.assistant.repository.SettingsRepository

class TtsEngineSelector(
    private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val okHttpClient: okhttp3.OkHttpClient = com.app.assistant.viewmodel.MainViewModelFactory.okHttpClient,
    private val onSpeakingStateChanged: (isSpeaking: Boolean) -> Unit
) : TtsManager {

    private var nativeTtsManager: NativeTtsManager? = null
    private var offlineTtsManager: OfflineTtsManager? = null
    private var apiTtsManager: ApiTtsManager? = null

    private fun getNativeTtsManager(): NativeTtsManager {
        return nativeTtsManager ?: NativeTtsManager(context, onSpeakingStateChanged).also { nativeTtsManager = it }
    }

    private fun getOfflineTtsManager(): OfflineTtsManager {
        return offlineTtsManager ?: OfflineTtsManager(context, settingsRepository, onSpeakingStateChanged).also { offlineTtsManager = it }
    }

    private fun getApiTtsManager(): ApiTtsManager {
        return apiTtsManager ?: ApiTtsManager(context, settingsRepository, okHttpClient, onSpeakingStateChanged).also { apiTtsManager = it }
    }

    override fun speak(text: String, queueMode: Int) {
        val mode = settingsRepository.getTtsMode()
        
        if (queueMode == TtsManager.QUEUE_FLUSH) {
            nativeTtsManager?.stop()
            offlineTtsManager?.stop()
            apiTtsManager?.stop()
        }

        when (mode) {
            TtsMode.OFFLINE -> {
                if (isOfflineModelInstalled()) {
                    getOfflineTtsManager().speak(text, queueMode)
                } else {
                    getNativeTtsManager().speak(text, queueMode)
                }
            }
            TtsMode.API -> {
                getApiTtsManager().speak(text, queueMode)
            }
            else -> {
                getNativeTtsManager().speak(text, queueMode)
            }
        }
    }

    override fun stop() {
        nativeTtsManager?.stop()
        offlineTtsManager?.stop()
        apiTtsManager?.stop()
    }

    override fun isSpeaking(): Boolean {
        val mode = settingsRepository.getTtsMode()
        return when (mode) {
            TtsMode.OFFLINE -> {
                if (isOfflineModelInstalled()) {
                    offlineTtsManager?.isSpeaking() ?: false
                } else {
                    nativeTtsManager?.isSpeaking() ?: false
                }
            }
            TtsMode.API -> {
                apiTtsManager?.isSpeaking() ?: false
            }
            else -> {
                nativeTtsManager?.isSpeaking() ?: false
            }
        }
    }

    override fun shutdown() {
        nativeTtsManager?.shutdown()
        offlineTtsManager?.shutdown()
        apiTtsManager?.shutdown()
    }

    private val ttsModelManager = TtsModelManager(context)

    /**
     * Офлайн-режим возможен, если скачан ХОТЯ БЫ ОДИН голос: конкретный голос агента
     * выбирается уже внутри OfflineTtsManager, а без модели он откатывается на любой
     * установленный. Отсутствие «голоса по умолчанию» не должно выключать режим.
     */
    private fun isOfflineModelInstalled(): Boolean = ttsModelManager.isAnyModelDownloaded()
}
