package org.opencrow.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    background = DarkSurface,
    surface = DarkSurface,
    surfaceContainerLowest = DarkSurfaceContainerLowest,
    surfaceContainerLow = DarkSurfaceContainerLow,
    surfaceContainer = DarkSurfaceContainer,
    surfaceContainerHigh = DarkSurfaceContainerHigh,
    surfaceContainerHighest = DarkSurfaceContainerHighest,
    onBackground = DarkOnSurface,
    onSurface = DarkOnSurface,
    onSurfaceVariant = DarkOnSurfaceVariant,
    outlineVariant = DarkOutlineVariant,
    primary = Violet,
    onPrimary = Color.White,
    primaryContainer = VioletDim,
    onPrimaryContainer = VioletLight,
    secondary = Cyan,
    onSecondary = Color(0xFF003F47),
    secondaryContainer = CyanDim,
    onSecondaryContainer = CyanLight,
    tertiary = ActionBlue,
    onTertiary = Color.White,
    tertiaryContainer = ActionBlueDeep,
    onTertiaryContainer = Color(0xFFD6E2FF),
    error = ErrorRose,
    onError = Color.White,
    surfaceTint = Violet
)

private val LightColorScheme = lightColorScheme(
    background = LightSurface,
    surface = LightSurface,
    surfaceContainerLowest = LightSurfaceContainerLowest,
    surfaceContainerLow = LightSurfaceContainerLow,
    surfaceContainer = LightSurfaceContainer,
    surfaceContainerHigh = LightSurfaceContainerHigh,
    surfaceContainerHighest = LightSurfaceContainerHighest,
    onBackground = LightOnSurface,
    onSurface = LightOnSurface,
    onSurfaceVariant = LightOnSurfaceVariant,
    outlineVariant = LightOutlineVariant,
    primary = LightViolet,
    onPrimary = Color.White,
    primaryContainer = LightVioletTint,
    onPrimaryContainer = LightViolet,
    secondary = LightCyan,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC4F8FF),
    onSecondaryContainer = Color(0xFF065A6E),
    tertiary = ActionBlue,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFD6E2FF),
    onTertiaryContainer = ActionBlueDeep,
    error = LightErrorRose,
    onError = Color.White,
    surfaceTint = LightViolet
)

@Composable
fun OpenCrowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(LocalSpacing provides Spacing()) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = OpenCrowTypography,
            shapes = OpenCrowShapes,
            content = content
        )
    }
}
