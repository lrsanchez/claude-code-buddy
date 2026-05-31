package com.claude.buddy.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Typography

// Color tokens
val ClaudeBlack = Color(0xFF000000)
val ClaudeSurface = Color(0xFF0D0D0D)
val ClaudeCard = Color(0xFF1A1A1A)
val ClaudeCoral = Color(0xFFD97757)      // Claude brand accent
val ClaudeCoralDim = Color(0xFF8A4B37)
val ClaudeTeal = Color(0xFF4FC3F7)       // running
val ClaudeAmber = Color(0xFFFFB74D)      // waiting / attention
val ClaudeGreen = Color(0xFF66BB6A)      // approve
val ClaudeRed = Color(0xFFEF5350)        // deny
val ClaudeTextPrimary = Color(0xFFF0F0F0)
val ClaudeTextSecondary = Color(0xFF888888)
val ClaudeDivider = Color(0xFF2A2A2A)

private val DarkColorScheme = darkColorScheme(
    background = ClaudeBlack,
    surface = ClaudeSurface,
    surfaceVariant = ClaudeCard,
    primary = ClaudeCoral,
    secondary = ClaudeTeal,
    tertiary = ClaudeAmber,
    onBackground = ClaudeTextPrimary,
    onSurface = ClaudeTextPrimary,
    onPrimary = ClaudeBlack,
    error = ClaudeRed,
)

private val AppTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 48.sp,
        color = ClaudeTextPrimary,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        color = ClaudeTextPrimary,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontSize = 16.sp,
        color = ClaudeTextSecondary,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontSize = 12.sp,
        color = ClaudeTextSecondary,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        color = ClaudeTextSecondary,
    ),
)

@Composable
fun BuddyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = AppTypography,
        content = content,
    )
}
