package com.app.assistant.tts

/**
 * Разбор префикса «[Имя]», который роутер ставит перед ответом агента.
 *
 * Нужен, чтобы один и тот же чат мог звучать разными голосами: сообщение «[Док] …»
 * читается голосом Дока, «[Масяня] …» — голосом Масяни. Регулярка намеренно строгая:
 * только начало строки и имя до 32 символов, чтобы «[1/2]» (нумерация длинного ответа)
 * или квадратные скобки в середине текста не принимались за агента.
 */
object VoiceResolver {
    private val AGENT_PREFIX = Regex("""^\s*\[([^\[\]]{1,32})\]\s*""")

    fun agentName(text: String): String? =
        AGENT_PREFIX.find(text)?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** Убрать префикс агента из текста, который пойдёт в синтез. */
    fun stripAgentPrefix(text: String): String = AGENT_PREFIX.replaceFirst(text, "")
}
