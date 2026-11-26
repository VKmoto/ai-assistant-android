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

    suspend fun create(
        client: OkHttpClient,
        config: TasksConfig,
        title: String,
        dueAt: String? = null,
        priority: Int? = null,
    ): TaskItem = withContext(Dispatchers.IO) {
        val payload = JSONObject().put("title", title)
        if (dueAt != null) payload.put("due_at", dueAt)
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
