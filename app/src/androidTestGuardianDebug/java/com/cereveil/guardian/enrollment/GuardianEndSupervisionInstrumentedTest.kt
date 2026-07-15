package com.cereveil.guardian.enrollment

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cereveil.guardian.ui.GuardianTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GuardianEndSupervisionInstrumentedTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun destructiveActionRequiresExplicitConfirmation() {
    var confirmed = false
    composeTestRule.setContent {
      GuardianTheme {
        Column {
          var expanded by remember { mutableStateOf(false) }
          GuardianEndSupervisionActionContent(
            childDisplayName = "TestChild",
            expanded = expanded,
            state = GuardianEndSupervisionUiState.Confirming,
            onExpand = { expanded = true },
            onConfirm = { confirmed = true },
            onCancel = {},
            onSupervisionEnded = {},
          )
        }
      }
    }

    composeTestRule.runOnIdle { assertFalse(confirmed) }
    composeTestRule.onNodeWithText("End supervision").performClick()
    composeTestRule.runOnIdle { assertFalse(confirmed) }
    composeTestRule
      .onNodeWithText(
        "This immediately revokes the Child Device and permanently deletes TestChild’s Child Profile and supervision data. This cannot be undone.",
      )
      .assertExists()
    composeTestRule.onNodeWithText("Permanently end supervision").performClick()
    composeTestRule.runOnIdle { assertTrue(confirmed) }
  }
}
