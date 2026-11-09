package com.example.ui.theme

import androidx.compose.material3.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val Light = lightColorScheme(
    primary = BrandPrimary, onPrimary = Color.White,
    primaryContainer = BrandContainer, onPrimaryContainer = Color(0xFF07545E),
    secondary = Color(0xFF7854A5), onSecondary = Color.White,
    secondaryContainer = Color(0xFFF0E8FA), onSecondaryContainer = Color(0xFF5C397C),
    tertiary = CorrectGreen, onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE4F3EA), onTertiaryContainer = Color(0xFF216344),
    background = BrandBackground, onBackground = TextDarkPrimary,
    surface = BrandSurface, onSurface = TextDarkPrimary,
    surfaceVariant = BrandSurfaceContainer, onSurfaceVariant = TextDarkSecondary,
    outline = Color(0xFF8993A5), outlineVariant = TextMuted,
    error = ErrorRed, errorContainer = Color(0xFFFCEAEA), onErrorContainer = Color(0xFF8F3030),
    surfaceContainer = Color(0xFFF9FAFC), surfaceContainerLow = Color.White
)
private val Dark = darkColorScheme(
    primary = Color(0xFF80CBD2), onPrimary = Color(0xFF073D43),
    primaryContainer = Color(0xFF164F57), onPrimaryContainer = Color(0xFFCCEDF0),
    secondary = Color(0xFFD4B8F1), secondaryContainer = Color(0xFF44315D),
    onSecondaryContainer = Color(0xFFEEDDFB),
    tertiary = Color(0xFF86D4AF), tertiaryContainer = Color(0xFF1E4435),
    onTertiaryContainer = Color(0xFFD0EFDF),
    background = Color(0xFF151B26), onBackground = Color(0xFFE7EAF1),
    surface = Color(0xFF202837), onSurface = Color(0xFFE7EAF1),
    surfaceVariant = Color(0xFF2A3546), onSurfaceVariant = Color(0xFFB5BFD0),
    outline = Color(0xFF8D99AC), outlineVariant = Color(0xFF3D495E),
    error = Color(0xFFF3ACAA), errorContainer = Color(0xFF4F282D), onErrorContainer = Color(0xFFF8DADC),
    surfaceContainer = Color(0xFF202837), surfaceContainerLow = Color(0xFF202837)
)

@Composable
fun MyApplicationTheme(darkTheme: Boolean = false, dynamicColor: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (darkTheme) Dark else Light, typography = Typography,
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(20.dp), extraLarge = RoundedCornerShape(28.dp)),
        content = content)
}
