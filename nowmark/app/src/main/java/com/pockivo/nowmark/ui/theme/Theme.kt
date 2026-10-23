package com.pockivo.nowmark.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Pockivo tokens taken from the approved Nowmark screens. */
@Immutable
data class NowmarkColors(
    val ground: Color,
    val panel: Color,
    val ink: Color,
    val inkSoft: Color,
    val line: Color,
    val accent: Color,
    val accentTint: Color,
    val ok: Color,
    val okTint: Color,
    val warn: Color,
    val warnTint: Color,
    val dark: Color,
    val onDark: Color,
)

val LightTokens = NowmarkColors(
    ground = Color(0xFFF7F4F2),
    panel = Color(0xFFFFFFFF),
    ink = Color(0xFF161314),
    inkSoft = Color(0xFF6E6668),
    line = Color(0xFFE9E3E1),
    accent = Color(0xFF8B2E48),
    accentTint = Color(0xFFF6E4E8),
    ok = Color(0xFF2E7D4F),
    okTint = Color(0xFFE3F1E7),
    warn = Color(0xFFB35C00),
    warnTint = Color(0xFFFCEFDC),
    dark = Color(0xFF1C1A1B),
    onDark = Color(0xFFFFFFFF),
)

val DarkTokens = NowmarkColors(
    ground = Color(0xFF131112),
    panel = Color(0xFF1E1B1C),
    ink = Color(0xFFF3EEEF),
    inkSoft = Color(0xFFB3A9AB),
    line = Color(0xFF332E30),
    accent = Color(0xFFE48AA3),
    accentTint = Color(0xFF3D2129),
    ok = Color(0xFF7FCB9C),
    okTint = Color(0xFF1D3326),
    warn = Color(0xFFF2B266),
    warnTint = Color(0xFF3A2A16),
    dark = Color(0xFF2A2627),
    onDark = Color(0xFFFFFFFF),
)

val LocalTokens = staticCompositionLocalOf { LightTokens }

object Nm {
    val c: NowmarkColors @Composable get() = LocalTokens.current
}

private val NowmarkType = Typography(
    displayLarge = TextStyle(fontSize = 64.sp, fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
    headlineLarge = TextStyle(fontSize = 32.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, lineHeight = 34.sp),
    titleLarge = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, fontWeight = FontWeight.Bold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    labelLarge = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
)

@Composable
fun NowmarkTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val t = if (dark) DarkTokens else LightTokens
    val scheme = if (dark) {
        darkColorScheme(
            primary = t.accent, onPrimary = Color(0xFF2B0B15), primaryContainer = t.accentTint,
            onPrimaryContainer = t.ink, background = t.ground, onBackground = t.ink, surface = t.panel,
            onSurface = t.ink, onSurfaceVariant = t.inkSoft, outline = t.line, surfaceVariant = t.panel,
        )
    } else {
        lightColorScheme(
            primary = t.accent, onPrimary = Color.White, primaryContainer = t.accentTint,
            onPrimaryContainer = t.accent, background = t.ground, onBackground = t.ink, surface = t.panel,
            onSurface = t.ink, onSurfaceVariant = t.inkSoft, outline = t.line, surfaceVariant = Color(0xFFF0EBE9),
        )
    }
    CompositionLocalProvider(LocalTokens provides t) {
        MaterialTheme(colorScheme = scheme, typography = NowmarkType, content = content)
    }
}
