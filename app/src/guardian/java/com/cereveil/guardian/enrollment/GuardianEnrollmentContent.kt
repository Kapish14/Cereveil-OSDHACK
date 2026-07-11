package com.cereveil.guardian.enrollment

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cereveil.CereveilApplication
import com.cereveil.ui.CereveilCard
import com.cereveil.ui.CereveilHeader
import com.cereveil.ui.CereveilNotice
import com.cereveil.ui.CereveilPrimaryButton
import com.cereveil.ui.CereveilScreen
import com.cereveil.ui.CereveilSecondaryButton
import com.cereveil.ui.CereveilTitle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.delay
import com.cereveil.guardian.auth.SharedPreferencesGuardianInstallationIdProvider

@Composable
fun GuardianEnrollmentContent(childProfileId: String, childDisplayName: String, onBack: () -> Unit) {
  val application = LocalContext.current.applicationContext as CereveilApplication
  val factory = remember(childProfileId) {
    viewModelFactory {
      initializer {
        GuardianEnrollmentViewModel(
          childProfileId,
          ConvexGuardianEnrollmentClient(
            application.convex,
            SharedPreferencesGuardianInstallationIdProvider(application),
          ),
        )
      }
    }
  }
  val model: GuardianEnrollmentViewModel = viewModel(key = childProfileId, factory = factory)
  val state by model.state.collectAsStateWithLifecycle()

  CereveilScreen {
    CereveilHeader(role = "Guardian", compact = true)
    Text("Child setup • Step 2 of 2", style = MaterialTheme.typography.labelSmall)
    when (val current = state) {
      GuardianEnrollmentUiState.Loading -> {
        CereveilTitle("Preparing ${childDisplayName}’s device", "Keep both phones nearby while Cereveil creates a secure one-time code.")
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
      }
      is GuardianEnrollmentUiState.ShowingCode -> EnrollmentCode(
        displayName = childDisplayName,
        code = current.code,
        onRegenerate = model::regenerate,
        onCancel = model::cancel,
      )
      is GuardianEnrollmentUiState.Enrolled -> {
        CereveilTitle(
          "${childDisplayName}’s phone is connected",
          if (current.protectionHealthStatus == GuardianProtectionHealthStatus.Pending) {
            "Cereveil is waiting for the first protection status from the Child Device."
          } else {
            "The first protection status has reached the Guardian app."
          },
        )
        StatusCard("Device enrollment", "Complete")
        StatusCard("Device status", when (current.connectivityStatus) {
          GuardianConnectivityStatus.Pending -> "Waiting for first report"
          GuardianConnectivityStatus.Online -> "Online"
          GuardianConnectivityStatus.Offline -> "Offline"
          GuardianConnectivityStatus.NotApplicable -> "Unavailable"
        })
        StatusCard("Guardian settings", if (current.policyStatus == GuardianPolicyStatus.Applied) "Applied" else "Finishing")
        StatusCard(
          "Protection status",
          if (current.protectionHealthStatus == GuardianProtectionHealthStatus.Pending) "Checking" else "Reported",
        )
        CereveilNotice("Cereveil will keep reporting if an important protection setting changes.")
        CereveilPrimaryButton(text = "Go to Guardian home", onClick = onBack)
      }
      GuardianEnrollmentUiState.Cancelled -> {
        CereveilTitle("Setup paused", "No device was enrolled. You can create a fresh code whenever you are ready.")
        CereveilPrimaryButton(text = "Back to children", onClick = onBack)
      }
      is GuardianEnrollmentUiState.Failure -> {
        CereveilTitle("We couldn’t create the code", "Check your connection, then try again. No Child Device has been changed.")
        CereveilPrimaryButton(text = "Try again", onClick = model::regenerate)
        CereveilSecondaryButton(text = "Back", onClick = onBack)
      }
    }
  }
}

@Composable
private fun EnrollmentCode(
  displayName: String,
  code: GuardianEnrollmentCode,
  onRegenerate: () -> Unit,
  onCancel: () -> Unit,
) {
  val remainingSeconds = enrollmentRemainingSeconds(code)
  CereveilTitle("Scan this code on ${displayName}’s phone", "Open the Cereveil Child app, finish protection setup, then tap Scan enrollment code.")
  CereveilCard {
    Box(
      modifier = Modifier.fillMaxWidth().background(Color.White, RoundedCornerShape(12.dp)).padding(12.dp),
      contentAlignment = Alignment.Center,
    ) {
      if (remainingSeconds > 0) {
        Image(
          bitmap = remember(code.qrPayload) { qrBitmap(code.qrPayload).asImageBitmap() },
          contentDescription = "Child enrollment QR code",
          modifier = Modifier.size(264.dp),
        )
      } else {
        Text("Code expired", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(80.dp))
      }
    }
    Text(
      if (remainingSeconds > 0) "One-time code • ${remainingSeconds / 60}:${(remainingSeconds % 60).toString().padStart(2, '0')} remaining" else "Create a new one-time code to continue.",
      style = MaterialTheme.typography.bodyMedium,
    )
  }
  CereveilNotice("Only use this code with the Child Device beside you. Never send a screenshot of it.")
  CereveilPrimaryButton(text = if (remainingSeconds > 0) "Create a fresh code" else "Create new code", onClick = onRegenerate)
  CereveilSecondaryButton(text = "Cancel setup", onClick = onCancel)
}

@Composable
private fun StatusCard(label: String, status: String) {
  CereveilCard {
    Text(label, style = MaterialTheme.typography.titleLarge)
    Text(status, color = MaterialTheme.colorScheme.secondary)
  }
}

@Composable
private fun enrollmentRemainingSeconds(code: GuardianEnrollmentCode): Long {
  val serverOffset = code.serverNow - System.currentTimeMillis()
  var remaining by remember(code.enrollmentCodeId) {
    mutableLongStateOf(((code.expiresAt - code.serverNow) / 1000).coerceAtLeast(0))
  }
  LaunchedEffect(code.enrollmentCodeId) {
    while (remaining > 0) {
      delay(1_000)
      remaining = ((code.expiresAt - (System.currentTimeMillis() + serverOffset)) / 1000).coerceAtLeast(0)
    }
  }
  return remaining
}

private fun qrBitmap(payload: String): Bitmap {
  val matrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 720, 720)
  return Bitmap.createBitmap(720, 720, Bitmap.Config.ARGB_8888).apply {
    for (x in 0 until matrix.width) for (y in 0 until matrix.height) {
      setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
  }
}
