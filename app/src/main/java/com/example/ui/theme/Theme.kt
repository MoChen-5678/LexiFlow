package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkAppColorScheme = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    background = Color(0xFF121214),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1E1D22),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF2E2B33),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF938F99),
    error = Color(0xFFF2B8B5)
)

private val AppColorScheme = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = BrandSurface,
    primaryContainer = BrandContainer,
    onPrimaryContainer = TextDarkPrimary,
    background = BrandBackground,
    onBackground = TextDarkPrimary,
    surface = BrandSurface,
    onSurface = TextDarkPrimary,
    surfaceVariant = BrandSurfaceContainer,
    onSurfaceVariant = TextDarkSecondary,
    outline = TextMuted,
    error = ErrorRed
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val scheme = if (darkTheme) DarkAppColorScheme else AppColorScheme
    MaterialTheme(
        colorScheme = scheme,
        typography = Typography,
        content = content
    )
}
