package com.mts.tikmod.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** TikTok-inspired neon accents used by the hero header and highlights. */
object Neon {
    /** Set by [TikModTheme]: bright neon on dark backgrounds, deeper tones on light ones (readable either way). */
    @Volatile var isDark = true
    val Cyan: Color get() = if (isDark) Color(0xFF25F4EE) else Color(0xFF00727A)
    val Pink: Color get() = if (isDark) Color(0xFFFF4D6D) else Color(0xFFC8143F)
    val Violet: Color get() = if (isDark) Color(0xFFA78BFF) else Color(0xFF5B3FD6)
    val Lime: Color get() = if (isDark) Color(0xFF7CFFB2) else Color(0xFF1E7A3C)
    val Amber: Color get() = if (isDark) Color(0xFFFFC857) else Color(0xFF9A5B00)
    /** Text/icons drawn on top of an accent fill. */
    val OnAccent: Color get() = if (isDark) Color(0xFF0A0C10) else Color.White
}

/** Default "Neon" look: a deep, techy dark scheme with TikTok cyan/pink. */
private val NeonDark = darkColorScheme(
    primary = Color(0xFF25F4EE),
    onPrimary = Color(0xFF00201F),
    primaryContainer = Color(0xFF0E3B3A),
    onPrimaryContainer = Color(0xFFB6FFFC),
    secondary = Color(0xFFFE2C55),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF4A1020),
    onSecondaryContainer = Color(0xFFFFD9DF),
    tertiary = Color(0xFF7C4DFF),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFF2C1A5C),
    onTertiaryContainer = Color(0xFFE6DDFF),
    background = Color(0xFF07090D),
    onBackground = Color(0xFFE6EAF0),
    surface = Color(0xFF0B0E14),
    onSurface = Color(0xFFE6EAF0),
    surfaceVariant = Color(0xFF1A1F29),
    onSurfaceVariant = Color(0xFFB4BDCA),
    surfaceContainerLowest = Color(0xFF05070A),
    surfaceContainerLow = Color(0xFF0E121A),
    surfaceContainer = Color(0xFF121722),
    surfaceContainerHigh = Color(0xFF171D2A),
    surfaceContainerHighest = Color(0xFF1D2433),
    outline = Color(0xFF3A4352),
    outlineVariant = Color(0xFF252C38),
    error = Color(0xFFFF5470),
    errorContainer = Color(0xFF4D0F1C),
    onErrorContainer = Color(0xFFFFDADF)
)

private val NeonLight = lightColorScheme(
    primary = Color(0xFF00696A),
    secondary = Color(0xFFC0123E),
    tertiary = Color(0xFF5B3FD6),
    primaryContainer = Color(0xFFB6FFFC),
    secondaryContainer = Color(0xFFFFD9DF),
    background = Color(0xFFF4F6FA),
    surface = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F8FB),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFEFF1F5),
    onSurface = Color(0xFF12151B),
    onSurfaceVariant = Color(0xFF4A5260),
    outlineVariant = Color(0xFFD9DDE5)
)

enum class ThemeMode { NEON, DYNAMIC, SYSTEM }

@Composable
fun TikModTheme(mode: ThemeMode = ThemeMode.NEON, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when (mode) {
        ThemeMode.NEON -> NeonDark
        ThemeMode.DYNAMIC -> if (Build.VERSION.SDK_INT >= 31) {
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        } else if (dark) NeonDark else NeonLight
        ThemeMode.SYSTEM -> if (dark) NeonDark else NeonLight
    }
    Neon.isDark = (0.2126f * scheme.background.red + 0.7152f * scheme.background.green + 0.0722f * scheme.background.blue) < 0.5f
    // Re-key on the scheme so every accent (read from Neon) is re-evaluated when the theme changes.
    MaterialTheme(colorScheme = scheme, typography = Typography) { androidx.compose.runtime.key(scheme) { content() } }
}
