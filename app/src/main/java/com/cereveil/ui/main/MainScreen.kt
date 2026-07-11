package com.cereveil.ui.main

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.cereveil.BuildConfig
import com.cereveil.theme.CereveilTheme

@Composable
fun MainScreen(
  modifier: Modifier = Modifier,
  role: String = BuildConfig.CEREVEIL_ROLE,
  statusText: String = "Setup pending",
  showRetry: Boolean = false,
  onRetry: () -> Unit = {},
) {
  Column(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(text = "Cereveil")
    Text(text = roleDisplayName(role))
    Text(text = statusText)
    if (showRetry) {
      Button(onClick = onRetry) { Text("Retry") }
    }
  }
}

internal fun roleDisplayName(role: String): String =
  when (role) {
    "guardian" -> "Guardian Mode"
    "child" -> "Child Mode"
    else -> "Unknown Mode"
  }

@Preview(showBackground = true)
@Composable
fun MainScreenPreview() {
  CereveilTheme { MainScreen(role = "guardian") }
}

@Preview(showBackground = true, widthDp = 340)
@Composable
fun MainScreenPortraitPreview() {
  CereveilTheme { MainScreen(role = "child") }
}
