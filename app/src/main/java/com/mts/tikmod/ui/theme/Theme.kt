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

/**
 * Accent palette ("Studio", 4.10). The names are the roles the screens already use: Cyan is the primary accent,
 * Pink LIVE / stories, Violet privacy, Lime health / on, Amber warnings.
 */
object Neon {
    /** Set by [TikModTheme]: luminous tones on dark backgrounds, deeper ones on light (readable either way). */
    @Volatile var isDark = true
    val Cyan: Color get() = if (isDark) Color(0xFF8B9CFF) else Color(0xFF3F4FD1)   // iris
    val Pink: Color get() = if (isDark) Color(0xFFFF6F91) else Color(0xFFC2185B)   // rose
    val Violet: Color get() = if (isDark) Color(0xFFC3A6FF) else Color(0xFF6A3FD6) // lavender
    val Lime: Color get() = if (isDark) Color(0xFF5EE6C4) else Color(0xFF0E7A62)   // mint
    val Amber: Color get() = if (isDark) Color(0xFFFFBE76) else Color(0xFF9A5400)  // apricot
    /** Text/icons drawn on top of an accent fill. */
    val OnAccent: Color get() = if (isDark) Color(0xFF0D0E16) else Color.White
    /** The signature gradient (headers, the status ring, the active tab). */
    val brand: List<Color> get() = listOf(Cyan, Violet, Pink)
}

/** Default look: deep ink with a hint of blue, quiet surfaces, luminous accents. */
private val NeonDark = darkColorScheme(
    primary = Color(0xFF8B9CFF),
    onPrimary = Color(0xFF0D0E16),
    primaryContainer = Color(0xFF262B57),
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFFFF6F91),
    onSecondary = Color(0xFF1A0710),
    secondaryContainer = Color(0xFF4A1426),
    onSecondaryContainer = Color(0xFFFFD9E1),
    tertiary = Color(0xFFC3A6FF),
    onTertiary = Color(0xFF160D2E),
    tertiaryContainer = Color(0xFF33255A),
    onTertiaryContainer = Color(0xFFEBDDFF),
    background = Color(0xFF09090F),
    onBackground = Color(0xFFECECF4),
    surface = Color(0xFF0E0E16),
    onSurface = Color(0xFFECECF4),
    surfaceVariant = Color(0xFF1D1D29),
    onSurfaceVariant = Color(0xFFB5B5C8),
    surfaceContainerLowest = Color(0xFF07070C),
    surfaceContainerLow = Color(0xFF111119),
    surfaceContainer = Color(0xFF14141E),
    surfaceContainerHigh = Color(0xFF191924),
    surfaceContainerHighest = Color(0xFF20202D),
    outline = Color(0xFF3B3B4E),
    outlineVariant = Color(0xFF26263A),
    error = Color(0xFFFF6B7D),
    errorContainer = Color(0xFF4D1220),
    onErrorContainer = Color(0xFFFFDADF)
)

private val NeonLight = lightColorScheme(
    primary = Color(0xFF3F4FD1),
    secondary = Color(0xFFC2185B),
    tertiary = Color(0xFF6A3FD6),
    primaryContainer = Color(0xFFDDE1FF),
    secondaryContainer = Color(0xFFFFD9E1),
    background = Color(0xFFF5F5FA),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFECECF3),
    surfaceContainerLow = Color(0xFFF8F8FC),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFEFEFF6),
    onSurface = Color(0xFF14141C),
    onSurfaceVariant = Color(0xFF4B4B5E),
    outlineVariant = Color(0xFFDCDCE6)
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
