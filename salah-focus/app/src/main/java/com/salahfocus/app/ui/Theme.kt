package com.salahfocus.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

val Teal = Color(0xFF0F3D3E)
val TealLight = Color(0xFF1F6F6B)
val Gold = Color(0xFFE8C26A)
val Sand = Color(0xFFF7F5EF)

private val colors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    secondary = Gold,
    onSecondary = Teal,
    background = Sand,
    surface = Color.White,
    surfaceVariant = Color(0xFFEAE6DA),
    primaryContainer = Color(0xFFD5EBE8),
    onPrimaryContainer = Teal,
)

@Composable
fun SalahTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors) {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background, content = content)
        }
    }
}
