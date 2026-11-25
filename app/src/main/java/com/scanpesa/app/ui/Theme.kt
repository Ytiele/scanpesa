package com.scanpesa.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val Green = Color(0xFF00A650)
val GreenDark = Color(0xFF007A3B)
val GreenLight = Color(0xFFE3F5EA)
val Danger = Color(0xFFC62828)
val WarnLight = Color(0xFFFFF1E6)

/** Spacing scale used everywhere, so screens breathe the same way. */
object Space {
    val xs = 6.dp
    val s = 12.dp
    val m = 20.dp
    val l = 28.dp
    val xl = 40.dp
    /** Side padding of every screen. */
    val screen = 24.dp
}

private val Light = lightColorScheme(
    primary = Green,
    onPrimary = Color.White,
    primaryContainer = GreenLight,
    onPrimaryContainer = Color(0xFF00391A),
    secondary = GreenDark,
    onSecondary = Color.White,
    secondaryContainer = GreenLight,
    onSecondaryContainer = Color(0xFF00391A),
    error = Danger,
    background = Color(0xFFF6F8F6),
    onBackground = Color(0xFF151A16),
    surface = Color.White,
    onSurface = Color(0xFF151A16),
    surfaceVariant = Color(0xFFEDF2EE),
    onSurfaceVariant = Color(0xFF5F6B63),
    outlineVariant = Color(0xFFE1E7E2),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF5BD98C),
    onPrimary = Color(0xFF00391A),
    primaryContainer = Color(0xFF0F3B23),
    onPrimaryContainer = GreenLight,
    secondary = Color(0xFF9BE5B4),
    secondaryContainer = Color(0xFF0F3B23),
    onSecondaryContainer = GreenLight,
    error = Color(0xFFFF8A80),
    background = Color(0xFF0F1411),
    surface = Color(0xFF171D19),
    surfaceVariant = Color(0xFF222A25),
    onSurfaceVariant = Color(0xFFA9B5AD),
    outlineVariant = Color(0xFF2C3530),
)

private val AppTypography = Typography().let { t ->
    t.copy(
        displaySmall = t.displaySmall.copy(fontWeight = FontWeight.Bold, fontSize = 34.sp, lineHeight = 40.sp),
        headlineMedium = t.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 17.sp),
        bodyLarge = t.bodyLarge.copy(fontSize = 17.sp, lineHeight = 25.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp),
        labelLarge = t.labelLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold),
        labelMedium = t.labelMedium.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp),
    )
}

private val AppShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
)

@Composable
fun ScanPesaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
