package com.isl.soundforge.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ─────────────────────────────────────────────────────────────────────────────
// Color palette — ISL cyberpunk
// ─────────────────────────────────────────────────────────────────────────────

object IslColors {
    val Void         = Color(0xFF06060C)
    val Surface      = Color(0xFF0D0D1A)
    val SurfaceHigh  = Color(0xFF141428)
    val Cyan         = Color(0xFF00F0FF)
    val CyanDim      = Color(0x8000F0FF)
    val Magenta      = Color(0xFFFF00AA)
    val MagentaDim   = Color(0x80FF00AA)
    val TextPrimary  = Color(0xFFE0E0FF)
    val TextSecondary= Color(0x99B0B0CC)
    val TextDisabled = Color(0x4DE0E0FF)
    val Success      = Color(0xFF00FF88)
    val Warning      = Color(0xFFFFCC00)
    val Error        = Color(0xFFFF3355)
    val GridLine     = Color(0x1AFFFFFF)
}

private val ColorScheme = darkColorScheme(
    primary            = IslColors.Cyan,
    onPrimary          = IslColors.Void,
    primaryContainer   = IslColors.SurfaceHigh,
    onPrimaryContainer = IslColors.Cyan,
    secondary          = IslColors.Magenta,
    onSecondary        = IslColors.Void,
    background         = IslColors.Void,
    onBackground       = IslColors.TextPrimary,
    surface            = IslColors.Surface,
    onSurface          = IslColors.TextPrimary,
    surfaceVariant     = IslColors.SurfaceHigh,
    onSurfaceVariant   = IslColors.TextSecondary,
    error              = IslColors.Error,
    onError            = IslColors.Void,
    outline            = IslColors.CyanDim
)

// ─────────────────────────────────────────────────────────────────────────────
// Extended colors accessible via SoundForgeTheme.colors
// ─────────────────────────────────────────────────────────────────────────────

@Immutable
data class ExtendedColors(
    val background: Color,
    val surface: Color,
    val surfaceElevated: Color,
    val cyan: Color,
    val cyanDim: Color,
    val magenta: Color,
    val magentaDim: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val success: Color,
    val warning: Color,
    val gridLine: Color
)

private val islExtended = ExtendedColors(
    background      = IslColors.Void,
    surface         = IslColors.Surface,
    surfaceElevated = IslColors.SurfaceHigh,
    cyan            = IslColors.Cyan,
    cyanDim         = IslColors.CyanDim,
    magenta         = IslColors.Magenta,
    magentaDim      = IslColors.MagentaDim,
    textPrimary     = IslColors.TextPrimary,
    textSecondary   = IslColors.TextSecondary,
    success         = IslColors.Success,
    warning         = IslColors.Warning,
    gridLine        = IslColors.GridLine
)

val LocalExtendedColors = staticCompositionLocalOf { islExtended }

// ─────────────────────────────────────────────────────────────────────────────
// Typography — monospace body, regular headings
// ─────────────────────────────────────────────────────────────────────────────

private val Typography = androidx.compose.material3.Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        color = IslColors.TextPrimary
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp,
        color = IslColors.TextPrimary
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        color = IslColors.TextPrimary
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        color = IslColors.TextPrimary
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        color = IslColors.TextSecondary
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 13.sp,
        letterSpacing = 1.sp
    )
)

// ─────────────────────────────────────────────────────────────────────────────
// Entry point
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun SoundForgeTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalExtendedColors provides islExtended) {
        MaterialTheme(
            colorScheme = ColorScheme,
            typography  = Typography,
            content     = content
        )
    }
}

/** Shortcut for accessing extended colors inside composables. */
object SoundForgeTheme {
    val colors: ExtendedColors
        @Composable get() = LocalExtendedColors.current
}
