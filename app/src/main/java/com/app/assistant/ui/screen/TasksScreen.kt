package com.app.assistant.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.app.assistant.data.TaskItem
import com.app.assistant.data.TasksApi
import com.app.assistant.data.TasksConfig
import com.app.assistant.repository.SettingsRepository
import com.app.assistant.viewmodel.MainViewModelFactory
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Экран «Задачи» — часть приложения, поэтому оформлен теми же средствами: Material3,
 * AssistantTheme, скругления 16 dp и цвета темы.
 *
 * Структура намеренно простая: Column + TopAppBar, никаких subcompose-контейнеров
 * (Scaffold) и ранних `return` из composable-лямбд. Первый вариант с Scaffold падал
 * с ArrayIndexOutOfBoundsException в Composer (стек с устройства 2026-11-26) —
 * повреждение группы при измерении subcompose-слота. Здесь каждая ветка — обычный
 * if/else без выхода из composition, а диалоги вынесены за пределы Column.
 *
 * v1: список (Входящие/Сегодня/Предстоящее) и канбан: Надо / В работе / Ждёт / Готово.
 * Перемещение — через карточку (drag-and-drop отдельным шагом).
 */
private val STATUS_COLUMNS = listOf(
    "todo" to "Надо",
    "in_progress" to "В работе",
    "waiting" to "Ждёт",
    "done" to "Готово",
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TasksScreen(
    settingsRepository: SettingsRepository,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val client = MainViewModelFactory.okHttpClient

    var baseUrl by remember { mutableStateOf(settingsRepository.getTasksUrl()) }
    var token by remember { mutableStateOf(settingsRepository.getTasksToken()) }
    var showSettings by remember { mutableStateOf(false) }
    var showAdd by remember { mutableStateOf(false) }
    var view by remember { mutableStateOf("today") }
    var kanban by remember { mutableStateOf(false) }
    var tasks by remember { mutableStateOf<List<TaskItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<TaskItem?>(null) }
    var settingsUrl by remember { mutableStateOf("") }
    var settingsToken by remember { mutableStateOf("") }
    var newTitle by remember { mutableStateOf("") }
    var newDue by remember { mutableStateOf("") }

    val ready = baseUrl.isNotBlank() && token.isNotBlank()
    val config = TasksConfig(baseUrl, token)

    LaunchedEffect(view, kanban, refreshKey, ready) {
        if (ready) {
            loading = true
            error = null
            runCatching { TasksApi.list(client, config, if (kanban) "kanban" else view) }
                .onSuccess { tasks = it }
                .onFailure { error = it.message ?: "не удалось загрузить" }
            loading = false
        } else {
            showSettings = true
        }
    }

    // Фон обязателен: Activity прозрачная (edge-to-edge, как у чата), и без него
    // сквозь экран задач просвечивала домашняя подложка (скриншоты владельца).
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text("Задачи") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "Назад")
                }
            },
            actions = {
                IconButton(onClick = { refreshKey++ }) {
                    Icon(Icons.Filled.Refresh, contentDescription = "Обновить")
                }
                IconButton(onClick = {
                    settingsUrl = baseUrl
                    settingsToken = token
                    showSettings = true
                }) {
                    Icon(Icons.Filled.Settings, contentDescription = "Настройки задач")
                }
            },
        )

        if (!ready) {
            Text(
                "Укажите адрес сервиса задач и токен устройства (кнопка настроек сверху).",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            // FlowRow, а не Row: четыре чипа не помещаются в ширину экрана, и «Канбан»
            // уезжал за край (на скриншоте от него осталась полоска).
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(selected = !kanban && view == "inbox", onClick = { kanban = false; view = "inbox" }, label = { Text("Входящие") })
                FilterChip(selected = !kanban && view == "today", onClick = { kanban = false; view = "today" }, label = { Text("Сегодня") })
                FilterChip(selected = !kanban && view == "upcoming", onClick = { kanban = false; view = "upcoming" }, label = { Text("Предстоящее") })
                FilterChip(selected = kanban, onClick = { kanban = true }, label = { Text("Канбан") })
            }

            if (loading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (error != null) {
                Text(error!!, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
            } else if (tasks.isEmpty()) {
                Text("Пусто", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
            } else if (kanban) {
                KanbanBoard(tasks = tasks, onTaskClick = { selected = it })
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(tasks, key = { it.id }) { task ->
                        TaskCard(task = task, onClick = { selected = task })
                    }
                }
            }
        }
    }

    // Кнопка «+» обычная, а не FAB из Scaffold: тот же вид, но без subcompose-слота.
    if (ready) {
        // navigationBarsPadding: иначе кнопка «+» уезжает под системную навигацию.
        Box(
            modifier = Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp),
            contentAlignment = Alignment.BottomEnd,
        ) {
            FloatingActionButton(onClick = {
                newTitle = ""
                newDue = ""
                showAdd = true
            }) {
                Icon(Icons.Filled.Add, contentDescription = "Новая задача")
            }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Сервис задач") },
            text = {
                Column {
                    OutlinedTextField(
                        value = settingsUrl,
                        onValueChange = { settingsUrl = it },
                        label = { Text("Адрес (https://tasks…)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = settingsToken,
                        onValueChange = { settingsToken = it },
                        label = { Text("Токен устройства") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    baseUrl = settingsUrl.trim()
                    token = settingsToken.trim()
                    settingsRepository.setTasksUrl(baseUrl)
                    settingsRepository.setTasksToken(token)
                    showSettings = false
                    refreshKey++
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { showSettings = false }) { Text("Отмена") } },
        )
    }

    if (showAdd) {
        AlertDialog(
            onDismissRequest = { showAdd = false },
            title = { Text("Новая задача") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newTitle,
                        onValueChange = { newTitle = it },
                        label = { Text("Что сделать") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newDue,
                        onValueChange = { newDue = it },
                        label = { Text("Срок (27.11.2026 09:00, МСК)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val title = newTitle.trim()
                    if (title.isNotEmpty()) {
                        scope.launch {
                            runCatching { TasksApi.create(client, config, title, newDue.trim().ifEmpty { null }) }
                                .onSuccess { showAdd = false; refreshKey++ }
                                .onFailure { error = it.message }
                        }
                    }
                }) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { showAdd = false }) { Text("Отмена") } },
        )
    }

    if (selected != null) {
        TaskDetailsDialog(
            task = selected!!,
            onDismiss = { selected = null },
            onMove = { status ->
                val task = selected
                if (task != null) {
                    scope.launch {
                        runCatching { TasksApi.move(client, config, task.id, status) }
                            .onSuccess { selected = null; refreshKey++ }
                            .onFailure { error = it.message }
                    }
                }
            },
            onComplete = {
                val task = selected
                if (task != null) {
                    scope.launch {
                        runCatching { TasksApi.complete(client, config, task.id) }
                            .onSuccess { selected = null; refreshKey++ }
                            .onFailure { error = it.message }
                    }
                }
            },
        )
    }
}

@Composable
private fun TaskCard(task: TaskItem, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth().clickable { onClick() },
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                task.title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (task.priority <= 2) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            val meta = listOfNotNull(
                dueLabel(task.dueAt),
                if (task.priority == 1) "высокий приоритет" else null,
                if (task.status != "todo") statusLabel(task.status) else null,
            ).joinToString(" · ")
            if (meta.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun KanbanBoard(tasks: List<TaskItem>, onTaskClick: (TaskItem) -> Unit) {
    // Четыре равные колонки на телефоне давали ~70 dp — текст рвался по буквам
    // («Тест email-н апом...»). Колонки фиксированной ширины, канбан прокручивается
    // по горизонтали — как в обычных канбан-досках.
    Row(
        modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        STATUS_COLUMNS.forEach { (status, label) ->
            val columnTasks = tasks.filter { it.status == status }
            Column(
                modifier = Modifier
                    .width(260.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    "$label (${columnTasks.size})",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
                columnTasks.forEach { task ->
                    Box(modifier = Modifier.padding(bottom = 6.dp)) {
                        TaskCard(task = task, onClick = { onTaskClick(task) })
                    }
                }
            }
        }
    }
}

@Composable
private fun TaskDetailsDialog(
    task: TaskItem,
    onDismiss: () -> Unit,
    onMove: (String) -> Unit,
    onComplete: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(task.title) },
        text = {
            Column {
                if (!task.description.isNullOrBlank()) {
                    Text(task.description, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                }
                Text("Статус: ${statusLabel(task.status)}", style = MaterialTheme.typography.bodySmall)
                dueLabel(task.dueAt)?.let { Text("Срок: $it", style = MaterialTheme.typography.bodySmall) }
                Spacer(Modifier.height(12.dp))
                Text("Переместить:", style = MaterialTheme.typography.labelMedium)
                STATUS_COLUMNS.forEach { (status, label) ->
                    if (status != task.status) {
                        TextButton(onClick = { onMove(status) }, modifier = Modifier.fillMaxWidth()) { Text(label) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onComplete) { Text("Готово") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } },
    )
}

private fun statusLabel(status: String): String = STATUS_COLUMNS.firstOrNull { it.first == status }?.second ?: status

private fun dueLabel(dueAt: String?): String? {
    if (dueAt.isNullOrBlank()) return null
    return runCatching {
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val date: Date = parser.parse(dueAt) ?: return null
        val fmt = SimpleDateFormat("dd.MM HH:mm", Locale("ru")).apply {
            timeZone = TimeZone.getTimeZone("Europe/Moscow")
        }
        fmt.format(date)
    }.getOrNull()
}
