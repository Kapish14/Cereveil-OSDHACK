package com.cereveil.ui.main

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

class MainScreenInstrumentedTest {
  @get:Rule val composeTestRule = createAndroidComposeRule<ComponentActivity>()

  @Test
  fun showsGuardianMode() {
    composeTestRule.setContent { MainScreen(role = "guardian") }

    composeTestRule.onNodeWithText("Guardian Mode").assertExists()
  }
}
