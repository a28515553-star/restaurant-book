package com.ibrahim.restaurantbook.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val ProfitGreen = Color(0xFF2E7D32)
val LossRed = Color(0xFFC62828)
val ExpenseOrange = Color(0xFFE65100)
val SalaryBlue = Color(0xFF1565C0)

private val colors = lightColorScheme(
    primary = Color(0xFF2E7D32),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC8E6C9),
    onPrimaryContainer = Color(0xFF0B3D0E),
    secondary = Color(0xFF6D4C41),
    background = Color(0xFFFAFAF7),
    surface = Color(0xFFFAFAF7),
    surfaceVariant = Color(0xFFEDEDE6),
)

@Composable
fun RestaurantTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
