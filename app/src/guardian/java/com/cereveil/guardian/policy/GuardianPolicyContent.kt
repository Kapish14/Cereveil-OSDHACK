package com.cereveil.guardian.policy

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cereveil.CereveilApplication
import com.cereveil.guardian.auth.AndroidGuardianOperationBootstrapper
import com.cereveil.guardian.auth.SharedPreferencesGuardianInstallationIdProvider
import com.cereveil.ui.CereveilCard

@Composable
fun GuardianPolicyContent(childProfileId: String) {
  val application = LocalContext.current.applicationContext as CereveilApplication
  val factory = remember(childProfileId) { viewModelFactory { initializer {
    GuardianPolicyViewModel(childProfileId, ConvexGuardianPolicyClient(
      application.convex,
      SharedPreferencesGuardianInstallationIdProvider(application),
      AndroidGuardianOperationBootstrapper(application),
    ))
  } } }
  val model: GuardianPolicyViewModel = viewModel(key = "policy-$childProfileId", factory = factory)
  val state by model.state.collectAsStateWithLifecycle()
  when (val current = state) {
    GuardianPolicyUiState.Loading -> CircularProgressIndicator()
    is GuardianPolicyUiState.Error -> Text(
      if (current.error == GuardianPolicyError.Unsupported) "Update the Child Device app to change settings."
      else "Couldn’t load supervision settings.",
      color = MaterialTheme.colorScheme.error,
    )
    is GuardianPolicyUiState.Ready -> {
      Text("Development supervision settings", style = MaterialTheme.typography.titleLarge)
      PolicyToggle(PolicyControl.AppBlocking, current, model::update)
      PolicyToggle(PolicyControl.SafeBrowsing, current, onChange = { feature, enabled, _ ->
        model.update(feature, enabled, if (enabled) current.policy.desired.safeSearchEnabled else false)
      })
      PolicyToggle(PolicyControl.SafeSearch, current, onChange = { _, enabled, _ ->
        model.update(PolicyFeature.SafeBrowsing, current.policy.desired.safeBrowsingEnabled, enabled)
      }, enabledByParent = current.policy.desired.safeBrowsingEnabled)
      Text("Active Screen Safety", style = MaterialTheme.typography.titleLarge)
      SafetyDetectorCard(
        title = "Scam Text Detection",
        detector = GuardianSafetyDetector.ScamText,
        desired = current.policy.desired.scamTextSafety,
        applied = current.policy.applied?.scamTextSafety,
        apps = current.catalogApps,
        saving = current.savingFeature == PolicyFeature.ScamTextSafety,
        onChange = model::updateSafety,
      )
      SafetyDetectorCard(
        title = "NSFW Screen Detection",
        detector = GuardianSafetyDetector.NsfwScreen,
        desired = current.policy.desired.nsfwScreenSafety,
        applied = current.policy.applied?.nsfwScreenSafety,
        apps = current.catalogApps,
        saving = current.savingFeature == PolicyFeature.NsfwScreenSafety,
        available = current.policy.supportsNsfwScreenDetection,
        onChange = model::updateSafety,
      )
      PolicyToggle(PolicyControl.LocationSharing, current, model::update)
      PolicyToggle(PolicyControl.ScreenTime, current, model::update)
      current.updateError?.let { error ->
        Text(
          if (error == GuardianPolicyError.Conflict) "Settings changed on another Guardian Device. Reloaded latest settings."
          else "Couldn’t save this setting.",
          color = MaterialTheme.colorScheme.error,
        )
      }
    }
  }
}

@Composable
private fun SafetyDetectorCard(
  title: String,
  detector: GuardianSafetyDetector,
  desired: GuardianSafetyDetectorPolicy,
  applied: GuardianSafetyDetectorPolicy?,
  apps: List<GuardianSelectableApp>,
  saving: Boolean,
  available: Boolean = true,
  onChange: (GuardianSafetyDetector, GuardianSafetyDetectorPolicy) -> Unit,
) {
  var search by remember(detector) { mutableStateOf("") }
  val pending = saving || applied != desired
  val visibleApps = apps.filter {
    search.isBlank() || it.label.contains(search, true) || it.packageName.contains(search, true)
  }
  CereveilCard {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(if (detector == GuardianSafetyDetector.NsfwScreen) "Android 11+ • blur only" else "Visible message text • warning")
      }
      if (pending) CircularProgressIndicator(modifier = Modifier.fillMaxWidth(0.08f), strokeWidth = 2.dp)
      Switch(
        checked = desired.enabled,
        onCheckedChange = { enabled -> onChange(detector, desired.copy(enabled = enabled)) },
        enabled = available && !pending && (!desired.monitoredPackageNames.isEmpty() || desired.enabled),
      )
    }
    if (!available) Text("Unavailable on this Child Device. NSFW Screen Detection requires Android 11 or later.")
    Text("Sensitivity")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      GuardianSafetySensitivity.entries.forEach { sensitivity ->
        FilterChip(
          selected = desired.sensitivity == sensitivity,
          onClick = { onChange(detector, desired.copy(sensitivity = sensitivity)) },
          label = { Text(sensitivity.name) },
          enabled = available && !pending,
        )
      }
    }
    OutlinedTextField(
      value = search,
      onValueChange = { search = it },
      label = { Text("Search monitored apps") },
      modifier = Modifier.fillMaxWidth(),
      singleLine = true,
    )
    visibleApps.forEach { app ->
      val selected = app.packageName in desired.monitoredPackageNames
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(
          checked = selected,
          onCheckedChange = { checked ->
            if (!checked && desired.enabled && desired.monitoredPackageNames.size == 1) return@Checkbox
            val packages = if (checked) desired.monitoredPackageNames + app.packageName
            else desired.monitoredPackageNames - app.packageName
            onChange(detector, desired.copy(monitoredPackageNames = packages))
          },
          enabled = available && !pending,
        )
        Column {
          Text(app.label)
          Text(app.packageName, style = MaterialTheme.typography.labelSmall)
        }
      }
    }
    desired.monitoredPackageNames.filter { selected -> apps.none { it.packageName == selected } }.forEach { packageName ->
      Text("$packageName • not currently installed", style = MaterialTheme.typography.labelSmall)
    }
    if (pending) Text("Waiting for Child Device")
  }
}

@Composable
private fun PolicyToggle(
  control: PolicyControl,
  state: GuardianPolicyUiState.Ready,
  onChange: (PolicyFeature, Boolean, Boolean) -> Unit,
  enabledByParent: Boolean = true,
) {
  val feature = control.feature
  val applied = state.policy.applied
  val appliedValue = applied?.let(control::value) ?: false
  val desiredValue = control.value(state.policy.desired)
  val pending = appliedValue != desiredValue || state.savingFeature == feature
  var longWait by remember(state.policy.desired.version, state.policy.applied?.version, feature) {
    mutableStateOf(false)
  }
  LaunchedEffect(pending, state.policy.desired.version, feature) {
    longWait = false
    if (pending) {
      delay(3_000)
      longWait = true
    }
  }
  CereveilCard {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Text(control.label, modifier = Modifier.weight(1f))
      if (pending) CircularProgressIndicator(modifier = Modifier.fillMaxWidth(0.08f), strokeWidth = 2.dp)
      Switch(
        checked = appliedValue,
        onCheckedChange = { onChange(feature, it, false) },
        enabled = enabledByParent && !pending,
      )
    }
    if (state.policy.status == PolicyApplicationStatus.Failed && pending) Text("Couldn’t apply")
    else if (pending && longWait) Text("Waiting for Child Device")
  }
}

private enum class PolicyControl(val label: String, val feature: PolicyFeature) {
  AppBlocking("App Blocking", PolicyFeature.AppBlocking),
  SafeBrowsing("Safe Browsing", PolicyFeature.SafeBrowsing),
  SafeSearch("Safe Search", PolicyFeature.SafeBrowsing),
  LocationSharing("Location sharing", PolicyFeature.LocationSharing),
  ScreenTime("Screen Time", PolicyFeature.ScreenTime);

  fun value(policy: GuardianPolicy) = when (this) {
    AppBlocking -> policy.appBlockingEnabled
    SafeBrowsing -> policy.safeBrowsingEnabled
    SafeSearch -> policy.safeSearchEnabled
    LocationSharing -> policy.locationSharingEnabled
    ScreenTime -> policy.screenTimeEnabled
  }
}
