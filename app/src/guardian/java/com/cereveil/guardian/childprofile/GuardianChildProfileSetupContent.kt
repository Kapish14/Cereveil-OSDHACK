package com.cereveil.guardian.childprofile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cereveil.ui.CereveilCard
import com.cereveil.ui.CereveilHeader
import com.cereveil.ui.CereveilNotice
import com.cereveil.ui.CereveilPrimaryButton
import com.cereveil.ui.CereveilScreen
import com.cereveil.ui.CereveilSecondaryButton
import com.cereveil.ui.CereveilTitle

@Composable
fun GuardianChildProfileSetupContent(
  modifier: Modifier = Modifier,
  authSessionKey: String?,
  viewModel: GuardianChildProfileSetupViewModel = viewModel(),
  onSetUpChildDevice: (ChildProfileSummary) -> Unit = {},
  refreshKey: Int = 0,
) {
  LaunchedEffect(authSessionKey, refreshKey) { viewModel.loadForSession(authSessionKey) }
  val state by viewModel.state.collectAsStateWithLifecycle()
  GuardianChildProfileSetupContent(
    state = state,
    onSubmit = viewModel::submit,
    onRetry = viewModel::load,
    onSetUpChildDevice = onSetUpChildDevice,
    modifier = modifier,
  )
}

@Composable
internal fun GuardianChildProfileSetupContent(
  state: GuardianChildProfileSetupState,
  onSubmit: (String, String, String) -> Unit,
  onRetry: () -> Unit,
  onSetUpChildDevice: (ChildProfileSummary) -> Unit,
  modifier: Modifier = Modifier,
) {
  var addingChild by rememberSaveable { mutableStateOf(false) }
  CereveilScreen(modifier = modifier) {
    CereveilHeader(role = "Guardian", compact = true)
    when (state) {
      GuardianChildProfileSetupState.Loading -> {
        androidx.compose.foundation.layout.Column(
          modifier = Modifier.fillMaxWidth(),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(com.cereveil.ui.CereveilItemSpacing),
        ) {
          CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
          Text("Loading Child Profiles")
        }
      }
      GuardianChildProfileSetupState.FirstChildForm -> ChildProfileForm(onSubmit)
      is GuardianChildProfileSetupState.FormError -> {
        ChildProfileForm(onSubmit)
        ErrorNotice(childProfileErrorDisplayName(state.error))
      }
      is GuardianChildProfileSetupState.LoadError -> {
        CereveilTitle("We couldn’t load your children", childProfileErrorDisplayName(state.error))
        CereveilPrimaryButton("Try again", onRetry)
      }
      is GuardianChildProfileSetupState.ProfileSetup -> {
        if (addingChild) {
          ChildProfileForm(onSubmit, onCancel = { addingChild = false })
        } else {
          ProfileList(
            profiles = state.profiles,
            onSetUpChildDevice = onSetUpChildDevice,
            onAddChild = { addingChild = true },
          )
        }
      }
    }
  }
}

@Composable
private fun ChildProfileForm(
  onSubmit: (String, String, String) -> Unit,
  onCancel: (() -> Unit)? = null,
) {
  var displayName by rememberSaveable { mutableStateOf("") }
  var birthMonth by rememberSaveable { mutableStateOf("") }
  var birthYear by rememberSaveable { mutableStateOf("") }

  Text("Child setup • Step 1 of 2", style = MaterialTheme.typography.labelSmall)
  CereveilTitle(
    title = "Who are we setting up?",
    supportingText = "We only collect the details needed to check age eligibility and personalize setup.",
  )
  OutlinedTextField(
    value = displayName,
    onValueChange = { displayName = it },
    label = { Text("Display name") },
    supportingText = { Text("Use the name your child knows.") },
    singleLine = true,
    modifier = Modifier.fillMaxWidth(),
  )
  Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(
      value = birthMonth,
      onValueChange = { if (it.length <= 2) birthMonth = it.filter(Char::isDigit) },
      label = { Text("Birth month") },
      placeholder = { Text("1–12") },
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      modifier = Modifier.weight(1f),
    )
    OutlinedTextField(
      value = birthYear,
      onValueChange = { if (it.length <= 4) birthYear = it.filter(Char::isDigit) },
      label = { Text("Birth year") },
      placeholder = { Text("YYYY") },
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
      modifier = Modifier.weight(1f),
    )
  }
  CereveilNotice("Cereveil only needs month and year. Exact birth dates are never collected.")
  Text("For children ages 8–15.", style = MaterialTheme.typography.labelSmall)
  CereveilPrimaryButton(
    text = "Create child profile",
    onClick = { onSubmit(displayName, birthMonth, birthYear) },
  )
  if (onCancel != null) CereveilSecondaryButton(text = "Cancel", onClick = onCancel)
}

@Composable
private fun ProfileList(
  profiles: List<ChildProfileSummary>,
  onSetUpChildDevice: (ChildProfileSummary) -> Unit,
  onAddChild: () -> Unit,
) {
  var detailProfileId by rememberSaveable { mutableStateOf<String?>(null) }
  val detailProfile = profiles.firstOrNull { it.childProfileId == detailProfileId }
  if (detailProfile != null) {
    CereveilTitle("${detailProfile.displayName}’s device", connectivityDisplayName(detailProfile.connectivityStatus))
    Text(protectionDisplayName(detailProfile.protectionStatus, detailProfile.connectivityStatus))
    RelativeHeartbeatTime(detailProfile)
    if (detailProfile.unavailableCapabilities.isNotEmpty()) {
      Text("Unavailable: ${detailProfile.unavailableCapabilities.joinToString()}")
    }
    CereveilSecondaryButton(text = "Back to children", onClick = { detailProfileId = null })
    return
  }
  CereveilTitle("Your children", "Finish device setup for each Child Profile.")
  profiles.forEach { profile ->
    CereveilCard {
      Text(profile.displayName.take(1).uppercase(), style = MaterialTheme.typography.headlineMedium)
      Text(profile.displayName, style = MaterialTheme.typography.titleLarge)
      Text("Born ${birthMonthName(profile.birthMonth)} ${profile.birthYear}")
      Text(
        childProfileEnrollmentDisplayName(profile.enrollmentStatus),
        color =
          if (profile.enrollmentStatus == ChildProfileEnrollmentStatus.Active) {
            MaterialTheme.colorScheme.secondary
          } else {
            MaterialTheme.colorScheme.tertiary
          },
      )
      if (profile.enrollmentStatus == ChildProfileEnrollmentStatus.Unenrolled) {
        Text("Protection has not started on a Child Device yet.")
        CereveilPrimaryButton(
          text = "Set up child device",
          onClick = { onSetUpChildDevice(profile) },
        )
      } else {
        Text(connectivityDisplayName(profile.connectivityStatus))
        Text(protectionDisplayName(profile.protectionStatus, profile.connectivityStatus))
        CereveilSecondaryButton(text = "View device status", onClick = { detailProfileId = profile.childProfileId })
      }
    }
  }
  CereveilSecondaryButton(text = "Add another child", onClick = onAddChild)
  Text("You can leave and resume setup later.", style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun RelativeHeartbeatTime(profile: ChildProfileSummary) {
  val heartbeatAt = profile.lastHeartbeatAt ?: return
  val serverOffset = (profile.serverNow ?: System.currentTimeMillis()) - System.currentTimeMillis()
  var currentTime by remember(profile.childProfileId, heartbeatAt) { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(profile.childProfileId, heartbeatAt) {
    while (true) {
      delay(60_000)
      currentTime = System.currentTimeMillis()
    }
  }
  val ageMinutes = (currentTime + serverOffset - heartbeatAt).coerceAtLeast(0) / 60_000
  Text(if (profile.connectivityStatus == GuardianConnectivityStatus.Offline) {
    "Last seen $ageMinutes min ago"
  } else {
    "Last checked $ageMinutes min ago"
  })
}

@Composable
private fun ErrorNotice(message: String) {
  CereveilCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
    Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
  }
}

private fun birthMonthName(month: Int): String =
  listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
  ).getOrElse(month - 1) { "" }

internal fun childProfileEnrollmentDisplayName(enrollmentStatus: ChildProfileEnrollmentStatus): String =
  when (enrollmentStatus) {
    ChildProfileEnrollmentStatus.Unenrolled -> "Device setup needed"
    ChildProfileEnrollmentStatus.Active -> "Device enrolled"
  }

internal fun connectivityDisplayName(status: GuardianConnectivityStatus) = when (status) {
  GuardianConnectivityStatus.NotApplicable -> "Device not enrolled"
  GuardianConnectivityStatus.Pending -> "Waiting for first device status"
  GuardianConnectivityStatus.Online -> "Online"
  GuardianConnectivityStatus.Offline -> "Offline"
}

internal fun protectionDisplayName(status: GuardianProtectionStatus, connectivity: GuardianConnectivityStatus) =
  when (status) {
    GuardianProtectionStatus.NotApplicable -> "Protection not available"
    GuardianProtectionStatus.Pending -> "Protection status pending"
    GuardianProtectionStatus.FullyProtected -> if (connectivity == GuardianConnectivityStatus.Offline) "Fully protected when last checked" else "Fully protected"
    GuardianProtectionStatus.ProtectionDegraded -> if (connectivity == GuardianConnectivityStatus.Offline) "Protection was degraded when last checked" else "Protection degraded"
  }

internal fun childProfileErrorDisplayName(error: GuardianChildProfileError): String =
  when (error) {
    GuardianChildProfileError.BootstrapRequired -> "Guardian setup required"
    GuardianChildProfileError.Unauthenticated -> "Sign in required"
    GuardianChildProfileError.AccountUnavailable -> "Guardian Account unavailable"
    GuardianChildProfileError.ValidationFailed -> "Check the Child Profile details"
    GuardianChildProfileError.AgeOutOfRange -> "Child age must be 8 to 15"
    GuardianChildProfileError.Retryable -> "Connection problem"
  }
