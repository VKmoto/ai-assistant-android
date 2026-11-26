package com.app.assistant

import android.app.Application
import com.app.assistant.repository.SettingsRepository
import java.io.PrintWriter
import java.io.StringWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Отправка стектрейса на наш шлюз при падении.
 *
 * На телефоне нет logcat, а «приложение выбивает» иначе не разобрать: владелец видит
 * только факт закрытия. Поэтому uncaught-исключение уходит в тот же диагностический
 * канал bbx-voice, что и журнал синтеза (там нет секретов — только стек).
 *
 * Отправка синхронная с коротким таймаутом: процесс всё равно умирает, а поток,
 * запущенный «в фоне», система может не успеть выполнить.
 */
class AssistantApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            // Сеть на главном потоке Android запрещает (NetworkOnMainThreadException),
            // поэтому отправка идёт в отдельном потоке, а здесь ждём её недолго: процесс
            // всё равно завершится, но стек успевает уйти.
            runCatching {
                val sender = Thread { runCatching { sendCrash(error) } }
                sender.start()
                sender.join(3000)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun sendCrash(error: Throwable) {
        val settings = SettingsRepository(this)
        val base = settings.getLlmCustomUrl().substringBefore("/chat/completions").trimEnd('/')
        if (base.isEmpty()) return
        val token = settings.getChatApiKey() ?: return
        val url = if (base.endsWith("/v1")) "$base/diagnostics" else "$base/v1/diagnostics"

        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        val payload = org.json.JSONObject()
            .put("reason", "crash")
            .put(
                "trace",
                (sw.toString() + "\n--- tts trace ---\n" + com.app.assistant.util.TtsTrace.dump()).take(16_000),
            )
            .toString()

        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 2500
        connection.readTimeout = 2500
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.outputStream.use { it.write(payload.toByteArray()) }
        connection.responseCode
        connection.disconnect()
    }
}
