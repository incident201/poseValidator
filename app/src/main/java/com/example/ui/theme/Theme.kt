package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val ElegantDarkColorScheme = darkColorScheme(
  primary = DarkPrimary,
  onPrimary = DarkOnPrimary,
  secondary = DarkSecondary,
  onSecondary = DarkOnSecondary,
  background = DarkBg,
  onBackground = DarkOnSecondary,
  surface = DarkSurface,
  onSurface = DarkOnSecondary,
  tertiary = DarkTertiary
)

@Composable
fun MyApplicationTheme(
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = ElegantDarkColorScheme,
    typography = Typography,
    content = content
  )
}
