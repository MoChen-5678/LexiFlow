package com.example.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private fun type(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
    fontFamily = FontFamily.SansSerif, fontWeight = weight, fontSize = size.sp,
    lineHeight = height.sp, letterSpacing = 0.sp
)

val Typography = Typography(
    displayLarge = type(48, 56, FontWeight.SemiBold),
    displayMedium = type(40, 48, FontWeight.SemiBold),
    displaySmall = type(34, 42, FontWeight.SemiBold),
    headlineLarge = type(30, 38, FontWeight.SemiBold),
    headlineMedium = type(26, 34, FontWeight.SemiBold),
    headlineSmall = type(23, 31, FontWeight.Medium),
    titleLarge = type(20, 28, FontWeight.Medium),
    titleMedium = type(16, 24, FontWeight.Medium),
    titleSmall = type(14, 20, FontWeight.Medium),
    bodyLarge = type(17, 27),
    bodyMedium = type(15, 23),
    bodySmall = type(13, 20),
    labelLarge = type(14, 20, FontWeight.Medium),
    labelMedium = type(12, 18, FontWeight.Medium),
    labelSmall = type(11, 16, FontWeight.Medium)
)
