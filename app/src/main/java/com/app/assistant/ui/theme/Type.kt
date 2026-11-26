package com.app.assistant.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Типографика — токены Material 3.
 *
 * Раньше здесь переопределялся только `bodyLarge` (letterSpacing 0.5sp), остальные роли
 * брались из библиотеки: получался «свой» стиль на одном размере и стандартный на
 * остальных. Скилл android-design прямо просит не переписывать веса и трекинг вручную,
 * а брать токены — так делает иерархия предсказуемой при масштабировании шрифта
 * (Android 14+: до 200%).
 *
 * bodyLarge задан явно только чтобы зафиксировать line-height 24sp (требование
 * читаемости), всё остальное — библиотечные значения.
 */
val Typography =
    Typography(
        bodyLarge =
            TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 24.sp,
                letterSpacing = 0.sp,
            ),
    )
