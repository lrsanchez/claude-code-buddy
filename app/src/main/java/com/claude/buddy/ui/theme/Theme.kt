package com.claude.buddy.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Typography

// ── Color tokens ──────────────────────────────────────────────────────────────

data class BuddyColors(
    val background: Color,
    val surface: Color,
    val card: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val divider: Color,
    val coral: Color,
    val coralDim: Color,
    val teal: Color,
    val amber: Color,
    val green: Color,
    val red: Color,
    val onCoral: Color,       // text/icons placed on coral backgrounds
    val isLight: Boolean,
)

val DarkBuddyColors = BuddyColors(
    background  = Color(0xFF000000),
    surface     = Color(0xFF0D0D0D),
    card        = Color(0xFF1A1A1A),
    textPrimary = Color(0xFFF0F0F0),
    textSecondary = Color(0xFF888888),
    divider     = Color(0xFF2A2A2A),
    coral       = Color(0xFFD97757),
    coralDim    = Color(0xFF8A4B37),
    teal        = Color(0xFF4FC3F7),
    amber       = Color(0xFFFFB74D),
    green       = Color(0xFF66BB6A),
    red         = Color(0xFFEF5350),
    onCoral     = Color(0xFF000000),
    isLight     = false,
)

val LightBuddyColors = BuddyColors(
    background  = Color(0xFFFFFFFF),
    surface     = Color(0xFFF8F8F8),
    card        = Color(0xFFEEEEEE),
    textPrimary = Color(0xFF1A1A1A),
    textSecondary = Color(0xFF555555),
    divider     = Color(0xFFCCCCCC),
    coral       = Color(0xFFB85C35),  // darker for contrast on white
    coralDim    = Color(0xFF7A3D24),
    teal        = Color(0xFF0277BD),
    amber       = Color(0xFFE65100),
    green       = Color(0xFF2E7D32),
    red         = Color(0xFFC62828),
    onCoral     = Color(0xFFFFFFFF),
    isLight     = true,
)

val LocalBuddyColors = compositionLocalOf { DarkBuddyColors }

// Convenience aliases — use these everywhere in UI code
val BuddyColors.ClaudeBlack      get() = background
val BuddyColors.ClaudeSurface    get() = surface
val BuddyColors.ClaudeCard       get() = card
val BuddyColors.ClaudeTextPrimary   get() = textPrimary
val BuddyColors.ClaudeTextSecondary get() = textSecondary
val BuddyColors.ClaudeDivider    get() = divider
val BuddyColors.ClaudeCoral      get() = coral
val BuddyColors.ClaudeCoralDim   get() = coralDim
val BuddyColors.ClaudeTeal       get() = teal
val BuddyColors.ClaudeAmber      get() = amber
val BuddyColors.ClaudeGreen      get() = green
val BuddyColors.ClaudeRed        get() = red

// ── Material color schemes ────────────────────────────────────────────────────

private val DarkColorScheme = darkColorScheme(
    background      = Color(0xFF000000),
    surface         = Color(0xFF0D0D0D),
    surfaceVariant  = Color(0xFF1A1A1A),
    primary         = Color(0xFFD97757),
    secondary       = Color(0xFF4FC3F7),
    tertiary        = Color(0xFFFFB74D),
    onBackground    = Color(0xFFF0F0F0),
    onSurface       = Color(0xFFF0F0F0),
    onPrimary       = Color(0xFF000000),
    error           = Color(0xFFEF5350),
)

private val LightColorScheme = lightColorScheme(
    background      = Color(0xFFFFFFFF),
    surface         = Color(0xFFF8F8F8),
    surfaceVariant  = Color(0xFFEEEEEE),
    primary         = Color(0xFFB85C35),
    secondary       = Color(0xFF0277BD),
    tertiary        = Color(0xFFE65100),
    onBackground    = Color(0xFF1A1A1A),
    onSurface       = Color(0xFF1A1A1A),
    onPrimary       = Color(0xFFFFFFFF),
    error           = Color(0xFFC62828),
)

// ── Typography ────────────────────────────────────────────────────────────────

private val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Bold,   fontSize = 48.sp),
    headlineLarge = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.SemiBold, fontSize = 28.sp),
    bodyLarge  = TextStyle(fontFamily = FontFamily.Default, fontSize = 16.sp),
    bodySmall  = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Default, fontWeight = FontWeight.Medium, fontSize = 11.sp),
)

// ── Theme ─────────────────────────────────────────────────────────────────────

@Composable
fun BuddyTheme(isLightMode: Boolean = false, content: @Composable () -> Unit) {
    val buddyColors = if (isLightMode) LightBuddyColors else DarkBuddyColors
    CompositionLocalProvider(LocalBuddyColors provides buddyColors) {
        MaterialTheme(
            colorScheme = if (isLightMode) LightColorScheme else DarkColorScheme,
            typography  = AppTypography,
            content     = content,
        )
    }
}
