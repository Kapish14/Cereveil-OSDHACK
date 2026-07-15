package com.cereveil.guardian.childprofile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import com.cereveil.guardian.policy.GuardianPolicyContent
import com.cereveil.guardian.policy.GuardianPolicySection
import com.cereveil.guardian.enrollment.GuardianDeviceReplacementAction
import com.cereveil.guardian.enrollment.GuardianEndSupervisionAction
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cereveil.guardian.ui.GuardianBottomBar
import com.cereveil.guardian.ui.GuardianCard
import com.cereveil.guardian.ui.GuardianDarkModeToggle
import com.cereveil.guardian.ui.GuardianGreen
import com.cereveil.guardian.ui.GuardianGreenContainer
import com.cereveil.guardian.ui.GuardianHeader
import com.cereveil.guardian.ui.GuardianNotice
import com.cereveil.guardian.ui.GuardianOrange
import com.cereveil.guardian.ui.GuardianOrangeContainer
import com.cereveil.guardian.ui.GuardianPrimaryButton
import com.cereveil.guardian.ui.GuardianProtectionCard
import com.cereveil.guardian.ui.GuardianScreen
import com.cereveil.guardian.ui.GuardianSecondaryButton
import com.cereveil.guardian.ui.GuardianTab
import com.cereveil.guardian.ui.GuardianTitle
import com.cereveil.guardianLogoutActionLabel
import com.cereveil.guardianLogoutError

private enum class GuardianHomeDetail { ScamText, NsfwScreen, AppBlocking, RemoteAudio }

@Composable
fun GuardianChildProfileSetupContent(
  modifier: Modifier = Modifier,
  authSessionKey: String?,
  viewModel: GuardianChildProfileSetupViewModel = viewModel(),
  onSetUpChildDevice: (ChildProfileSummary) -> Unit = {},
  onSupervisionEnded: () -> Unit = {},
  onSignOut: () -> Unit = {},
  signOutInProgress: Boolean = false,
  signOutFailed: Boolean = false,
  refreshKey: Int = 0,
  initialDetailProfileId: String? = null,
  initialOpenSafetyFeed: Boolean = false,
) {
  val context = LocalContext.current
  val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
  LaunchedEffect(Unit) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
      context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
  }
  LaunchedEffect(authSessionKey, refreshKey) { viewModel.loadForSession(authSessionKey) }
  val state by viewModel.state.collectAsStateWithLifecycle()
  GuardianChildProfileSetupContent(
    state = state,
    onSubmit = viewModel::submit,
    onRetry = viewModel::load,
    onSetUpChildDevice = onSetUpChildDevice,
    onSupervisionEnded = onSupervisionEnded,
    onSignOut = onSignOut,
    signOutInProgress = signOutInProgress,
    signOutFailed = signOutFailed,
    modifier = modifier,
    initialDetailProfileId = initialDetailProfileId,
    initialOpenSafetyFeed = initialOpenSafetyFeed,
  )
}

@Composable
internal fun GuardianChildProfileSetupContent(
  state: GuardianChildProfileSetupState,
  onSubmit: (String, String, String) -> Unit,
  onRetry: () -> Unit,
  onSetUpChildDevice: (ChildProfileSummary) -> Unit,
  onSupervisionEnded: () -> Unit = {},
  onSignOut: () -> Unit = {},
  signOutInProgress: Boolean = false,
  signOutFailed: Boolean = false,
  modifier: Modifier = Modifier,
  initialDetailProfileId: String? = null,
  initialOpenSafetyFeed: Boolean = false,
) {
  var addingChild by rememberSaveable { mutableStateOf(false) }
  var detailProfileId by rememberSaveable(initialDetailProfileId) { mutableStateOf(initialDetailProfileId) }
  val profileSetup = state as? GuardianChildProfileSetupState.ProfileSetup
  val detailProfile = profileSetup?.profiles?.firstOrNull { it.childProfileId == detailProfileId }
  if (detailProfile != null && !addingChild) {
    GuardianDashboard(
      profile = detailProfile,
      modifier = modifier,
      openSafetyAlerts = initialOpenSafetyFeed && initialDetailProfileId == detailProfile.childProfileId,
      onBack = { detailProfileId = null },
      onSetUpChildDevice = onSetUpChildDevice,
      onSupervisionEnded = {
        detailProfileId = null
        onSupervisionEnded()
      },
      onSignOut = onSignOut,
      signOutInProgress = signOutInProgress,
      signOutFailed = signOutFailed,
    )
    return
  }
  GuardianScreen(modifier = modifier) {
    GuardianHeader(compact = true)
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
        GuardianTitle("We couldn’t load your children", childProfileErrorDisplayName(state.error))
        GuardianPrimaryButton("Try again", onRetry)
      }
      is GuardianChildProfileSetupState.ProfileSetup -> {
        if (addingChild) {
          ChildProfileForm(onSubmit, onCancel = { addingChild = false })
        } else {
          ProfileList(
            profiles = state.profiles,
            onSetUpChildDevice = onSetUpChildDevice,
            onAddChild = { addingChild = true },
            onViewProfile = { detailProfileId = it.childProfileId },
          )
        }
      }
    }
    if (guardianTopLevelLogoutVisible(state)) {
      GuardianLogoutAction(onSignOut, signOutInProgress, signOutFailed)
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
  GuardianTitle(
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
  GuardianNotice("Cereveil only needs month and year. Exact birth dates are never collected.")
  GuardianNotice(InitialPolicyDefaultsDisclosure)
  Text("For children ages 8–15.", style = MaterialTheme.typography.labelSmall)
  GuardianPrimaryButton(
    text = "Create child profile",
    onClick = { onSubmit(displayName, birthMonth, birthYear) },
  )
  if (onCancel != null) GuardianSecondaryButton(text = "Cancel", onClick = onCancel)
}

@Composable
private fun ProfileList(
  profiles: List<ChildProfileSummary>,
  onSetUpChildDevice: (ChildProfileSummary) -> Unit,
  onAddChild: () -> Unit,
  onViewProfile: (ChildProfileSummary) -> Unit,
) {
  GuardianTitle("Your children", "Finish device setup for each Child Profile.")
  profiles.forEach { profile ->
    GuardianCard {
      Text(profile.displayName.take(1).uppercase(), style = MaterialTheme.typography.headlineMedium)
      Text(profile.displayName, style = MaterialTheme.typography.titleLarge)
      Text("Born ${birthMonthName(profile.birthMonth)} ${profile.birthYear}")
      Text(
        childProfileEnrollmentDisplayName(profile.enrollmentStatus),
        color =
          if (profile.enrollmentStatus == ChildProfileEnrollmentStatus.Active) {
            GuardianGreen
          } else {
            MaterialTheme.colorScheme.tertiary
          },
      )
      if (profile.enrollmentStatus == ChildProfileEnrollmentStatus.Unenrolled) {
        Text("Protection has not started on a Child Device yet.")
        GuardianPrimaryButton(
          text = "Set up child device",
          onClick = { onSetUpChildDevice(profile) },
        )
      } else {
        Text(connectivityDisplayName(profile.connectivityStatus))
        Text(protectionDisplayName(profile.protectionStatus, profile.connectivityStatus))
        GuardianSecondaryButton(text = "Open guardian dashboard", onClick = { onViewProfile(profile) })
      }
    }
  }
  GuardianSecondaryButton(text = "Add another child", onClick = onAddChild)
  Text("You can leave and resume setup later.", style = MaterialTheme.typography.labelSmall)
}

@Composable
private fun GuardianDashboard(
  profile: ChildProfileSummary,
  modifier: Modifier,
  openSafetyAlerts: Boolean,
  onBack: () -> Unit,
  onSetUpChildDevice: (ChildProfileSummary) -> Unit,
  onSupervisionEnded: () -> Unit,
  onSignOut: () -> Unit,
  signOutInProgress: Boolean,
  signOutFailed: Boolean,
) {
  var selectedTab by rememberSaveable(profile.childProfileId) { mutableStateOf(GuardianTab.Home) }
  var homeDetail by rememberSaveable(profile.childProfileId) { mutableStateOf<GuardianHomeDetail?>(null) }
  var homeScrollPosition by rememberSaveable(profile.childProfileId) { mutableStateOf(0) }
  var restoreHomeScroll by remember { mutableStateOf(false) }
  val dashboardScrollState = rememberScrollState()
  val openDetail: (GuardianHomeDetail) -> Unit = {
    homeScrollPosition = dashboardScrollState.value
    homeDetail = it
  }
  val closeDetail: () -> Unit = {
    restoreHomeScroll = true
    homeDetail = null
  }
  LaunchedEffect(selectedTab, homeDetail) {
    if (selectedTab == GuardianTab.Home && homeDetail == null && restoreHomeScroll) {
      dashboardScrollState.scrollTo(homeScrollPosition)
      restoreHomeScroll = false
    } else {
      dashboardScrollState.scrollTo(0)
    }
  }
  BackHandler {
    when {
      homeDetail != null -> closeDetail()
      selectedTab == GuardianTab.Home -> onBack()
      else -> selectedTab = GuardianTab.Home
    }
  }
  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = MaterialTheme.colorScheme.background,
    bottomBar = { GuardianBottomBar(selectedTab, onSelect = { selectedTab = it; homeDetail = null }) },
  ) { innerPadding ->
    Column(
      Modifier.fillMaxSize()
        .guardianTabSwipe(selectedTab, enabled = homeDetail == null) {
          selectedTab = it
          homeDetail = null
          restoreHomeScroll = false
        }
        .verticalScroll(dashboardScrollState)
        .padding(innerPadding)
        .padding(horizontal = 20.dp, vertical = 12.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
          Modifier.size(48.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer),
          contentAlignment = Alignment.Center,
        ) {
          Text(profile.displayName.take(1).uppercase(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
        Box(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
          Text(profile.displayName, style = MaterialTheme.typography.titleLarge)
          Text("Guardian dashboard", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }

      when (selectedTab) {
        GuardianTab.Home -> {
          when (homeDetail) {
            null -> {
              if (profile.enrollmentStatus == ChildProfileEnrollmentStatus.Unenrolled) {
                GuardianCard {
                  Text("Connect your child's device", style = MaterialTheme.typography.titleLarge)
                  Text("Supervision is paused. Setup must be completed on your child's phone to start protection.")
                  GuardianPrimaryButton(
                    text = "Set up child device",
                    onClick = { onSetUpChildDevice(profile) }
                  )
                }
              } else {
                GuardianProtectionCard(
                  title = if (profile.protectionStatus == GuardianProtectionStatus.FullyProtected) "Protection active"
                    else protectionDisplayName(profile.protectionStatus, profile.connectivityStatus),
                  supportingText = relativeHeartbeatLabel(profile),
                  attentionText = profile.unavailableCapabilities.takeIf { it.isNotEmpty() }
                    ?.joinToString(prefix = "Needs attention: "),
                )
                GuardianLiveFeaturesContent(
                  profile.childProfileId,
                  section = GuardianFeatureSection.Home,
                  openSafetyAlerts = openSafetyAlerts,
                  onOpenScam = { openDetail(GuardianHomeDetail.ScamText) },
                  onOpenNsfw = { openDetail(GuardianHomeDetail.NsfwScreen) },
                  onOpenAppBlocking = { openDetail(GuardianHomeDetail.AppBlocking) },
                  onOpenRemoteAudio = { openDetail(GuardianHomeDetail.RemoteAudio) },
                )
              }
            }
            GuardianHomeDetail.ScamText -> {
              FeatureDetailHeader("Scam Text Detection", closeDetail)
              GuardianPolicyContent(profile.childProfileId, section = GuardianPolicySection.ScamText)
            }
            GuardianHomeDetail.NsfwScreen -> {
              FeatureDetailHeader("NSFW Screen Detection", closeDetail)
              GuardianPolicyContent(profile.childProfileId, section = GuardianPolicySection.NsfwScreen)
            }
            GuardianHomeDetail.AppBlocking -> {
              FeatureDetailHeader("App Blocking", closeDetail)
              GuardianPolicyContent(profile.childProfileId, section = GuardianPolicySection.AppBlocking)
              GuardianLiveFeaturesContent(profile.childProfileId, GuardianFeatureSection.AppBlocking)
            }
            GuardianHomeDetail.RemoteAudio -> {
              FeatureDetailHeader("One-Way Audio", closeDetail)
              GuardianLiveFeaturesContent(profile.childProfileId, GuardianFeatureSection.RemoteAudio)
            }
          }
        }
        GuardianTab.Location -> GuardianLiveFeaturesContent(profile.childProfileId, GuardianFeatureSection.Location)
        GuardianTab.Activity -> GuardianLiveFeaturesContent(profile.childProfileId, GuardianFeatureSection.Activity)
        GuardianTab.Settings -> {
          GuardianTitle("Supervision settings", "Changes are sent to ${profile.displayName}’s enrolled device.")
          GuardianDarkModeToggle()
          GuardianPolicyContent(profile.childProfileId, section = GuardianPolicySection.Settings)
          GuardianDeviceReplacementAction(
            childProfileId = profile.childProfileId,
            childDisplayName = profile.displayName,
            onReplacementReady = {
              onSetUpChildDevice(
                profile.copy(
                  enrollmentStatus = ChildProfileEnrollmentStatus.Unenrolled,
                  connectivityStatus = GuardianConnectivityStatus.NotApplicable,
                  protectionStatus = GuardianProtectionStatus.NotApplicable,
                  unavailableCapabilities = emptyList(),
                  lastHeartbeatAt = null,
                ),
              )
            },
          )
          GuardianEndSupervisionAction(
            childProfileId = profile.childProfileId,
            childDisplayName = profile.displayName,
            onSupervisionEnded = onSupervisionEnded,
          )
          GuardianLogoutAction(onSignOut, signOutInProgress, signOutFailed)
          GuardianSecondaryButton("Back to children", onBack)
        }
      }
    }
  }
}

private fun Modifier.guardianTabSwipe(
  selected: GuardianTab,
  enabled: Boolean,
  onSelect: (GuardianTab) -> Unit,
): Modifier = pointerInput(selected, enabled) {
  if (!enabled) return@pointerInput
  var distance = 0f
  detectHorizontalDragGestures(
    onDragStart = { distance = 0f },
    onHorizontalDrag = { change, amount ->
      distance += amount
      change.consume()
    },
    onDragEnd = {
      if (kotlin.math.abs(distance) < 90f) return@detectHorizontalDragGestures
      val tabs = GuardianTab.entries
      val current = tabs.indexOf(selected)
      val target = if (distance < 0) current + 1 else current - 1
      tabs.getOrNull(target)?.let(onSelect)
    },
  )
}

@Composable
internal fun GuardianLogoutAction(
  onSignOut: () -> Unit,
  signOutInProgress: Boolean,
  signOutFailed: Boolean,
) {
  guardianLogoutError(signOutFailed)?.let { GuardianNotice(it) }
  GuardianSecondaryButton(
    text = guardianLogoutActionLabel(signOutInProgress),
    onClick = onSignOut,
    enabled = !signOutInProgress,
  )
}

@Composable
private fun FeatureDetailHeader(title: String, onBack: () -> Unit) {
  Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    IconButton(onClick = onBack) {
      Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to dashboard")
    }
    Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
  }
}

@Composable
private fun relativeHeartbeatLabel(profile: ChildProfileSummary): String {
  val heartbeatAt = profile.lastHeartbeatAt ?: return "Waiting for the first device check"
  val serverOffset = (profile.serverNow ?: System.currentTimeMillis()) - System.currentTimeMillis()
  var currentTime by remember(profile.childProfileId, heartbeatAt) { mutableLongStateOf(System.currentTimeMillis()) }
  LaunchedEffect(profile.childProfileId, heartbeatAt) {
    while (true) {
      delay(60_000)
      currentTime = System.currentTimeMillis()
    }
  }
  val ageMinutes = (currentTime + serverOffset - heartbeatAt).coerceAtLeast(0) / 60_000
  return if (profile.connectivityStatus == GuardianConnectivityStatus.Offline) {
    "Last seen $ageMinutes min ago"
  } else {
    "Last checked $ageMinutes min ago"
  }
}

@Composable
private fun ErrorNotice(message: String) {
  GuardianCard(containerColor = MaterialTheme.colorScheme.errorContainer) {
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

internal fun guardianTopLevelLogoutVisible(state: GuardianChildProfileSetupState): Boolean =
  state != GuardianChildProfileSetupState.Loading

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
internal const val InitialPolicyDefaultsDisclosure =
  "After device setup, Location Sharing and Screen Time will be on by default. You can turn either off in Settings."
