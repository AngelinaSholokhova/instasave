package com.instasave.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF3B5BDB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDEE4FF),
    onPrimaryContainer = Color(0xFF10237E),
    secondary = Color(0xFF7A5BC0),
    background = Color(0xFFF7F8FC),
    onBackground = Color(0xFF1A1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1A1C1E),
    error = Color(0xFFD32F2F),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFAEBFFF),
    onPrimary = Color(0xFF10237E),
    primaryContainer = Color(0xFF22359C),
    onPrimaryContainer = Color(0xFFDEE4FF),
    secondary = Color(0xFFD0BCFF),
    background = Color(0xFF12141D),
    onBackground = Color(0xFFE3E6EF),
    surface = Color(0xFF1A1D29),
    onSurface = Color(0xFFE3E6EF),
    error = Color(0xFFFF7A7A),
)

@Composable
fun InstaSaveTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}