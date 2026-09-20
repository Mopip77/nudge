package com.nudge.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.nudge.app.config.ThemeMode

private val LightColors = lightColorScheme(
    primary = Color(0xFF2B6CB0),
    background = Color(0xFFF7F7F8),
    surface = Color(0xFFFFFFFF),
    onBackground = Color(0xFF1A1A1A),
    onSurface = Color(0xFF1A1A1A),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FB3E8),
    background = Color(0xFF101114),
    surface = Color(0xFF1A1C20),
    onBackground = Color(0xFFE8E8EA),
    onSurface = Color(0xFFE8E8EA),
)

@Composable
fun NudgeTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
