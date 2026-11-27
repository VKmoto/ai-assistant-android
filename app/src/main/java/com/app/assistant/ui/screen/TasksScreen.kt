package com.app.assistant.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
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
import androidx.compose.material3.Checkbox
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
import kotlinx.coroutines.CancellationException
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
    var reminderFor by remember { mutableStateOf<TaskItem?>(null) }
    var reminderAt by remember { mutableStateOf("") }
    var pomodoroFor by remember { mutableStateOf<TaskItem?>(null) }
    var pomodoroSeconds by remember { mutableIntStateOf(25 * 60) }
    var pomodoroRunning by remember { mutableStateOf(false) }
    var pomodoroId by remember { mutableStateOf<String?>(null) }
    // Каналы выбирает человек: по умолчанию только телефон. «Слать сразу везде» —
    // плохой дефолт (MAX/почта могут быть лишними для бытовой задачи).
    var reminderChannels by remember { mutableStateOf(setOf("local")) }
    val channelOptions = listOf(
        "local" to "Телефон (уведомление)",
        "max" to "MAX",
        "telegram" to "Telegram",
        "email" to "Email",
    )

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
    // Тикаем локально: сервер хранит сессию и итог, а секунды не гоняем по сети.
    LaunchedEffect(pomodoroRunning, pomodoroFor) {
        while (pomodoroRunning && pomodoroSeconds > 0) {
            kotlinx.coroutines.delay(1000)
            pomodoroSeconds -= 1
        }
        if (pomodoroRunning && pomodoroSeconds == 0) {
            pomodoroRunning = false
            val id = pomodoroId
            if (id != null) runCatching { TasksApi.finishPomodoro(client, config, id, true) }
            pomodoroId = null
        }
    }

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
                KanbanBoard(
                    tasks = tasks,
                    onTaskClick = { selected = it },
                    onMove = { task, status ->
                        scope.launch {
                            runCatching { TasksApi.move(client, config, task.id, status) }
                                .onSuccess { refreshKey++ }
                                .onFailure { e -> e.rethrowIfCancellation(); error = e.message }
                        }
                    },
                )
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
                // Текущее время владельца (МСК) подставлено, чтобы в большинстве случаев
                // достаточно было поправить одну цифру, а не вводить всё с нуля.
                newDue = nowMsk()
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
                                .onFailure { e -> e.rethrowIfCancellation(); error = e.message }
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
                            .onFailure { e -> e.rethrowIfCancellation(); error = e.message }
                    }
                }
            },
            onStartPomodoro = {
                val task = selected
                scope.launch {
                    runCatching { TasksApi.startPomodoro(client, config, task?.id, 25 * 60) }
                        .onSuccess { id ->
                            pomodoroId = id
                            pomodoroSeconds = 25 * 60
                            pomodoroRunning = true
                            pomodoroFor = task
                        }
                        .onFailure { e -> e.rethrowIfCancellation(); error = e.message }
                }
            },
            onAddReminder = {
                // По умолчанию — срок самой задачи (если он есть), иначе текущее время.
                reminderAt = selected?.dueAt?.let { mskFromIso(it) } ?: nowMsk()
                reminderChannels = setOf("local")
                reminderFor = selected
            },
            onComplete = {
                val task = selected
                if (task != null) {
                    scope.launch {
                        runCatching { TasksApi.complete(client, config, task.id) }
                            .onSuccess { selected = null; refreshKey++ }
                            .onFailure { e -> e.rethrowIfCancellation(); error = e.message }
                    }
                }
            },
        )
    }

    if (pomodoroFor != null || pomodoroId != null) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text("Помодоро") },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "%02d:%02d".format(pomodoroSeconds / 60, pomodoroSeconds % 60),
                        style = MaterialTheme.typography.displaySmall,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        pomodoroFor?.title ?: "Без задачи",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { pomodoroRunning = !pomodoroRunning }) {
                    Text(if (pomodoroRunning) "Пауза" else "Продолжить")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    val id = pomodoroId
                    pomodoroRunning = false
                    pomodoroId = null
                    pomodoroFor = null
                    pomodoroSeconds = 25 * 60
                    if (id != null) scope.launch {
                        runCatching { TasksApi.finishPomodoro(client, config, id, false) }
                            .onFailure { e -> e.rethrowIfCancellation() }
                    }
                }) { Text("Стоп") }
            },
        )
    }

    if (reminderFor != null) {
        AlertDialog(
            onDismissRequest = { reminderFor = null },
            title = { Text("Напоминание") },
            text = {
                Column {
                    Text(reminderFor!!.title, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = reminderAt,
                        onValueChange = { reminderAt = it },
                        label = { Text("Когда (27.11.2026 09:00, МСК)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Куда напомнить", style = MaterialTheme.typography.labelLarge)
                    // Чекбоксы, а не switches: выбор подтверждается кнопкой «Поставить»
                    // (components.md: checkbox — для одного или нескольких выборов,
                    // подтверждаемых позже; switch — для немедленного вкл/выкл).
                    channelOptions.forEach { (id, label) ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                reminderChannels = if (reminderChannels.contains(id)) {
                                    reminderChannels - id
                                } else {
                                    reminderChannels + id
                                }
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = reminderChannels.contains(id),
                                onCheckedChange = { checked ->
                                    reminderChannels = if (checked) reminderChannels + id else reminderChannels - id
                                },
                            )
                            Text(label, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = reminderAt.isNotBlank() && reminderChannels.isNotEmpty(),
                    onClick = {
                    val task = reminderFor
                    val whenText = reminderAt.trim()
                    if (task != null && whenText.isNotEmpty() && reminderChannels.isNotEmpty()) {
                        scope.launch {
                            runCatching {
                                TasksApi.createReminder(
                                    client,
                                    config,
                                    task.id,
                                    whenText,
                                    reminderChannels.toList(),
                                )
                            }
                                .onSuccess { reminderFor = null; selected = null; refreshKey++ }
                                .onFailure { e -> e.rethrowIfCancellation(); error = e.message }
                        }
                    }
                    },
                ) { Text("Поставить") }
            },
            dismissButton = { TextButton(onClick = { reminderFor = null }) { Text("Отмена") } },
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
private fun KanbanBoard(
    tasks: List<TaskItem>,
    onTaskClick: (TaskItem) -> Unit,
    onMove: (TaskItem, String) -> Unit,
) {
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
                    DraggableTaskCard(
                        task = task,
                        onTaskClick = { onTaskClick(task) },
                        onDrop = { steps ->
                            // Карточку тащат влево/вправо: каждые 260 dp (ширина колонки)
                            // — одна колонка статуса. Так drag работает и в горизонтально
                            // прокручиваемом канбане, где координаты колонок уезжают.
                            val index = STATUS_COLUMNS.indexOfFirst { it.first == task.status }
                            val target = (index + steps).coerceIn(0, STATUS_COLUMNS.lastIndex)
                            if (target != index) onMove(task, STATUS_COLUMNS[target].first)
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DraggableTaskCard(
    task: TaskItem,
    onTaskClick: () -> Unit,
    onDrop: (steps: Int) -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val columnPx = with(density) { 260.dp.toPx() }
    var offsetX by remember { mutableStateOf(0f) }

    Box(
        modifier = Modifier
            .padding(bottom = 6.dp)
            .offset { androidx.compose.ui.unit.IntOffset(offsetX.roundToInt(), 0) }
            .pointerInput(task.id, task.status) {
                detectDragGesturesAfterLongPress(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                    },
                    onDragEnd = {
                        val steps = if (kotlin.math.abs(offsetX) < columnPx * 0.4f) 0 else (offsetX / columnPx).roundToInt()
                        offsetX = 0f
                        onDrop(steps)
                    },
                    onDragCancel = { offsetX = 0f },
                )
            },
    ) {
        TaskCard(task = task, onClick = onTaskClick)
    }
}

@Composable
private fun TaskDetailsDialog(
    task: TaskItem,
    onDismiss: () -> Unit,
    onMove: (String) -> Unit,
    onAddReminder: () -> Unit,
    onStartPomodoro: () -> Unit,
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
                TextButton(onClick = onStartPomodoro, modifier = Modifier.fillMaxWidth()) {
                    Text("Помодоро 25:00")
                }
                TextButton(onClick = onAddReminder, modifier = Modifier.fillMaxWidth()) {
                    Text("Напоминание…")
                }
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

/**
 * Отмена корутины — не ошибка.
 *
 * `rememberCoroutineScope` отменяется, когда экран уходит из композиции, и сообщение
 * отмены («The coroutine scope left the composition») попадало в error-строку как
 * красная ошибка, а список при этом затирался (баг владельца 2026-11-27). Отмену
 * прокидываем дальше, показываем только настоящие сбои.
 */
private fun Throwable.rethrowIfCancellation() {
    if (this is CancellationException) throw this
}

/** Текущее время владельца в формате ввода: «27.11.2026 09:00» (МСК). */
private fun nowMsk(): String {
    val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Europe/Moscow")
    }
    return fmt.format(Date())
}

/** Срок задачи (UTC ISO) → «27.11.2026 09:00» МСК для поля ввода. */
private fun mskFromIso(iso: String): String? = runCatching {
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }
    val date = parser.parse(iso) ?: return null
    val fmt = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("Europe/Moscow")
    }
    fmt.format(date)
}.getOrNull()

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
