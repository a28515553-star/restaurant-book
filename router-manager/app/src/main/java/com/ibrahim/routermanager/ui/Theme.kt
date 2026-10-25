package com.ibrahim.routermanager.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val BlockedRed = Color(0xFFC62828)
val OnlineGreen = Color(0xFF2E7D32)

private val colors = lightColorScheme(
    primary = Color(0xFF1565C0),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3E3FD),
    onPrimaryContainer = Color(0xFF0B2A5B),
    background = Color(0xFFF7F9FC),
    surface = Color(0xFFF7F9FC),
    surfaceVariant = Color(0xFFE6EBF3),
)

@Composable
fun RouterTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
