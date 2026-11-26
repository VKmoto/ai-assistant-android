package com.app.assistant.tts

interface TtsManager {
    /**
     * @param voiceIdOverride жёстко заданный голос (кнопка «Прослушать» в настройках).
     *   null = выбрать по префиксу «[Имя]» из текста. Параметр добавлен, потому что
     *   предпросмотр должен звучать выбранным голосом ещё до того, как он привязан к
     *   агенту.
     */
    fun speak(text: String, queueMode: Int = QUEUE_FLUSH, voiceIdOverride: String? = null)
    fun stop()
    fun isSpeaking(): Boolean
    fun shutdown()

    companion object {
        const val QUEUE_FLUSH = 0
        const val QUEUE_ADD = 1
    }
}

enum class TtsMode {
    NATIVE,
    OFFLINE,
    API
}
