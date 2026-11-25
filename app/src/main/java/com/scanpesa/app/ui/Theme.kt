package com.scanpesa.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Green = Color(0xFF00A650)
val GreenDark = Color(0xFF007A3B)
val GreenLight = Color(0xFFDDF5E6)
val Danger = Color(0xFFC62828)

private val Light = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = GreenLight,
    onPrimaryContainer = Color(0xFF00391A),
    secondary = GreenDark,
    onSecondary = Color.White,
    error = Danger,
    background = Color(0xFFF8FBF8),
    surface = Color.White,
)

private val Dark = darkColorScheme(
    primary = Color(0xFF5BD98C),
    onPrimary = Color(0xFF00391A),
    primaryContainer = Color(0xFF00522A),
    onPrimaryContainer = GreenLight,
    secondary = Color(0xFF9BE5B4),
    error = Color(0xFFFF8A80),
)

private val BigTypography = Typography().let { t ->
    t.copy(
        bodyLarge = t.bodyLarge.copy(fontSize = 18.sp, lineHeight = 26.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 16.sp, lineHeight = 23.sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.Bold),
        labelLarge = TextStyle(fontSize = 18.sp, fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun ScanPesaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = BigTypography,
        content = content,
    )
}
