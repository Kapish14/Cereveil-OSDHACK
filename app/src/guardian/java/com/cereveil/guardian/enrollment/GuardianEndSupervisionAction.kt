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
import com.cereveil.guardian.ui.GuardianCard
import com.cereveil.guardian.ui.GuardianNotice
import com.cereveil.guardian.ui.GuardianPrimaryButton
import com.cereveil.guardian.ui.GuardianSecondaryButton

@Composable
fun GuardianEndSupervisionAction(
  childProfileId: String,
  childDisplayName: String,
  onSupervisionEnded: () -> Unit,
) {
  val application = LocalContext.current.applicationContext as CereveilApplication
  val factory = remember(childProfileId) {
    viewModelFactory {
      initializer {
        GuardianEndSupervisionViewModel(
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
  val model: GuardianEndSupervisionViewModel =
    viewModel(key = "end-supervision:$childProfileId", factory = factory)
  val state by model.state.collectAsStateWithLifecycle()
  var expanded by rememberSaveable(childProfileId) { mutableStateOf(false) }

  GuardianEndSupervisionActionContent(
    childDisplayName = childDisplayName,
    expanded = expanded,
    state = state,
    onExpand = { expanded = true },
    onConfirm = model::endSupervision,
    onCancel = {
      model.retry()
      expanded = false
    },
    onSupervisionEnded = onSupervisionEnded,
  )
}

@Composable
internal fun GuardianEndSupervisionActionContent(
  childDisplayName: String,
  expanded: Boolean,
  state: GuardianEndSupervisionUiState,
  onExpand: () -> Unit,
  onConfirm: () -> Unit,
  onCancel: () -> Unit,
  onSupervisionEnded: () -> Unit,
) {
  if (!expanded) {
    GuardianSecondaryButton(text = "End supervision", onClick = onExpand)
    return
  }

  when (state) {
    GuardianEndSupervisionUiState.Confirming -> {
      GuardianEndSupervisionConfirmation(
        childDisplayName = childDisplayName,
        onConfirm = onConfirm,
        onCancel = onCancel,
      )
    }
    GuardianEndSupervisionUiState.Ending -> {
      GuardianNotice("Ending supervision and removing the Child Profile. Keep this screen open.")
      CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
    }
    GuardianEndSupervisionUiState.Ended -> {
      LaunchedEffect(state) { onSupervisionEnded() }
      GuardianNotice("Supervision ended. The Child Device no longer has access.")
    }
    is GuardianEndSupervisionUiState.Failure -> {
      GuardianNotice(endSupervisionErrorText(state.error))
      GuardianPrimaryButton(text = "Try ending supervision again", onClick = onConfirm)
      GuardianSecondaryButton(text = "Cancel", onClick = onCancel)
    }
  }
}

@Composable
internal fun GuardianEndSupervisionConfirmation(
  childDisplayName: String,
  onConfirm: () -> Unit,
  onCancel: () -> Unit,
) {
  GuardianCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
    Text("End supervision for $childDisplayName?", style = MaterialTheme.typography.titleLarge)
    Text(
      "This immediately revokes the Child Device and permanently deletes $childDisplayName’s Child Profile and supervision data. This cannot be undone.",
      color = MaterialTheme.colorScheme.onErrorContainer,
    )
  }
  GuardianPrimaryButton(text = "Permanently end supervision", onClick = onConfirm)
  GuardianSecondaryButton(text = "Keep supervision active", onClick = onCancel)
}

private fun endSupervisionErrorText(error: GuardianEnrollmentError): String = when (error) {
  GuardianEnrollmentError.BootstrapRequired -> "Guardian setup must finish before supervision can end."
  GuardianEnrollmentError.Unauthenticated -> "Sign in again before ending supervision."
  GuardianEnrollmentError.AccountUnavailable -> "This Guardian Account cannot end supervision right now."
  GuardianEnrollmentError.InvalidTarget -> "This Child Profile is no longer available."
  GuardianEnrollmentError.AlreadyEnrolled -> "Supervision could not end while the enrollment changed. Try again."
  GuardianEnrollmentError.Retryable -> "Supervision could not end. Check the connection and try again."
}
