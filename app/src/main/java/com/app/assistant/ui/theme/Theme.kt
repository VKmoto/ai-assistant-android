package com.app.assistant.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Тема приложения.
 *
 * По скиллу android-design:
 *  • роли, а не hex: схемы лежат в Color.kt и сгенерированы Material Color Utilities
 *    из одного источника (#7B5E4B), поэтому светлая и тёмная согласованы по тону;
 *  • **dynamic color по умолчанию выключен**. Раньше тема брала цвета обоев: экран
 *    задач/чат могли стать неконтрастными и разными по настроению. Приложение —
 *    личный инструмент с узнаваемым тёплым характером, поэтому бренд важнее обоев;
 *    динамику можно включить флагом, если владелец захочет;
 *  • shape-роли — из шкалы M3 (4/8/12/16/28), компоненты читают их, а не сырые dp;
 *  • типографика — дефолтные токены M3 (Type.kt), без самодельных letterSpacing.
 */
@Composable
fun AssistantTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }

            darkTheme -> DarkColorScheme
            else -> LightColorScheme
        }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        shapes = Shapes(
            extraSmall = RoundedCornerShape(4.dp),
            small = RoundedCornerShape(8.dp),
            medium = RoundedCornerShape(12.dp),
            large = RoundedCornerShape(16.dp),
            extraLarge = RoundedCornerShape(28.dp),
        ),
        content = content,
    )
}
