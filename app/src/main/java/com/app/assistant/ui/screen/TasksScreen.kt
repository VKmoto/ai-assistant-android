package com.app.assistant.ui.screen

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.app.assistant.data.TaskItem
import com.app.assistant.data.TasksApi
import com.app.assistant.data.TasksConfig
import com.app.assistant.tasks.PomodoroSound
import com.app.assistant.ui.components.DateTimeField
import com.app.assistant.ui.components.DateTimeValue
import com.app.assistant.repository.SettingsRepository
import com.app.assistant.viewmodel.MainViewModelFactory
import kotlinx.coroutines.CancellationException
import okhttp3.OkHttpClient
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
    var newDue by remember { mutableStateOf(DateTimeValue.nowMsk()) }
    var reminderFor by remember { mutableStateOf<TaskItem?>(null) }
    var reminderAt by remember { mutableStateOf(DateTimeValue.nowMsk()) }
    var tab by remember { mutableIntStateOf(0) }
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

        TabRow(selectedTabIndex = tab, modifier = Modifier.fillMaxWidth()) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Задачи") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Помодоро") })
        }

        if (tab == 1) {
            PomodoroTab(
                client = client,
                config = config,
                ready = ready,
                settingsRepository = settingsRepository,
                onError = { error = it },
                onGoSettings = { settingsUrl = baseUrl; settingsToken = token; showSettings = true },
            )
        } else if (!ready) {
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
                newDue = DateTimeValue.nowMsk()
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
                    Text("Срок (МСК): тап — выбрать, свайп ↑↓ — изменить", style = MaterialTheme.typography.labelMedium)
                    DateTimeField(value = newDue, onValueChange = { newDue = it })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val title = newTitle.trim()
                    if (title.isNotEmpty()) {
                        scope.launch {
                            runCatching { TasksApi.create(client, config, title, newDue.toIso()) }
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
            onAddReminder = {
                // По умолчанию — срок самой задачи (если он есть), иначе текущее время.
                reminderAt = selected?.dueAt?.let { DateTimeValue.fromIso(it) } ?: DateTimeValue.nowMsk()
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

    if (reminderFor != null) {
        AlertDialog(
            onDismissRequest = { reminderFor = null },
            title = { Text("Напоминание") },
            text = {
                Column {
                    Text(reminderFor!!.title, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("Когда (МСК): тап — выбрать, свайп ↑↓ — изменить", style = MaterialTheme.typography.labelMedium)
                    DateTimeField(value = reminderAt, onValueChange = { reminderAt = it })
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
                    enabled = reminderChannels.isNotEmpty(),
                    onClick = {
                    val task = reminderFor
                    if (task != null && reminderChannels.isNotEmpty()) {
                        scope.launch {
                            runCatching {
                                TasksApi.createReminder(
                                    client,
                                    config,
                                    task.id,
                                    reminderAt.toIso(),
                                    reminderChannels.toList(),
                                )
                            }
                                .onSuccess {
                                    // Точный будильник сразу: WorkManager тикает раз в 15 минут,
                                    // а напоминание должно прозвучать в назначенную минуту.
                                    if (reminderChannels.contains("local")) {
                                        com.app.assistant.tasks.ReminderScheduler.scheduleExact(
                                            context,
                                            "local_${task.id}_${reminderAt.toIso()}",
                                            task.title,
                                            reminderAt.toEpochMillis(),
                                        )
                                    }
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

/**
 * Вкладка «Помодоро» — отдельный режим, а не кнопка в задаче (просьба владельца).
 *
 * Большая круглая красная кнопка-помидор: тело, блик и зелёный листик нарисованы
 * Canvas-ом, поэтому не зависит от иконок и масштабируется без потерь. Внутри —
 * play/pause, тоже нарисованный (иконки Pause в базовом наборе нет).
 */
@Composable
private fun PomodoroTab(
    client: OkHttpClient,
    config: TasksConfig,
    ready: Boolean,
    settingsRepository: SettingsRepository,
    onError: (String?) -> Unit,
    onGoSettings: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Состояние живёт в prefs: вкладка может уйти из композиции, приложение — в фон.
    // Храним не остаток, а конец фазы: при возврате досчитываем по часам.
    var sessionId by remember { mutableStateOf(settingsRepository.getPomodoroSession()) }
    var endAt by remember { mutableLongStateOf(settingsRepository.getPomodoroEndAt()) }
    var mode by remember { mutableStateOf(settingsRepository.getPomodoroMode()) }
    var cycle by remember { mutableIntStateOf(settingsRepository.getPomodoroCycle()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var stats by remember { mutableStateOf<TasksApi.PomodoroStats?>(null) }

    val phaseTotal = when (mode) {
        "work" -> 25 * 60
        "long_break" -> 20 * 60
        else -> 5 * 60
    }
    val remaining = if (sessionId == null) {
        phaseTotal
    } else {
        ((endAt - now) / 1000L).coerceAtLeast(0L).toInt()
    }

    fun persist(id: String?, end: Long, phase: String, cycles: Int) {
        sessionId = id
        endAt = end
        mode = phase
        cycle = cycles
        settingsRepository.savePomodoro(id, end, phase, cycles)
    }

    suspend fun startPhase(phase: String, durationSec: Int) {
        runCatching { TasksApi.startPomodoro(client, config, null, durationSec) }
            .onSuccess { id -> persist(id, System.currentTimeMillis() + durationSec * 1000L, phase, cycle) }
            .onFailure { e -> e.rethrowIfCancellation(); onError(e.message) }
    }

    suspend fun finishPhase(completed: Boolean) {
        val id = sessionId
        if (id != null) runCatching { TasksApi.finishPomodoro(client, config, id, completed) }
        when {
            mode == "work" -> {
                // Цикл идёт сам: 25 работы → 5 отдыха, после 4-го — 20 минут.
                val next = cycle + 1
                PomodoroSound.play(context)
                val breakPhase = if (next % 4 == 0) "long_break" else "short_break"
                persist(null, 0L, breakPhase, next)
                startPhase(breakPhase, if (breakPhase == "long_break") 20 * 60 else 5 * 60)
            }
            else -> {
                // Перерыв закончился — сразу следующая работа, без тапа.
                PomodoroSound.play(context)
                persist(null, 0L, "work", if (mode == "long_break") 0 else cycle)
                startPhase("work", 25 * 60)
            }
        }
        runCatching { stats = TasksApi.pomodoroStats(client, config) }
    }

    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            now = System.currentTimeMillis()
        }
    }
    LaunchedEffect(sessionId, remaining) {
        if (sessionId != null && remaining <= 0) finishPhase(true)
    }
    LaunchedEffect(ready) {
        if (ready) runCatching { stats = TasksApi.pomodoroStats(client, config) }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (!ready) {
            Text("Сначала укажите адрес сервиса задач и токен.", style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(12.dp))
            Button(onClick = onGoSettings) { Text("Настроить") }
        } else {
            Text(
                when (mode) {
                    "work" -> "Работа"
                    "long_break" -> "Длинный перерыв"
                    else -> "Перерыв"
                },
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "%02d:%02d".format(remaining / 60, remaining % 60),
                style = MaterialTheme.typography.displayLarge,
            )
            Text(
                "цикл ${cycle % 4} из 4",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(24.dp))

            TomatoButton(
                diameter = 200.dp,
                running = sessionId != null,
                onClick = {
                    if (sessionId == null) {
                        // Тап запускает помодоро целиком: фазы и перерывы идут сами.
                        scope.launch {
                            persist(null, 0L, "work", 0)
                            startPhase("work", 25 * 60)
                        }
                    } else {
                        // Второй тап выключает помодоро полностью.
                        scope.launch {
                            val id = sessionId
                            persist(null, 0L, "work", 0)
                            if (id != null) runCatching { TasksApi.finishPomodoro(client, config, id, false) }
                        }
                    }
                },
            )

            Spacer(Modifier.height(16.dp))
            TextButton(onClick = {
                val id = sessionId
                persist(null, 0L, "work", 0)
                if (id != null) scope.launch {
                    runCatching { TasksApi.finishPomodoro(client, config, id, false) }
                        .onFailure { e -> e.rethrowIfCancellation() }
                }
            }) { Text("Сброс") }

            stats?.let {
                Spacer(Modifier.height(12.dp))
                Text(
                    "За 7 дней: ${it.sessions} сессий · ${it.seconds / 60} мин",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun TomatoButton(diameter: Dp, running: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.size(diameter).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val cx = w / 2f
            val cy = h * 0.58f
            val r = w * 0.40f
            // тело помидора
            drawCircle(Color(0xFFD32F2F), radius = r, center = Offset(cx, cy))
            // мягкий блик
            drawCircle(Color.White.copy(alpha = 0.16f), radius = r * 0.42f, center = Offset(cx - r * 0.35f, cy - r * 0.40f))
            // листик и черешок
            drawOval(Color(0xFF2E7D32), topLeft = Offset(cx - r * 0.48f, cy - r * 1.22f), size = Size(r * 0.96f, r * 0.46f))
            drawRect(Color(0xFF2E7D32), topLeft = Offset(cx - r * 0.03f, cy - r * 1.05f), size = Size(r * 0.06f, r * 0.30f))

            // play / pause
            if (running) {
                val barW = r * 0.12f
                val barH = r * 0.42f
                drawRoundRect(
                    Color.White,
                    topLeft = Offset(cx - barW * 1.6f, cy - barH / 2),
                    size = Size(barW, barH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2),
                )
                drawRoundRect(
                    Color.White,
                    topLeft = Offset(cx + barW * 0.6f, cy - barH / 2),
                    size = Size(barW, barH),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(barW / 2),
                )
            } else {
                val path = Path().apply {
                    moveTo(cx - r * 0.16f, cy - r * 0.30f)
                    lineTo(cx + r * 0.30f, cy)
                    lineTo(cx - r * 0.16f, cy + r * 0.30f)
                    close()
                }
                drawPath(path, Color.White)
            }
        }
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
