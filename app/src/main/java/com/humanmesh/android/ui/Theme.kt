package com.humanmesh.android.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val HumanMeshColors = darkColorScheme(
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

@Composable
fun HumanMeshTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = HumanMeshColors,
        typography = MaterialTheme.typography,
        content = content
    )
}
