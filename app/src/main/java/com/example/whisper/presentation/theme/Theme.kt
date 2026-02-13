package com.example.whisper.presentation.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// ============================================================================
// Talk to Whisper Dark Color Scheme — default, modern, bold
// ============================================================================
private val TtwDarkColorScheme = darkColorScheme(
    // Primary — Deep vibrant blue
    primary = Blue50,
    onPrimary = Color.White,
    primaryContainer = Blue30,
    onPrimaryContainer = Blue90,

    // Secondary — Electric teal
    secondary = Teal50,
    onSecondary = Color.White,
    secondaryContainer = Teal30,
    onSecondaryContainer = Teal90,

    // Tertiary — Warm amber
    tertiary = Amber50,
    onTertiary = Neutral10,
    tertiaryContainer = Amber30,
    onTertiaryContainer = Amber90,

    // Error — Soft red
    error = Red50,
    onError = Red10,
    errorContainer = Red30,
    onErrorContainer = Red90,

    // Background & Surface — Near-black
    background = Neutral10,
    onBackground = Neutral90,
    surface = Neutral10,
    onSurface = Neutral90,

    // Surface variants — Elevated dark cards
    surfaceVariant = NeutralVariant20,
    onSurfaceVariant = NeutralVariant80,

    // Surface containers — layered elevation
    surfaceContainerLowest = Neutral4,
    surfaceContainerLow = Neutral10,
    surfaceContainer = Neutral12,
    surfaceContainerHigh = Neutral17,
    surfaceContainerHighest = Neutral22,

    // Inverse
    inverseSurface = Neutral90,
    inverseOnSurface = Neutral20,
    inversePrimary = Blue40,

    // Outline
    outline = NeutralVariant40,
    outlineVariant = NeutralVariant30,

    // Scrim
    scrim = Neutral0
)

// ============================================================================
// Talk to Whisper Light Color Scheme — clean, bright alternative
// ============================================================================
private val TtwLightColorScheme = lightColorScheme(
    // Primary — Deep vibrant blue
    primary = Blue50,
    onPrimary = Color.White,
    primaryContainer = Blue90,
    onPrimaryContainer = Blue10,

    // Secondary — Electric teal
    secondary = Teal40,
    onSecondary = Color.White,
    secondaryContainer = Teal90,
    onSecondaryContainer = Teal10,

    // Tertiary — Warm amber
    tertiary = Amber40,
    onTertiary = Color.White,
    tertiaryContainer = Amber90,
    onTertiaryContainer = Amber10,

    // Error — Soft red
    error = Red40,
    onError = Color.White,
    errorContainer = Red90,
    onErrorContainer = Red10,

    // Background & Surface — Clean white
    background = Neutral98,
    onBackground = Neutral10,
    surface = Neutral98,
    onSurface = Neutral10,

    // Surface variants
    surfaceVariant = NeutralVariant90,
    onSurfaceVariant = NeutralVariant30,

    // Surface containers — layered elevation
    surfaceContainerLowest = Neutral100,
    surfaceContainerLow = Neutral96,
    surfaceContainer = Neutral94,
    surfaceContainerHigh = Neutral92,
    surfaceContainerHighest = Neutral90,

    // Inverse
    inverseSurface = Neutral20,
    inverseOnSurface = Neutral95,
    inversePrimary = Blue80,

    // Outline
    outline = NeutralVariant50,
    outlineVariant = NeutralVariant80,

    // Scrim
    scrim = Neutral0
)

// ============================================================================
// Talk to Whisper Theme Composable
// ============================================================================

/**
 * Talk to Whisper application theme.
 *
 * @param darkTheme Whether to use dark mode. Defaults to **true** (dark mode as default).
 * @param dynamicColor Whether to use Android 12+ dynamic color. Defaults to false so the
 *   brand palette is used by default.
 * @param content The composable content to theme.
 */
@Composable
fun TalkToWhisperTheme(
    darkTheme: Boolean = true,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> TtwDarkColorScheme
        else -> TtwLightColorScheme
    }

    // Update the system bar colors to match the theme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = TalkToWhisperTypography,
        shapes = TalkToWhisperShapes,
        content = content
    )
}
