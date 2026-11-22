package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val EdgeOpsLightColorScheme = lightColorScheme(
    primary = BluePrimary,
    onPrimary = SurfaceWhite,
    primaryContainer = BlueContainer,
    onPrimaryContainer = BlueOnContainer,
    secondary = EmeraldPrimary,
    onSecondary = SurfaceWhite,
    secondaryContainer = EmeraldContainer,
    onSecondaryContainer = EmeraldOnContainer,
    tertiary = AmberPrimary,
    onTertiary = SurfaceWhite,
    tertiaryContainer = AmberContainer,
    onTertiaryContainer = AmberOnContainer,
    background = CanvasLight,
    onBackground = InkPrimary,
    surface = SurfaceWhite,
    onSurface = InkPrimary,
    surfaceVariant = SurfaceSubtle,
    onSurfaceVariant = InkSecondary,
    outline = BorderHairline,
    outlineVariant = BorderMuted,
    error = RosePrimary,
    onError = SurfaceWhite,
    errorContainer = RoseContainer,
    onErrorContainer = RoseOnContainer,
    surfaceContainerLowest = SurfaceWhite,
    surfaceContainerLow = CanvasLight,
    surfaceContainer = SurfaceWhite,
    surfaceContainerHigh = SurfaceSubtle,
    surfaceContainerHighest = BorderHairline
)

val EdgeOpsShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

/**
 * EdgeOps is strictly a crisp, approachable light-mode Material 3 theme (NO dark mode).
 */
@Composable
fun EdgeOpsTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = EdgeOpsLightColorScheme,
        typography = EdgeOpsTypography,
        shapes = EdgeOpsShapes,
        content = content
    )
}
