package com.cozycat.launcher

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Cream = Color(0xFFF7EBDD)
val Latte = Color(0xFFFFF4E8)
val Ink = Color(0xFF3A2A28)
val Glow = Color(0xFFFFC97A)

@Composable
fun CozyCatTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Glow,
            background = Ink,
            surface = Ink,
            onBackground = Latte,
            onSurface = Latte,
        ),
        content = content,
    )
}
