package com.app.assistant.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.app.assistant.data.TaskItem
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** День в поясе владельца (МСК). */
data class MskDay(val year: Int, val month0: Int, val day: Int)

/** Месяц в поясе владельца (МСК). */
data class MskMonth(val year: Int, val month0: Int)

/**
 * Разбор сроков задач для календаря.
 *
 * java.time здесь недоступен: minSdk 24, а desugaring не включён. Поэтому — обычный
 * Calendar, и МСК считается жёстким сдвигом +03:00: в России переходов на летнее время
 * нет с 2014 года, так что правило верное и не зависит от базы tz (её может не быть на
 * старых прошивках).
 */
object TaskDates {
    private val MSK: TimeZone = TimeZone.getTimeZone("Europe/Moscow")
    private val MONTHS = arrayOf(
        "января", "февраля", "марта", "апреля", "мая", "июня",
        "июля", "августа", "сентября", "октября", "ноября", "декабря",
    )
    private val MONTHS_NOM = arrayOf(
        "Январь", "Февраль", "Март", "Апрель", "Май", "Июнь",
        "Июль", "Август", "Сентябрь", "Октябрь", "Ноябрь", "Декабрь",
    )
    private val WEEKDAYS = arrayOf("воскресенье", "понедельник", "вторник", "среда", "четверг", "пятница", "суббота")
    val WEEKDAY_SHORT = arrayOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс")

    data class Stamp(
        val day: MskDay,
        val hour: Int,
        val minute: Int,
        /** false — срок без времени (полночь): показываем в блоке «Весь день». */
        val hasTime: Boolean,
    )

    fun todayMsk(): MskDay {
        val cal = Calendar.getInstance(MSK)
        return MskDay(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH), cal.get(Calendar.DAY_OF_MONTH))
    }

    fun currentMonth(): MskMonth {
        val t = todayMsk()
        return MskMonth(t.year, t.month0)
    }

    fun monthLabel(m: MskMonth): String = "${MONTHS_NOM[m.month0]} ${m.year}"

    fun dayLabel(d: MskDay): String {
        val cal = Calendar.getInstance(MSK).apply { clear(); set(d.year, d.month0, d.day) }
        return "${d.day} ${MONTHS[d.month0]}, ${WEEKDAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]}"
    }

    fun daysInMonth(m: MskMonth): Int {
        val cal = Calendar.getInstance(MSK).apply { clear(); set(m.year, m.month0, 1) }
        return cal.getActualMaximum(Calendar.DAY_OF_MONTH)
    }

    /** Смещение первого дня месяца от понедельника: Пн = 0 … Вс = 6. */
    fun firstWeekdayIndex(m: MskMonth): Int {
        val cal = Calendar.getInstance(MSK).apply { clear(); set(m.year, m.month0, 1) }
        return (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
    }

    fun plusMonths(m: MskMonth, delta: Int): MskMonth {
        val cal = Calendar.getInstance(MSK).apply { clear(); set(m.year, m.month0, 1) }
        cal.add(Calendar.MONTH, delta)
        return MskMonth(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH))
    }

    fun timeLabel(hour: Int, minute: Int): String = String.format(Locale.US, "%02d:%02d", hour, minute)

    /** Разбор ISO-строки сервера (`2026-12-01T06:00:00.000Z` или `… 06:00:00+00`). */
    fun parse(iso: String?): Stamp? {
        if (iso.isNullOrBlank() || iso.length < 16) return null
        val s = iso.replace(' ', 'T')
        val year = s.substring(0, 4).toIntOrNull() ?: return null
        val month = s.substring(5, 7).toIntOrNull() ?: return null
        val day = s.substring(8, 10).toIntOrNull() ?: return null
        val hour = s.substring(11, 13).toIntOrNull() ?: 0
        val minute = s.substring(14, 16).toIntOrNull() ?: 0
        val tail = s.substring(19)
        val offset = when {
            tail.startsWith("Z") || tail.isEmpty() -> 0
            tail.startsWith("+") || tail.startsWith("-") -> {
                val hours = tail.substring(1, 3).toIntOrNull() ?: 0
                if (tail.startsWith("-")) -hours else hours
            }
            else -> 0
        }
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, day, hour, minute)
        }
        cal.add(Calendar.HOUR_OF_DAY, 3 - offset)
        return Stamp(
            day = MskDay(
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH),
            ),
            hour = cal.get(Calendar.HOUR_OF_DAY),
            minute = cal.get(Calendar.MINUTE),
            hasTime = !(hour == 0 && minute == 0),
        )
    }
}

private fun TaskItem.dueStamp(): TaskDates.Stamp? = TaskDates.parse(dueAt)

/** Месяц сеткой, как в Google Календаре: дни с задачами — ссылки. */
@Composable
fun TasksCalendar(
    tasks: List<TaskItem>,
    month: MskMonth,
    onMonthChange: (MskMonth) -> Unit,
    onDayClick: (MskDay) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Дни месяца, на которые что-то стоит. Считаем один раз на (задачи, месяц):
    // пересчёт в композиции каждого дня — лишняя работа ради ничего.
    val marked = tasks.mapNotNull { it.dueStamp()?.day }
        .filter { it.year == month.year && it.month0 == month.month0 }
        .map { it.day }
        .toSet()
    val today = TaskDates.todayMsk()
    val days = TaskDates.daysInMonth(month)
    val first = TaskDates.firstWeekdayIndex(month)
    val cells = first + days
    val rows = (cells + 6) / 7

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(onClick = { onMonthChange(TaskDates.plusMonths(month, -1)) }) {
                Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "Предыдущий месяц")
            }
            Text(
                TaskDates.monthLabel(month),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            IconButton(onClick = { onMonthChange(TaskDates.plusMonths(month, 1)) }) {
                Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "Следующий месяц")
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            TaskDates.WEEKDAY_SHORT.forEach { label ->
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                )
            }
        }

        for (row in 0 until rows) {
            Row(modifier = Modifier.fillMaxWidth()) {
                for (col in 0 until 7) {
                    val index = row * 7 + col
                    val dayNumber = index - first + 1
                    if (dayNumber < 1 || dayNumber > days) {
                        Spacer(Modifier.weight(1f).height(48.dp))
                    } else {
                        val day = MskDay(month.year, month.month0, dayNumber)
                        DayCell(
                            day = dayNumber,
                            marked = marked.contains(dayNumber),
                            today = day == today,
                            onClick = { onDayClick(day) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DayCell(
    day: Int,
    marked: Boolean,
    today: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.height(48.dp).padding(3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(10.dp))
                .then(
                    if (marked) {
                        Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f))
                    } else {
                        Modifier
                    },
                )
                .then(
                    if (today) {
                        Modifier.border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp))
                    } else {
                        Modifier
                    },
                )
                .clickable(onClick = onClick),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                day.toString(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (today || marked) FontWeight.SemiBold else FontWeight.Normal,
                color = if (marked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            )
            if (marked) {
                Box(
                    modifier = Modifier
                        .padding(top = 2.dp)
                        .size(4.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
    }
}

/** День по часам: то, что открывается тапом по числу в календаре. */
@Composable
fun TasksDaySchedule(
    tasks: List<TaskItem>,
    day: MskDay,
    onBack: () -> Unit,
    onTaskClick: (TaskItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    val stamps = tasks.mapNotNull { task -> task.dueStamp()?.let { task to it } }
        .filter { (_, s) -> s.day == day }
    val allDay = stamps.filter { !it.second.hasTime }.map { it.first }
    val byHour = stamps.filter { it.second.hasTime }.groupBy { it.second.hour }.toSortedMap()

    Column(modifier = modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "К месяцу") }
            Text(
                TaskDates.dayLabel(day),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp)) {
            if (allDay.isNotEmpty()) {
                item {
                    Text(
                        "Весь день",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    allDay.forEach { task ->
                        DayTaskRow(task = task, time = "—", onClick = { onTaskClick(task) })
                    }
                }
            }
            // Полные сутки: пустые часы тоже нужны — это сетка дня, а не список задач.
            items((0..23).toList()) { hour ->
                Row(modifier = Modifier.fillMaxWidth().height(44.dp)) {
                    Text(
                        String.format(Locale.US, "%02d:00", hour),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(48.dp).padding(top = 4.dp),
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                        byHour[hour]?.forEach { (task, stamp) ->
                            DayTaskRow(
                                task = task,
                                time = TaskDates.timeLabel(stamp.hour, stamp.minute),
                                onClick = { onTaskClick(task) },
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun DayTaskRow(task: TaskItem, time: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            time,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(40.dp),
        )
        Text(
            task.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 2,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
