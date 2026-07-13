package com.cereveil.child.enrollment

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cereveil.ui.CereveilCard
import com.cereveil.ui.CereveilHeader
import com.cereveil.ui.CereveilNotice
import com.cereveil.ui.CereveilPrimaryButton
import com.cereveil.ui.CereveilScreen
import com.cereveil.ui.CereveilSecondaryButton
import com.cereveil.ui.CereveilTitle
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning

@Composable
fun ChildEnrollmentContent(modifier: Modifier = Modifier, model: ChildEnrollmentViewModel = viewModel()) {
  val context = LocalContext.current
  val scanner = remember {
    GmsBarcodeScanning.getClient(
      context,
      GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build(),
    )
  }
  val state by model.state.collectAsStateWithLifecycle()
  var guardianJoined by rememberSaveable { mutableStateOf(false) }
  val corePermissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
    model.refreshProtectionSetup()
  }
  val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
    model.refreshProtectionSetup()
  }

  if (state == ChildEnrollmentUiState.ProtectionSetup && !guardianJoined) {
    GuardianHandoff(modifier = modifier, onContinue = { guardianJoined = true })
    return
  }

  CereveilScreen(modifier = modifier) {
    CereveilHeader(role = "Child", compact = state !is ChildEnrollmentUiState.Enrolled)
    when (val current = state) {
      ChildEnrollmentUiState.ProtectionSetup -> ProtectionSetup(
        onAccessibility = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
        onUsage = { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) },
        onLocationAndMic = {
          corePermissions.launch(
            arrayOf(
              Manifest.permission.ACCESS_COARSE_LOCATION,
              Manifest.permission.ACCESS_FINE_LOCATION,
              Manifest.permission.RECORD_AUDIO,
            ),
          )
        },
        onBackgroundLocation = {
          context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
          )
        },
        onNotifications = {
          if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
          else model.refreshProtectionSetup()
        },
        onBattery = {
          context.startActivity(
            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
          )
        },
        onAutomaticTime = { context.startActivity(Intent(Settings.ACTION_DATE_SETTINGS)) },
        onContinue = model::completeProtectionSetup,
      )
      ChildEnrollmentUiState.ReadyToScan -> {
        Text("Enrollment • Ready", style = MaterialTheme.typography.labelSmall)
        CereveilTitle("Ready to connect this phone", "Ask your Guardian to show the one-time QR code on their phone.")
        CereveilNotice("Cereveil checks the child name before anything is enrolled.")
        CereveilPrimaryButton(
          text = "Scan enrollment code",
          onClick = {
            scanner.startScan().addOnSuccessListener { barcode -> barcode.rawValue?.let(model::scanned) }
          },
        )
      }
      ChildEnrollmentUiState.PreviewLoading -> LoadingState("Checking the enrollment code")
      is ChildEnrollmentUiState.Preview -> {
        Text("Confirm enrollment", style = MaterialTheme.typography.labelSmall)
        CereveilTitle("Connect this phone for ${current.details.childDisplayName}?", "Your Guardian should confirm that this is the correct Child Profile.")
        CereveilCard {
          Text(current.details.childDisplayName, style = MaterialTheme.typography.headlineMedium)
          Text("This phone will stay in Child Mode after enrollment.")
        }
        CereveilPrimaryButton(text = "Yes, enroll this phone", onClick = model::confirmEnrollment)
        CereveilSecondaryButton(text = "Scan a different code", onClick = model::retryScan)
      }
      ChildEnrollmentUiState.Enrolling -> LoadingState("Connecting this Child Device")
      is ChildEnrollmentUiState.Enrolled -> ChildHome(current)
      is ChildEnrollmentUiState.Failure -> {
        CereveilTitle("That code didn’t work", childErrorText(current.error))
        CereveilNotice("Ask your Guardian to create a fresh code. Your phone has not been enrolled.")
        CereveilPrimaryButton(text = "Try another code", onClick = model::retryScan)
      }
    }
  }
}

@Composable
private fun GuardianHandoff(modifier: Modifier, onContinue: () -> Unit) {
  CereveilScreen(modifier = modifier, scrollable = false) {
    CereveilHeader(role = "Child")
    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
    CereveilTitle(
      title = "Let’s bring your Guardian in",
      supportingText = "Your Guardian will help set up Cereveil on this phone. You’ll both be able to see when protection is active.",
      centered = true,
    )
    CereveilNotice("Cereveil won’t start protection until setup is finished and this phone is enrolled.")
    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
    CereveilPrimaryButton(text = "I’m the Guardian — continue", onClick = onContinue)
    Text(
      "Stay nearby so you both know what is being set up.",
      style = MaterialTheme.typography.labelSmall,
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

@Composable
private fun ProtectionSetup(
  onAccessibility: () -> Unit,
  onUsage: () -> Unit,
  onLocationAndMic: () -> Unit,
  onBackgroundLocation: () -> Unit,
  onNotifications: () -> Unit,
  onBattery: () -> Unit,
  onAutomaticTime: () -> Unit,
  onContinue: () -> Unit,
) {
  Text("Protection setup • 7 settings", style = MaterialTheme.typography.labelSmall)
  CereveilTitle("Set up protection on this phone", "Android asks for each setting separately. Cereveil explains why before opening it.")
  PermissionCard("1", "Accessibility", "Helps apply the app controls your Guardian chooses.", onAccessibility)
  PermissionCard("2", "App usage", "Shares the latest launchable app names and today's Android-calculated per-app usage with your Guardian.", onUsage)
  PermissionCard("3", "Location and microphone", "Supports chosen safety features and audio checks.", onLocationAndMic)
  PermissionCard("4", "Allow all-the-time location", "In Android’s app settings, choose Location, then Allow all the time.", onBackgroundLocation)
  PermissionCard("5", "Notifications", "Keeps protection status and important alerts visible.", onNotifications)
  PermissionCard("6", "Battery access", "Helps protection continue reliably in the background.", onBattery)
  PermissionCard("7", "Automatic date and time", "Keep both automatic time and automatic time zone on so schedules and today's usage stay trustworthy.", onAutomaticTime)
  CereveilNotice("Safe Browsing and VPN access are not part of this hackathon build.")
  CereveilPrimaryButton(text = "Check settings and continue", onClick = onContinue)
}

@Composable
private fun PermissionCard(number: String, title: String, description: String, onClick: () -> Unit) {
  CereveilCard {
    Text("$number  $title", style = MaterialTheme.typography.titleLarge)
    Text(description, color = MaterialTheme.colorScheme.onSurfaceVariant)
    CereveilSecondaryButton(text = "Open setting", onClick = onClick)
  }
}

@Composable
private fun LoadingState(message: String) {
  CereveilTitle(message, "Keep this screen open for a moment.")
  CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun ChildHome(current: ChildEnrollmentUiState.Enrolled) {
  CereveilTitle(
    if (current.policyApplied) "Protection is on" else "Finishing protection setup",
    "This phone is connected as ${current.state.childDisplayName}’s Child Device.",
  )
  CereveilCard {
    Text("Cereveil is active", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.secondary)
    Text(if (current.policyApplied) "Guardian protection settings are applied." else "Guardian protection settings are finishing.")
  }
  CereveilNotice("You can always see that Cereveil is running. If a setting changes, your Guardian will be told.")
  Text(
    if (current.policyApplied) "Protection status shared" else "Protection status will retry automatically",
    style = MaterialTheme.typography.labelSmall,
  )
}

private fun childErrorText(error: ChildEnrollmentError) = when (error) {
  ChildEnrollmentError.InvalidCode -> "The enrollment code is invalid or has expired."
  ChildEnrollmentError.AlreadyEnrolled -> "That Child Profile is already connected to another phone."
  ChildEnrollmentError.EnrollmentFailed -> "Enrollment could not be completed."
  ChildEnrollmentError.ValidationFailed -> "The Child Device sent an invalid request."
  ChildEnrollmentError.PolicyVersionMismatch -> "Guardian protection settings have changed."
  ChildEnrollmentError.InvalidPolicy -> "Guardian protection settings could not be validated."
  ChildEnrollmentError.InternalError -> "Cereveil could not complete the request."
  ChildEnrollmentError.NetworkUnavailable -> "Check the internet connection on both phones."
  ChildEnrollmentError.Unauthorized -> "This Child Device is no longer authorized."
}
