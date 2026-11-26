package com.app.assistant.util

import com.app.assistant.repository.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Автоматическая отправка журнала синтеза на наш голосовой шлюз.
 *
 * Зачем: на телефоне нет logcat, а причина «ответ не озвучен» иначе неотличима —
 * не дошёл текст до TTS, не поднялся голос, пустой PCM или сработал fallback.
 * Журнал уходит на bbx-voice (POST <base>/v1/diagnostics) после каждого озвученного
 * ответа, поэтому владельцу не нужно ничего копировать руками: я читаю файл на VPS.
 *
 * Отправляется только когда провайдер CUSTOM — то есть когда приложение действительно
 * говорит с нашим шлюзом; чужие серверы (OpenAI/Groq) таким путём не затрагиваются.
 */
object TtsDiagnosticsSender {
    suspend fun send(
        client: OkHttpClient,
        settingsRepository: SettingsRepository,
        reason: String,
    ) = withContext(Dispatchers.IO) {
        try {
            if (!settingsRepository.getLlmProvider().equals("CUSTOM", ignoreCase = true)) return@withContext
            val trace = TtsTrace.dump()
            if (trace == "пусто") return@withContext

            val base = settingsRepository.getLlmCustomUrl()
                .substringBefore("/chat/completions")
                .trimEnd('/')
            if (base.isEmpty()) return@withContext
            val url = if (base.endsWith("/v1")) "$base/diagnostics" else "$base/v1/diagnostics"

            val payload = JSONObject()
                .put("reason", reason)
                .put("trace", trace)
                .toString()
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer ${settingsRepository.getChatApiKey() ?: ""}")
                .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(request).execute().use { it.body?.string() }
        } catch (e: Exception) {
            android.util.Log.w("TtsDiagnosticsSender", "diagnostics upload failed: ${e.message}")
        }
    }
}
