package com.app.assistant.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Клиент bbx-tasks.
 *
 * Отдельный от чата: чат ходит в голосовой шлюз, задачи — в `bbx-tasks` (схема todo).
 * Токен — device, только чтение/CRUD своих задач; сервисный токен агенту не нужен.
 */
data class TaskItem(
    val id: String,
    val title: String,
    val status: String,
    val priority: Int,
    val dueAt: String?,
    val projectId: String?,
    val description: String?,
)

data class TasksConfig(val baseUrl: String, val token: String)

/** Напоминание, готовое к показу на телефоне. */
data class DueReminder(val id: String, val title: String)

object TasksApi {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    private fun request(config: TasksConfig, path: String): Request.Builder =
        Request.Builder()
            .url("${config.baseUrl.trimEnd('/')}$path")
            .addHeader("Authorization", "Bearer ${config.token}")

    private fun parseTask(o: JSONObject): TaskItem = TaskItem(
        id = o.optString("id"),
        title = o.optString("title"),
        status = o.optString("status", "todo"),
        priority = o.optInt("priority", 3),
        dueAt = if (o.isNull("due_at")) null else o.optString("due_at"),
        projectId = if (o.isNull("project_id")) null else o.optString("project_id"),
        description = if (o.isNull("description")) null else o.optString("description"),
    )

    suspend fun list(client: OkHttpClient, config: TasksConfig, view: String): List<TaskItem> =
        withContext(Dispatchers.IO) {
            val req = request(config, "/api/v1/tasks?view=$view").get().build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw IllegalStateException(errorMessage(body) ?: "HTTP ${res.code}")
                val arr: JSONArray = JSONObject(body).getJSONArray("tasks")
                (0 until arr.length()).map { parseTask(arr.getJSONObject(it)) }
            }
        }

    /**
     * «27.11.2026 09:00» (как вводит человек, МСК) → ISO с +03:00.
     * API и база живут в UTC, а человек пишет дату в привычном формате; конвертация
     * здесь, чтобы формат ввода не протекал в модель.
     */
    private fun toIsoOrNull(raw: String?): String? {
        val v = raw?.trim().orEmpty()
        if (v.isEmpty()) return null
        val m = Regex("""^(\d{2})\.(\d{2})\.(\d{4})(?:[ ,]+(\d{1,2}):(\d{2}))?$""").find(v)
            ?: return v // уже ISO или другой формат — отдаём как есть
        val (d, mo, y, h, mi) = m.destructured
        return "%s-%s-%sT%02d:%s:00+03:00".format(y, mo, d, h.ifEmpty { "0" }.toInt(), mi.ifEmpty { "00" })
    }

    suspend fun create(
        client: OkHttpClient,
        config: TasksConfig,
        title: String,
        dueAt: String? = null,
        priority: Int? = null,
    ): TaskItem = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("title", title)
        toIsoOrNull(dueAt)?.let { payload.put("due_at", it) }
        if (priority != null) payload.put("priority", priority)
        val req = request(config, "/api/v1/tasks")
            .post(payload.toString().toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IllegalStateException(errorMessage(body) ?: "HTTP ${res.code}")
            parseTask(JSONObject(body))
        }
    }

    suspend fun move(client: OkHttpClient, config: TasksConfig, id: String, status: String): TaskItem =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().put("status", status)
            val req = request(config, "/api/v1/tasks/$id")
                .patch(payload.toString().toRequestBody(jsonType))
                .build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw IllegalStateException(errorMessage(body) ?: "HTTP ${res.code}")
                parseTask(JSONObject(body))
            }
        }

    /** Напоминание на задачу: время в формате человека (27.11.2026 09:00, МСК). */
    suspend fun createReminder(
        client: OkHttpClient,
        config: TasksConfig,
        taskId: String,
        fireAt: String,
        channels: List<String> = listOf("local", "max", "email"),
    ) = withContext(Dispatchers.IO) {
        val payload = JSONObject()
            .put("fire_at", toIsoOrNull(fireAt) ?: fireAt)
            .put("channels", org.json.JSONArray(channels))
        val req = request(config, "/api/v1/tasks/$taskId/reminders")
            .post(payload.toString().toRequestBody(jsonType))
            .build()
        client.newCall(req).execute().use { res ->
            val body = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw IllegalStateException(errorMessage(body) ?: "HTTP ${res.code}")
        }
    }

    suspend fun startPomodoro(client: OkHttpClient, config: TasksConfig, taskId: String?, durationSec: Int): String =
        withContext(Dispatchers.IO) {
            val payload = JSONObject().put("duration_sec", durationSec)
            if (taskId != null) payload.put("task_id", taskId)
            val req = request(config, "/api/v1/pomodoro/start")
                .post(payload.toString().toRequestBody(jsonType)).build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw IllegalStateException(errorMessage(body) ?: "HTTP ${res.code}")
                JSONObject(body).optString("id")
            }
        }

    suspend fun finishPomodoro(client: OkHttpClient, config: TasksConfig, id: String, completed: Boolean) =
        withContext(Dispatchers.IO) {
            val req = request(config, "/api/v1/pomodoro/$id/${if (completed) "finish" else "cancel"}")
                .post("{}".toRequestBody(jsonType)).build()
            client.newCall(req).execute().use { it.body?.string() }
        }

    data class PomodoroStats(val sessions: Int, val seconds: Int)

    suspend fun pomodoroStats(client: OkHttpClient, config: TasksConfig): PomodoroStats =
        withContext(Dispatchers.IO) {
            val req = request(config, "/api/v1/pomodoro/stats").get().build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw IllegalStateException(errorMessage(body) ?: "HTTP ${res.code}")
                val o = JSONObject(body)
                PomodoroStats(o.optInt("sessions"), o.optInt("seconds"))
            }
        }

    suspend fun dueReminders(client: OkHttpClient, config: TasksConfig): List<DueReminder> =
        withContext(Dispatchers.IO) {
            val req = request(config, "/api/v1/reminders/due").get().build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw IllegalStateException(errorMessage(body) ?: "HTTP ${res.code}")
                val arr = JSONObject(body).getJSONArray("reminders")
                (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    DueReminder(o.optString("id"), o.optString("title"))
                }
            }
        }

    suspend fun complete(client: OkHttpClient, config: TasksConfig, id: String): TaskItem =
        withContext(Dispatchers.IO) {
            val req = request(config, "/api/v1/tasks/$id/complete").post("{}".toRequestBody(jsonType)).build()
            client.newCall(req).execute().use { res ->
                val body = res.body?.string().orEmpty()
                if (!res.isSuccessful) throw IllegalStateException(errorMessage(body) ?: "HTTP ${res.code}")
                parseTask(JSONObject(body))
            }
        }

    private fun errorMessage(body: String): String? = runCatching {
        JSONObject(body).getJSONObject("error").optString("message")
    }.getOrNull()?.takeIf { it.isNotBlank() }
}
