package com.app.assistant.util

/**
 * Кольцевой журнал пути «текст ответа → звук».
 *
 * Нужен, чтобы отвечать на вопрос «почему не озвучилось» по факту, а не по догадкам:
 * на устройстве нет logcat, а без него причина (не дошёл SpeakText, не поднялся голос,
 * пустой PCM, fallback) неотличима. Журнал виден в настройках и копируется в буфер.
 */
object TtsTrace {
    private const val MAX = 300
    private val lines = mutableListOf<String>()

    @Synchronized
    fun add(tag: String, message: String) {
        val line = "${System.currentTimeMillis() % 1_000_000} $tag: $message"
        lines.add(line)
        while (lines.size > MAX) lines.removeAt(0)
        android.util.Log.d("TtsTrace", line)
    }

    @Synchronized
    fun dump(): String = if (lines.isEmpty()) "пусто" else lines.joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()
}
