package com.cereveil.guardian.enrollment

import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cereveil.CereveilApplication
import com.cereveil.guardian.auth.AndroidGuardianOperationBootstrapper
import com.cereveil.guardian.auth.SharedPreferencesGuardianInstallationIdProvider
import com.cereveil.ui.CereveilCard
import com.cereveil.ui.CereveilNotice
import com.cereveil.ui.CereveilPrimaryButton
import com.cereveil.ui.CereveilSecondaryButton

@Composable
fun GuardianDeviceReplacementAction(
  childProfileId: String,
  childDisplayName: String,
  onReplacementReady: () -> Unit,
) {
  val application = LocalContext.current.applicationContext as CereveilApplication
  val factory = remember(childProfileId) {
    viewModelFactory {
      initializer {
        GuardianDeviceReplacementViewModel(
          childProfileId,
          ConvexGuardianEnrollmentClient(
            application.convex,
            SharedPreferencesGuardianInstallationIdProvider(application),
            AndroidGuardianOperationBootstrapper(application),
          ),
        )
      }
    }
  }
  val model: GuardianDeviceReplacementViewModel =
    viewModel(key = "replace:$childProfileId", factory = factory)
  val state by model.state.collectAsStateWithLifecycle()
  var expanded by rememberSaveable(childProfileId) { mutableStateOf(false) }

  if (!expanded) {
    CereveilSecondaryButton(text = "Replace Child Device", onClick = { expanded = true })
    return
  }

  when (val current = state) {
    GuardianDeviceReplacementUiState.Confirming -> {
      CereveilCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
        Text("Replace ${childDisplayName}’s device?", style = MaterialTheme.typography.titleLarge)
        Text(
          "The existing Child Device will immediately lose access. The Child Profile and Guardian settings will be kept for the new enrollment.",
          color = MaterialTheme.colorScheme.onErrorContainer,
        )
      }
      CereveilPrimaryButton(text = "Replace device and create code", onClick = model::replace)
      CereveilSecondaryButton(text = "Keep existing device", onClick = { expanded = false })
    }
    GuardianDeviceReplacementUiState.Replacing -> {
      CereveilNotice("Revoking the existing Child Device. Keep this screen open.")
      CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
    GuardianDeviceReplacementUiState.Replaced -> {
      LaunchedEffect(childProfileId) { onReplacementReady() }
      CereveilNotice("Existing device access was revoked. Preparing a new enrollment code.")
    }
    is GuardianDeviceReplacementUiState.Failure -> {
      CereveilNotice(replacementErrorText(current.error))
      CereveilPrimaryButton(text = "Try replacement again", onClick = model::replace)
      CereveilSecondaryButton(
        text = "Cancel",
        onClick = {
          model.retry()
          expanded = false
        },
      )
    }
  }
}

private fun replacementErrorText(error: GuardianEnrollmentError): String = when (error) {
  GuardianEnrollmentError.BootstrapRequired -> "Guardian setup must finish before replacing this device."
  GuardianEnrollmentError.Unauthenticated -> "Sign in again before replacing this device."
  GuardianEnrollmentError.AccountUnavailable -> "This Guardian Account cannot replace devices right now."
  GuardianEnrollmentError.InvalidTarget -> "This Child Profile is no longer available."
  GuardianEnrollmentError.AlreadyEnrolled -> "The Child Profile still has an active device. Try again."
  GuardianEnrollmentError.Retryable -> "The device could not be replaced. Check the connection and try again."
}
