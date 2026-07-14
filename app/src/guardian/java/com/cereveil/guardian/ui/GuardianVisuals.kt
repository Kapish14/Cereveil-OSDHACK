package com.cereveil.guardian.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.composeunstyled.UnstyledButton
import com.cereveil.theme.Typography

val GuardianBackground = Color(0xFFF2F4F8)
val GuardianSurface = Color(0xFFFAFBFE)
val GuardianPrimary = Color(0xFF2D5DB8)
val GuardianText = Color(0xFF1C2B38)
val GuardianTextSecondary = Color(0xFF6F7C89)
val GuardianOutline = Color(0xFFE0E5EB)
val GuardianGreen = Color(0xFF16855F)
val GuardianGreenContainer = Color(0xFFE8F8F0)
val GuardianOrange = Color(0xFFE56F00)
val GuardianOrangeContainer = Color(0xFFFEF3E8)
val GuardianYellow = Color(0xFFB77900)
val GuardianYellowContainer = Color(0xFFFFF7DB)
val GuardianPurple = Color(0xFF7651B5)

private val GuardianColorScheme =
  lightColorScheme(
    primary = GuardianPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE4F8),
    onPrimaryContainer = Color(0xFF0D2249),
    secondary = GuardianOrange,
    onSecondary = Color.White,
    secondaryContainer = GuardianOrangeContainer,
    onSecondaryContainer = Color(0xFF4A2500),
    tertiary = GuardianGreen,
    onTertiary = Color.White,
    tertiaryContainer = GuardianGreenContainer,
    onTertiaryContainer = Color(0xFF003824),
    background = GuardianBackground,
    onBackground = GuardianText,
    surface = GuardianSurface,
    onSurface = GuardianText,
    surfaceVariant = Color(0xFFE9EDF3),
    onSurfaceVariant = GuardianTextSecondary,
    outline = Color(0xFFC9D0D9),
    outlineVariant = GuardianOutline,
    error = Color(0xFFBA1A1A),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
  )

@Composable
fun GuardianTheme(content: @Composable () -> Unit) {
  MaterialTheme(
    colorScheme = GuardianColorScheme,
    typography = Typography,
    shapes =
      Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(32.dp),
      ),
    content = content,
  )
}

@Composable
fun GuardianScreen(
  modifier: Modifier = Modifier,
  scrollable: Boolean = true,
  bottomContentPadding: androidx.compose.ui.unit.Dp = 20.dp,
  content: @Composable ColumnScope.() -> Unit,
) {
  val scrolling = if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier
  Column(
    modifier =
      modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .safeDrawingPadding()
        .then(scrolling)
        .padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = bottomContentPadding),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    content = content,
  )
}

@Composable
fun GuardianHeader(modifier: Modifier = Modifier, role: String = "Guardian", compact: Boolean = false) {
  Row(
    modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Column {
      Text(
        "Cereveil",
        style = if (compact) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
      )
      Text(role, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    StatusPill("Guardian", GuardianPrimary, MaterialTheme.colorScheme.primaryContainer)
  }
}

@Composable
fun GuardianTitle(
  title: String,
  supportingText: String? = null,
  centered: Boolean = false,
  modifier: Modifier = Modifier,
) {
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(
      title,
      style = MaterialTheme.typography.headlineMedium,
      fontWeight = FontWeight.Bold,
      textAlign = if (centered) TextAlign.Center else TextAlign.Start,
      modifier = Modifier.fillMaxWidth(),
    )
    supportingText?.let {
      Text(
        it,
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

@Composable
fun GuardianCard(
  modifier: Modifier = Modifier,
  containerColor: Color = MaterialTheme.colorScheme.surface,
  onClick: (() -> Unit)? = null,
  content: @Composable ColumnScope.() -> Unit,
) {
  val cardModifier = modifier.fillMaxWidth().border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
  if (onClick == null) {
    ElevatedCard(
      modifier = cardModifier,
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
      elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
      Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
  } else {
    ElevatedCard(
      onClick = onClick,
      modifier = cardModifier,
      shape = RoundedCornerShape(16.dp),
      colors = CardDefaults.elevatedCardColors(containerColor = containerColor),
      elevation = CardDefaults.elevatedCardElevation(defaultElevation = 1.dp),
    ) {
      Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
  }
}

@Composable
fun GuardianFeatureCard(
  icon: ImageVector,
  iconTint: Color,
  title: String,
  subtitle: String,
  status: String? = null,
  onClick: () -> Unit,
) {
  GuardianCard(onClick = onClick) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Box(
        Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(iconTint.copy(alpha = 0.13f)),
        contentAlignment = Alignment.Center,
      ) {
        Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(23.dp))
      }
      Spacer(Modifier.width(14.dp))
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
      if (status != null) StatusPill(status, iconTint, iconTint.copy(alpha = 0.12f))
      else Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
  }
}

@Composable
fun GuardianSectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
  Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    action?.let {
      Text(
        it,
        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp).clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(horizontal = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
      )
    }
  }
}

@Composable
fun StatusPill(text: String, color: Color, background: Color, icon: ImageVector? = null) {
  Surface(color = background, contentColor = color, shape = CircleShape) {
    Row(
      Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
      horizontalArrangement = Arrangement.spacedBy(5.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      icon?.let { Icon(it, contentDescription = null, modifier = Modifier.size(14.dp)) }
      Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
  }
}

@Composable
fun GuardianPrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
  UnstyledButton(
    onClick = onClick,
    enabled = enabled,
    modifier =
      modifier.fillMaxWidth().height(52.dp)
        .alpha(if (enabled) 1f else 0.45f)
        .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp)),
  ) {
    Text(text, color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
  }
}

@Composable
fun GuardianSecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
  UnstyledButton(
    onClick = onClick,
    enabled = enabled,
    modifier = modifier.fillMaxWidth().height(50.dp).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp)),
  ) {
    Text(text, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
  }
}

@Composable
fun GuardianNotice(text: String, modifier: Modifier = Modifier) {
  Row(
    modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)).padding(16.dp),
    verticalAlignment = Alignment.Top,
  ) {
    Box(Modifier.padding(top = 6.dp).size(7.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
    Spacer(Modifier.width(10.dp))
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
  }
}

enum class GuardianTab(val label: String, val icon: ImageVector) {
  Home("Home", Icons.Default.Home),
  Location("Location", Icons.Default.LocationOn),
  Activity("Activity", Icons.AutoMirrored.Filled.ShowChart),
  Settings("Settings", Icons.Default.Settings),
}

@Composable
fun GuardianBottomBar(selected: GuardianTab, onSelect: (GuardianTab) -> Unit) {
  Box(
    Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp),
    contentAlignment = Alignment.BottomCenter,
  ) {
    Surface(
      modifier = Modifier.fillMaxWidth().height(68.dp).shadow(12.dp, RoundedCornerShape(34.dp)),
      shape = RoundedCornerShape(34.dp),
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 1.dp,
    ) {
      Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        GuardianTab.entries.forEach { tab -> GuardianNavItem(tab, selected == tab) { onSelect(tab) } }
      }
    }
  }
}

@Composable
private fun RowScope.GuardianNavItem(tab: GuardianTab, selected: Boolean, onClick: () -> Unit) {
  val color = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
  val background = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
  Row(
    Modifier.weight(if (selected) 1.65f else 1f)
      .clip(CircleShape)
      .background(background)
      .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 13.dp),
    horizontalArrangement = Arrangement.Center,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Icon(tab.icon, contentDescription = tab.label, tint = color, modifier = Modifier.size(22.dp))
    if (selected) {
      Spacer(Modifier.width(7.dp))
      Text(tab.label, color = color, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
    }
  }
}
