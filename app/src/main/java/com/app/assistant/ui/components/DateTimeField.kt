package com.app.assistant.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Дата и время как пять отдельных позиций: день, месяц, год, часы, минуты.
 *
 * Почему не текстовое поле: владелец просил менять значение свайпом по позиции —
 * тап выбирает позицию, свайп вверх/вниз увеличивает/уменьшает её. За один длинный
 * свайп значение меняется несколько раз (порог 26 dp), минуты идут шагом 15,
 * остальные позиции — шагом 1. Это ближе к «колесу» и не требует стирать строку.
 *
 * Значение всегда трактуется в поясе владельца (МСК, +03:00): API и база — в UTC,
 * конвертация на границе (toIso/fromIso), а не в UI.
 */
data class DateTimeValue(
    val year: Int,
    val month: Int,
    val day: Int,
    val hour: Int,
    val minute: Int,
) {
    /** ISO с зоной владельца: 2026-11-27T09:00:00+03:00. */
    fun toIso(): String = "%04d-%02d-%02dT%02d:%02d:00+03:00".format(year, month, day, hour, minute)

    fun daysInMonth(): Int = daysIn(year, month)

    companion object {
        private val zone = TimeZone.getTimeZone("Europe/Moscow")

        fun nowMsk(): DateTimeValue {
            val c = Calendar.getInstance(zone)
            return DateTimeValue(
                c.get(Calendar.YEAR),
                c.get(Calendar.MONTH) + 1,
                c.get(Calendar.DAY_OF_MONTH),
                c.get(Calendar.HOUR_OF_DAY),
                c.get(Calendar.MINUTE),
            )
        }

        /** UTC ISO из API → локальное значение (МСК). null, если разобрать не удалось. */
        fun fromIso(iso: String?): DateTimeValue? {
            if (iso.isNullOrBlank()) return null
            return runCatching {
                val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }
                val date: Date = parser.parse(iso) ?: return null
                val c = Calendar.getInstance(zone).apply { time = date }
                DateTimeValue(
                    c.get(Calendar.YEAR),
                    c.get(Calendar.MONTH) + 1,
                    c.get(Calendar.DAY_OF_MONTH),
                    c.get(Calendar.HOUR_OF_DAY),
                    c.get(Calendar.MINUTE),
                )
            }.getOrNull()
        }

        fun daysIn(year: Int, month: Int): Int =
            Calendar.getInstance(TimeZone.getTimeZone("UTC")).run {
                set(year, month - 1, 1)
                getActualMaximum(Calendar.DAY_OF_MONTH)
            }
    }
}

private val SEGMENTS = listOf("день", "месяц", "год", "часы", "минуты")

@Composable
fun DateTimeField(
    value: DateTimeValue,
    onValueChange: (DateTimeValue) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Выбранная позиция: тап выбирает, свайп меняет именно её.
    var selected by remember { mutableIntStateOf(0) }

    fun bump(index: Int, delta: Int) {
        val step = if (index == 4) 15 else 1
        val v = value
        when (index) {
            // День ограничен длиной месяца: 31.02 не появляется.
            0 -> {
                val dim = v.daysInMonth()
                val d = ((v.day - 1 + delta).mod(dim)) + 1
                onValueChange(v.copy(day = d))
            }
            1 -> onValueChange(v.copy(month = ((v.month - 1 + delta).mod(12)) + 1, day = v.day.coerceAtMost(DateTimeValue.daysIn(v.year, ((v.month - 1 + delta).mod(12)) + 1))))
            2 -> onValueChange(v.copy(year = (v.year + delta).coerceIn(2000, 2100)))
            3 -> onValueChange(v.copy(hour = (v.hour + delta).mod(24)))
            else -> onValueChange(v.copy(minute = (v.minute + delta * step).mod(60)))
        }
    }

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        Segment(0, "%02d".format(value.day), selected, onSelect = { selected = it }, onChange = { bump(0, it) })
        Separator(".")
        Segment(1, "%02d".format(value.month), selected, onSelect = { selected = it }, onChange = { bump(1, it) })
        Separator(".")
        Segment(2, "%04d".format(value.year), selected, onSelect = { selected = it }, onChange = { bump(2, it) })
        Separator(" ")
        Segment(3, "%02d".format(value.hour), selected, onSelect = { selected = it }, onChange = { bump(3, it) })
        Separator(":")
        Segment(4, "%02d".format(value.minute), selected, onSelect = { selected = it }, onChange = { bump(4, it) })
    }
}

@Composable
private fun Separator(text: String) {
    Text(text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
}

/**
 * Одна позиция: тап — выбрать, свайп вверх/вниз — изменить.
 * Шаг изменения 26 dp: длинный свайп проходит несколько порогов и меняет значение
 * несколько раз, короткий — один.
 */
@Composable
private fun Segment(
    index: Int,
    text: String,
    selected: Int,
    onSelect: (Int) -> Unit,
    onChange: (Int) -> Unit,
) {
    val stepPx = with(LocalDensity.current) { 26.dp.toPx() }
    var accumulated by remember { mutableIntStateOf(0) }
    // 18sp вместо 16sp: владелец просил шрифт даты/времени чуть крупнее.
    val highlight = if (selected == index) MaterialTheme.colorScheme.primaryContainer else Color.Transparent

    Box(
        modifier = Modifier
            .background(highlight, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 8.dp)
            .pointerInput(Unit) {
                detectTapGestures { onSelect(index) }
            }
            .pointerInput(Unit) {
                var acc = 0f
                detectVerticalDragGestures(
                    onDragStart = { onSelect(index) },
                    onDragEnd = { acc = 0f },
                    onDragCancel = { acc = 0f },
                ) { change, dy ->
                    change.consume()
                    acc += dy
                    // Пока палец идёт, каждые 26 dp меняем на шаг — «несколько раз за свайп».
                    while (acc >= stepPx) {
                        acc -= stepPx
                        onChange(1)
                    }
                    while (acc <= -stepPx) {
                        acc += stepPx
                        onChange(-1)
                    }
                }
            },
    ) {
        Text(
            text,
            fontSize = 18.sp,
            fontWeight = if (selected == index) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
