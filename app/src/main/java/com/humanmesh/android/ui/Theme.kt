package com.humanmesh.android.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9B8AFB),
    onPrimary = Color(0xFF17122D),
    secondary = Color(0xFF6BE0C1),
    onSecondary = Color(0xFF08251D),
    background = Color(0xFF10111B),
    onBackground = Color(0xFFF6F4FF),
    surface = Color(0xFF191B29),
    onSurface = Color(0xFFF6F4FF),
    surfaceVariant = Color(0xFF272A3B),
    onSurfaceVariant = Color(0xFFB7B7CC),
    error = Color(0xFFFF8B9A)
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF6550C8),
    onPrimary = Color.White,
    secondary = Color(0xFF087F68),
    onSecondary = Color.White,
    background = Color(0xFFF7F7FC),
    onBackground = Color(0xFF191827),
    surface = Color.White,
    onSurface = Color(0xFF191827),
    surfaceVariant = Color(0xFFE9E8F3),
    onSurfaceVariant = Color(0xFF555469),
    error = Color(0xFFB3261E)
)

@Composable
fun HumanMeshTheme(mode: String = "system", content: @Composable () -> Unit) {
    val dark = when (mode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = MaterialTheme.typography,
        content = content
    )
}
