package com.cereveil

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cereveil.guardian.auth.GuardianStartupRoute
import com.cereveil.guardian.auth.GuardianStartupViewModel
import com.cereveil.guardian.childprofile.ChildProfileSummary
import com.cereveil.guardian.childprofile.GuardianChildProfileSetupContent
import com.cereveil.guardian.enrollment.GuardianEnrollmentContent
import com.cereveil.ui.CereveilCard
import com.cereveil.ui.CereveilHeader
import com.cereveil.ui.CereveilNotice
import com.cereveil.ui.CereveilPrimaryButton
import com.cereveil.ui.CereveilScreen
import com.cereveil.ui.CereveilTitle
import com.clerk.ui.auth.AuthView

@Composable
fun RoleStartupContent(modifier: Modifier = Modifier) {
  val startupViewModel: GuardianStartupViewModel = viewModel()
  val route by startupViewModel.route.collectAsStateWithLifecycle()
  val authSessionKey by startupViewModel.setupAuthSessionKey.collectAsStateWithLifecycle()
  var enrollmentProfile by remember { mutableStateOf<ChildProfileSummary?>(null) }
  var refreshKey by remember { mutableIntStateOf(0) }
  var trustAccepted by rememberSaveable { mutableStateOf(false) }

  enrollmentProfile?.let { profile ->
    GuardianEnrollmentContent(
      childProfileId = profile.childProfileId,
      childDisplayName = profile.displayName,
      onBack = {
        enrollmentProfile = null
        refreshKey += 1
      },
    )
    return
  }

  when (route) {
    GuardianStartupRoute.Auth -> GuardianAuthContent(onAuthComplete = startupViewModel::start)
    GuardianStartupRoute.Loading -> GuardianLoadingContent()
    GuardianStartupRoute.Setup -> {
      if (!trustAccepted) {
        GuardianTrustContent(onContinue = { trustAccepted = true })
      } else {
        GuardianChildProfileSetupContent(
          modifier = modifier,
          authSessionKey = authSessionKey,
          onSetUpChildDevice = { enrollmentProfile = it },
          refreshKey = refreshKey,
        )
      }
    }
    GuardianStartupRoute.Dashboard ->
      GuardianChildProfileSetupContent(
        modifier = modifier,
        authSessionKey = authSessionKey,
        onSetUpChildDevice = { enrollmentProfile = it },
        refreshKey = refreshKey,
      )
    GuardianStartupRoute.DeviceRevoked ->
      GuardianBlockingState(
        title = "This Guardian Device was revoked",
        message = "This installation no longer has access to the Guardian Account.",
      )
    GuardianStartupRoute.DeviceLimitReached ->
      GuardianBlockingState(
        title = "Guardian Device limit reached",
        message = "This Guardian Account already has two active Guardian Devices. Use an existing device to continue.",
      )
    GuardianStartupRoute.RetryableError ->
      GuardianBlockingState(
        title = "Connection problem",
        message = "Cereveil could not finish starting Guardian Mode. Check your connection and try again.",
        action = "Try again",
        onAction = startupViewModel::retry,
      )
  }
}

@Composable
private fun GuardianAuthContent(onAuthComplete: () -> Unit) {
  CereveilScreen(scrollable = false) {
    CereveilHeader(role = "Guardian")
    CereveilTitle(
      title = "Your extra set of eyes in the digital world.",
      supportingText =
        "Set up app controls, safety alerts, and location features that stay visible on your child’s phone.",
      centered = true,
    )
    AuthView(
      modifier = Modifier.fillMaxWidth().weight(1f),
      onAuthComplete = onAuthComplete,
    )
  }
}

@Composable
private fun GuardianLoadingContent() {
  CereveilScreen(scrollable = false) {
    CereveilHeader(role = "Guardian")
    androidx.compose.foundation.layout.Column(
      modifier = Modifier.fillMaxWidth().weight(1f),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
    ) {
      CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
      Text("Starting Guardian Mode", modifier = Modifier.fillMaxWidth())
    }
  }
}

@Composable
private fun GuardianTrustContent(onContinue: () -> Unit) {
  CereveilScreen {
    CereveilHeader(role = "Guardian", compact = true)
    Text("Getting started • 1 of 2", style = MaterialTheme.typography.labelSmall)
    CereveilTitle(
      title = "Protection works best when everyone understands it",
      supportingText =
        "Cereveil stays visible on your child’s phone and only uses the information needed for the features you choose.",
    )
    CereveilCard {
      Text("Visible by design", style = MaterialTheme.typography.titleLarge)
      Text("Your child can see when Cereveil protection is active.")
    }
    CereveilCard {
      Text("Privacy-first defaults", style = MaterialTheme.typography.titleLarge)
      Text("Optional monitoring starts off until you choose it.")
    }
    CereveilCard {
      Text("Have both phones ready", style = MaterialTheme.typography.titleLarge)
      Text("You’ll finish setup together on the Child Device.")
    }
    CereveilPrimaryButton(text = "Continue to child setup", onClick = onContinue)
  }
}

@Composable
private fun GuardianBlockingState(
  title: String,
  message: String,
  action: String? = null,
  onAction: () -> Unit = {},
) {
  CereveilScreen {
    CereveilHeader(role = "Guardian", compact = true)
    CereveilTitle(title, message)
    CereveilNotice("Your account and Child data remain protected.")
    if (action != null) CereveilPrimaryButton(action, onAction)
  }
}

internal fun guardianStartupDisplayName(route: GuardianStartupRoute): String =
  when (route) {
    GuardianStartupRoute.Auth -> "Sign in required"
    GuardianStartupRoute.Loading -> "Starting Guardian Mode"
    GuardianStartupRoute.Setup -> "Guardian setup"
    GuardianStartupRoute.Dashboard -> "Guardian dashboard"
    GuardianStartupRoute.DeviceRevoked -> "Guardian Device revoked"
    GuardianStartupRoute.DeviceLimitReached -> "Guardian Device limit reached"
    GuardianStartupRoute.RetryableError -> "Connection problem"
  }
