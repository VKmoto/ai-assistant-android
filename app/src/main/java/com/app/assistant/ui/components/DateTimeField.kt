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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
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
    var selected by remember { mutableIntStateOf(0) }
    // pointerInput живёт дольше одной рекомпозиции, поэтому замыкание на `value`
    // «застревало»: после первого шага дальнейшие считались от старого значения и
    // перезаписывали его — свайп останавливался через пару итераций.
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onValueChange)

    fun bump(index: Int, delta: Int) {
        val v = current
        val newValue = when (index) {
            0 -> v.copy(day = ((v.day - 1 + delta).mod(v.daysInMonth())) + 1)
            1 -> {
                val month = ((v.month - 1 + delta).mod(12)) + 1
                v.copy(month = month, day = v.day.coerceAtMost(DateTimeValue.daysIn(v.year, month)))
            }
            2 -> v.copy(year = (v.year + delta).coerceIn(2000, 2100))
            3 -> v.copy(hour = (v.hour + delta).mod(24))
            else -> v.copy(minute = (v.minute + delta * 15).mod(60))
        }
        change(newValue)
    }

    // Центры сегментов в координатах поля: тап определяется по x, чтобы тап и свайп
    // жили в ОДНОМ pointerInput поля. Раньше тап обрабатывался на сегменте, и жест,
    // начатый на нём, до поля не доходил — после выбора позиции тапом свайп не менял
    // значение (баг владельца 2026-11-27).
    val centers = remember { FloatArray(5) }

    // +20% к прежней скорости: 44 dp → 37 dp на шаг.
    val stepPx = with(LocalDensity.current) { 37.dp.toPx() }

    Row(
        modifier = modifier
            // Свайп работает в любом месте поля, а не только по выделенной позиции:
            // позиция уже выбрана тапом, и целиться в неё пальцем не нужно.
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    // Ближайший центр сегмента — и есть выбранная позиция.
                    var best = 0
                    var bestDist = Float.MAX_VALUE
                    centers.forEachIndexed { i, c ->
                        val d = kotlin.math.abs(offset.x - c)
                        if (d < bestDist) { bestDist = d; best = i }
                    }
                    selected = best
                }
            }
            .pointerInput(Unit) {
                var acc = 0f
                detectVerticalDragGestures(
                    onDragEnd = { acc = 0f },
                    onDragCancel = { acc = 0f },
                ) { changeEvent, dy ->
                    changeEvent.consume()
                    // Вверх (dy < 0) — увеличить, вниз — уменьшить. 44 dp на шаг:
                    // спокойный темп, но длинный свайп всё равно идёт без остановки.
                    acc += dy
                    while (acc <= -stepPx) {
                        acc += stepPx
                        bump(selected, 1)
                    }
                    while (acc >= stepPx) {
                        acc -= stepPx
                        bump(selected, -1)
                    }
                }
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Segment(0, "%02d".format(value.day), selected) { x -> centers[0] = x }
        Separator(".")
        Segment(1, "%02d".format(value.month), selected) { x -> centers[1] = x }
        Separator(".")
        Segment(2, "%04d".format(value.year), selected) { x -> centers[2] = x }
        Separator(" ")
        Segment(3, "%02d".format(value.hour), selected) { x -> centers[3] = x }
        Separator(":")
        Segment(4, "%02d".format(value.minute), selected) { x -> centers[4] = x }
    }
}

@Composable
private fun Separator(text: String) {
    Text(text, fontSize = 20.sp, fontWeight = FontWeight.Medium)
}

/** Позиция: сообщает свой центр в координатах поля; тап и свайп ловит контейнер. */
@Composable
private fun Segment(index: Int, text: String, selected: Int, onCenter: (Float) -> Unit) {
    val isSelected = selected == index
    // Подсветка совсем лёгкая: тёмная плашка перекрывала цифры.
    val highlight = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f) else Color.Transparent

    Box(
        modifier = Modifier
            .background(highlight, RoundedCornerShape(8.dp))
            .padding(horizontal = 6.dp, vertical = 8.dp)
            .onGloballyPositioned { coords ->
                onCenter(coords.positionInParent().x + coords.size.width / 2f)
            },
    ) {
        Text(
            text,
            fontSize = 18.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}
