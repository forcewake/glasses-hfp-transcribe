package com.glasses.hfptranscribe.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp

val RecordingRed = Color(0xFFE23D2B)
val PhoneAmber = Color(0xFF8A5A00)
val GlassesGreen = Color(0xFF1F6B45)

private val DarkColors = darkColorScheme(
    primary = RecordingRed,
    onPrimary = Color.White,
    secondary = Color(0xFFE7C27A),
    onSecondary = Color(0xFF241C00),
    background = Color(0xFF121410),
    surface = Color(0xFF121410),
    surfaceVariant = Color(0xFF282B26),
    onBackground = Color(0xFFE6E3DA),
    onSurface = Color(0xFFE6E3DA),
    onSurfaceVariant = Color(0xFFC6C6B8),
    error = Color(0xFFFFB4AB),
    outline = Color(0xFF8C8F84),
)

private val LightColors = lightColorScheme(
    primary = RecordingRed,
    onPrimary = Color.White,
    secondary = Color(0xFF6B5420),
    onSecondary = Color.White,
    background = Color(0xFFF6F3EC),
    surface = Color(0xFFF6F3EC),
    surfaceVariant = Color(0xFFE7E2D6),
    onBackground = Color(0xFF1B1C17),
    onSurface = Color(0xFF1B1C17),
    onSurfaceVariant = Color(0xFF44483E),
    error = Color(0xFFBA1A1A),
    outline = Color(0xFF74776C),
)

private val AppType = Typography(
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontSize = 18.sp,
        lineHeight = 26.sp,
    ),
)

@Composable
fun GlassesTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = AppType,
        content = content,
    )
}
