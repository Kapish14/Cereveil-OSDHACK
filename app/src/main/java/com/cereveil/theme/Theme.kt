package com.cereveil.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

private val LightColorScheme =
  lightColorScheme(
    primary = CereveilPrimary,
    onPrimary = CereveilSurface,
    primaryContainer = CereveilPrimaryDark,
    onPrimaryContainer = CereveilSurface,
    secondary = CereveilSecondary,
    onSecondary = CereveilSurface,
    secondaryContainer = CereveilSecondaryContainer,
    onSecondaryContainer = CereveilOnSecondaryContainer,
    tertiary = CereveilAttention,
    background = CereveilBackground,
    onBackground = CereveilText,
    surface = CereveilSurface,
    onSurface = CereveilText,
    surfaceVariant = CereveilSurfaceMuted,
    onSurfaceVariant = CereveilTextMuted,
    outline = CereveilOutline,
    error = CereveilError,
    errorContainer = CereveilErrorContainer,
  )

private val CereveilShapes =
  Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
  )

@Composable
fun CereveilTheme(
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = LightColorScheme,
    typography = Typography,
    shapes = CereveilShapes,
    content = content,
  )
}
