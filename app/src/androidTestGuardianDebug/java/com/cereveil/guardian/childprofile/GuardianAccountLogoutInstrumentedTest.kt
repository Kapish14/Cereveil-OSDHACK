package com.cereveil.guardian.childprofile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cereveil.guardian.ui.GuardianTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class GuardianAccountLogoutInstrumentedTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun stableTopLevelScreenLetsTheGuardianReturnToLogin() {
    var signOutRequested = false
    composeTestRule.setContent {
      GuardianTheme {
        GuardianChildProfileSetupContent(
          state = GuardianChildProfileSetupState.FirstChildForm,
          onSubmit = { _, _, _ -> },
          onRetry = {},
          onSetUpChildDevice = {},
          onSignOut = { signOutRequested = true },
        )
      }
    }

    composeTestRule.onNodeWithText("Log out").assertIsEnabled().performClick()

    composeTestRule.runOnIdle { assertTrue(signOutRequested) }
  }

  @Test
  fun childCreationDisclosesEnabledLocationAndScreenTimeDefaultsBeforeSubmit() {
    var profileSubmitted = false
    composeTestRule.setContent {
      GuardianTheme {
        GuardianChildProfileSetupContent(
          state = GuardianChildProfileSetupState.FirstChildForm,
          onSubmit = { _, _, _ -> profileSubmitted = true },
          onRetry = {},
          onSetUpChildDevice = {},
        )
      }
    }

    composeTestRule.onNodeWithText(InitialPolicyDefaultsDisclosure).fetchSemanticsNode()
    composeTestRule.onNodeWithText("Create child profile").assertIsEnabled().performClick()

    composeTestRule.runOnIdle { assertTrue(profileSubmitted) }
  }
}
