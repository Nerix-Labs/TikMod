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
    enum class Style { NEON, STUDIO }

    /** Set by [TikModTheme]: luminous tones on dark backgrounds, deeper ones on light (readable either way). */
    @Volatile var isDark = true
    /** Neon: electric colors that glow. Studio: calm, softer tones. */
    @Volatile var style = Style.NEON
    private val neon get() = style == Style.NEON
    /** Glow effects (colored shadows, gradient headlines, the grid) — Neon on a dark background only. */
    val glow: Boolean get() = neon && isDark

    val Cyan: Color get() = when { !isDark -> Color(0xFF3F4FD1); neon -> Color(0xFF00E5FF); else -> Color(0xFF8B9CFF) }
    val Pink: Color get() = when { !isDark -> Color(0xFFC2185B); neon -> Color(0xFFFF2E8B); else -> Color(0xFFFF6F91) }
    val Violet: Color get() = when { !isDark -> Color(0xFF6A3FD6); neon -> Color(0xFFA45CFF); else -> Color(0xFFC3A6FF) }
    val Lime: Color get() = when { !isDark -> Color(0xFF0E7A62); neon -> Color(0xFF39FFB0); else -> Color(0xFF5EE6C4) }
    val Amber: Color get() = when { !isDark -> Color(0xFF9A5400); neon -> Color(0xFFFFC23D); else -> Color(0xFFFFBE76) }
    /** Text/icons drawn on top of an accent fill. */
    val OnAccent: Color get() = if (isDark) Color(0xFF07070C) else Color.White
    /** The signature gradient (headers, the status ring, the active tab). */
    val brand: List<Color> get() = listOf(Cyan, Violet, Pink)
}

/** Neon: true black, surfaces with a violet tint, made for glowing accents. */
private val NeonGlowDark = darkColorScheme(
    primary = Color(0xFF00E5FF),
    onPrimary = Color(0xFF00161A),
    primaryContainer = Color(0xFF00373F),
    onPrimaryContainer = Color(0xFFB8F7FF),
    secondary = Color(0xFFFF2E8B),
    onSecondary = Color(0xFF1A0010),
    secondaryContainer = Color(0xFF4F0F2E),
    onSecondaryContainer = Color(0xFFFFD8E8),
    tertiary = Color(0xFFA45CFF),
    onTertiary = Color(0xFF12002B),
    tertiaryContainer = Color(0xFF34156B),
    onTertiaryContainer = Color(0xFFEBDCFF),
    background = Color(0xFF030305),
    onBackground = Color(0xFFF1F0FA),
    surface = Color(0xFF09090F),
    onSurface = Color(0xFFF1F0FA),
    surfaceVariant = Color(0xFF1B1830),
    onSurfaceVariant = Color(0xFFBDB8D6),
    surfaceContainerLowest = Color(0xFF020203),
    surfaceContainerLow = Color(0xFF0B0A12),
    surfaceContainer = Color(0xFF0F0E18),
    surfaceContainerHigh = Color(0xFF14121F),
    surfaceContainerHighest = Color(0xFF1C1A2B),
    outline = Color(0xFF3E3960),
    outlineVariant = Color(0xFF27233F),
    error = Color(0xFFFF4D6D),
    errorContainer = Color(0xFF4D0F1C),
    onErrorContainer = Color(0xFFFFDADF)
)

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

/** NEON: always dark and glowing. STUDIO: always dark and calm. DYNAMIC: Material You. SYSTEM: Studio, light or dark like the phone. */
enum class ThemeMode { NEON, STUDIO, DYNAMIC, SYSTEM }

@Composable
fun TikModTheme(mode: ThemeMode = ThemeMode.NEON, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    Neon.style = if (mode == ThemeMode.NEON) Neon.Style.NEON else Neon.Style.STUDIO
    val scheme = when (mode) {
        ThemeMode.NEON -> NeonGlowDark
        ThemeMode.STUDIO -> NeonDark
        ThemeMode.DYNAMIC -> if (Build.VERSION.SDK_INT >= 31) {
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        } else if (dark) NeonDark else NeonLight
        ThemeMode.SYSTEM -> if (dark) NeonDark else NeonLight
    }
    Neon.isDark = (0.2126f * scheme.background.red + 0.7152f * scheme.background.green + 0.0722f * scheme.background.blue) < 0.5f
    // Re-key on the scheme so every accent (read from Neon) is re-evaluated when the theme changes.
    MaterialTheme(colorScheme = scheme, typography = Typography) { androidx.compose.runtime.key(scheme) { content() } }
}
