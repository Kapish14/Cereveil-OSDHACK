package com.cereveil.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.composeunstyled.UnstyledButton
import com.cereveil.theme.CereveilBackground
import com.cereveil.theme.CereveilOutline
import com.cereveil.theme.CereveilPrimary
import com.cereveil.theme.CereveilSecondaryContainer
import com.cereveil.theme.CereveilSurface
import com.cereveil.theme.CereveilTextMuted

val CereveilPagePadding = 20.dp
val CereveilSectionSpacing = 24.dp
val CereveilItemSpacing = 12.dp

@Composable
fun CereveilScreen(
  modifier: Modifier = Modifier,
  scrollable: Boolean = true,
  content: @Composable ColumnScope.() -> Unit,
) {
  val scrollModifier = if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier
  Column(
    modifier =
      modifier
        .fillMaxSize()
        .background(CereveilBackground)
        .safeDrawingPadding()
        .then(scrollModifier)
        .padding(horizontal = CereveilPagePadding, vertical = 16.dp),
    verticalArrangement = Arrangement.spacedBy(CereveilItemSpacing),
    content = content,
  )
}

@Composable
fun CereveilHeader(
  role: String,
  modifier: Modifier = Modifier,
  compact: Boolean = false,
) {
  Row(
    modifier = modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = "Cereveil",
      style = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displaySmall,
      color = CereveilPrimary,
      fontWeight = FontWeight.Bold,
    )
    RolePill(role)
  }
}

@Composable
fun RolePill(role: String, modifier: Modifier = Modifier) {
  Surface(
    modifier = modifier,
    color = CereveilSecondaryContainer,
    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    shape = CircleShape,
  ) {
    Text(
      text = role,
      style = MaterialTheme.typography.labelSmall,
      modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
    )
  }
}

@Composable
fun CereveilTitle(
  title: String,
  supportingText: String? = null,
  modifier: Modifier = Modifier,
  centered: Boolean = false,
) {
  Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(
      text = title,
      style = MaterialTheme.typography.headlineLarge,
      color = MaterialTheme.colorScheme.onBackground,
      textAlign = if (centered) TextAlign.Center else TextAlign.Start,
      modifier = Modifier.fillMaxWidth(),
    )
    if (supportingText != null) {
      Text(
        text = supportingText,
        style = MaterialTheme.typography.bodyLarge,
        color = CereveilTextMuted,
        textAlign = if (centered) TextAlign.Center else TextAlign.Start,
        modifier = Modifier.fillMaxWidth(),
      )
    }
  }
}

@Composable
fun CereveilCard(
  modifier: Modifier = Modifier,
  containerColor: Color = CereveilSurface,
  content: @Composable ColumnScope.() -> Unit,
) {
  Column(
    modifier =
      modifier
        .fillMaxWidth()
        .background(containerColor, RoundedCornerShape(16.dp))
        .border(1.dp, CereveilOutline.copy(alpha = 0.7f), RoundedCornerShape(16.dp))
        .padding(16.dp),
    verticalArrangement = Arrangement.spacedBy(CereveilItemSpacing),
    content = content,
  )
}

@Composable
fun CereveilPrimaryButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  Button(
    onClick = onClick,
    enabled = enabled,
    modifier = modifier.fillMaxWidth().height(52.dp),
    shape = RoundedCornerShape(16.dp),
    colors =
      ButtonDefaults.buttonColors(
        containerColor = CereveilPrimary,
        contentColor = Color.White,
      ),
    contentPadding = PaddingValues(horizontal = 20.dp),
  ) {
    Text(text, style = MaterialTheme.typography.labelLarge)
  }
}

@Composable
fun CereveilSecondaryButton(
  text: String,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
) {
  UnstyledButton(
    onClick = onClick,
    enabled = enabled,
    modifier =
      modifier
        .fillMaxWidth()
        .height(52.dp)
        .border(1.dp, CereveilOutline, RoundedCornerShape(12.dp)),
  ) {
    Text(text, color = CereveilPrimary, style = MaterialTheme.typography.labelLarge)
  }
}

@Composable
fun CereveilNotice(
  text: String,
  modifier: Modifier = Modifier,
) {
  Box(
    modifier =
      modifier
        .fillMaxWidth()
        .background(CereveilSecondaryContainer, RoundedCornerShape(12.dp))
        .padding(16.dp),
  ) {
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
  }
}

@Composable
fun CereveilSpacer() {
  Spacer(Modifier.height(CereveilSectionSpacing - CereveilItemSpacing))
}
