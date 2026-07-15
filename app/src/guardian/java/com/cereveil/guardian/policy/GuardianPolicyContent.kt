package com.cereveil.guardian.policy

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.delay
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cereveil.BuildConfig
import com.cereveil.CereveilApplication
import com.cereveil.guardian.auth.AndroidGuardianOperationBootstrapper
import com.cereveil.guardian.auth.SharedPreferencesGuardianInstallationIdProvider
import com.cereveil.guardian.ui.GuardianCard as CereveilCard
import com.cereveil.guardian.ui.GuardianSecondaryButton as CereveilSecondaryButton
import com.cereveil.guardian.ui.GuardianAppIcon

enum class GuardianPolicySection { All, Settings, AppBlocking, ScamText, NsfwScreen }

@Composable
fun GuardianPolicyContent(
  childProfileId: String,
  showDevelopmentSafetyControls: Boolean = BuildConfig.DEBUG,
  section: GuardianPolicySection = GuardianPolicySection.All,
) {
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
      if (section == GuardianPolicySection.All || section == GuardianPolicySection.AppBlocking) {
        if (section == GuardianPolicySection.All) Text("App blocking", style = MaterialTheme.typography.titleLarge)
        PolicyToggle(
          PolicyControl.AppBlocking,
          current,
          model::update,
          label = if (section == GuardianPolicySection.AppBlocking) "Enabled" else null,
        )
      }
      if (showDevelopmentSafetyControls &&
        (section == GuardianPolicySection.All || section == GuardianPolicySection.ScamText)
      ) {
        if (section == GuardianPolicySection.All) {
          Text("Active Screen Safety", style = MaterialTheme.typography.titleLarge)
        }
        SafetyDetectorCard(
          title = "Scam Text Detection",
          detector = GuardianSafetyDetector.ScamText,
          desired = current.policy.desired.scamTextSafety,
          applied = current.policy.applied?.scamTextSafety,
          apps = current.catalogApps,
          saving = current.savingFeature == PolicyFeature.ScamTextSafety,
          compact = section != GuardianPolicySection.All,
          onChange = model::updateSafety,
        )
      }
      if (showDevelopmentSafetyControls &&
        (section == GuardianPolicySection.All || section == GuardianPolicySection.NsfwScreen)
      ) {
        SafetyDetectorCard(
          title = "NSFW Screen Detection",
          detector = GuardianSafetyDetector.NsfwScreen,
          desired = current.policy.desired.nsfwScreenSafety,
          applied = current.policy.applied?.nsfwScreenSafety,
          apps = current.catalogApps,
          saving = current.savingFeature == PolicyFeature.NsfwScreenSafety,
          available = current.policy.supportsNsfwScreenDetection,
          compact = section != GuardianPolicySection.All,
          onChange = model::updateSafety,
        )
      }
      if (section == GuardianPolicySection.All || section == GuardianPolicySection.Settings) {
        Text("General supervision", style = MaterialTheme.typography.titleLarge)
        PolicyToggle(PolicyControl.LocationSharing, current, model::update)
        PolicyToggle(PolicyControl.ScreenTime, current, model::update)
      }
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
  compact: Boolean = false,
  onChange: (GuardianSafetyDetector, GuardianSafetyDetectorPolicy) -> Unit,
) {
  var search by remember(detector) { mutableStateOf("") }
  var choosingApps by remember(detector) { mutableStateOf(false) }
  val pending = saving || applied != desired
  val visibleApps = apps.filter {
    search.isBlank() || it.label.contains(search, true) || it.packageName.contains(search, true)
  }
  CereveilCard {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      Column(Modifier.weight(1f)) {
        Text(if (compact) "Detection" else title, style = MaterialTheme.typography.titleMedium)
        if (!compact) Text(if (detector == GuardianSafetyDetector.NsfwScreen) "Screens • blur" else "Messages • warning")
      }
      if (pending) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
      Switch(
        checked = desired.enabled,
        onCheckedChange = { enabled -> onChange(detector, desired.copy(enabled = enabled)) },
        enabled = available && !pending && (!desired.monitoredPackageNames.isEmpty() || desired.enabled),
      )
    }
    if (!available) Text("Requires Android 11+")
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
    Text(
      if (desired.monitoredPackageNames.isEmpty()) "No apps selected"
      else "${desired.monitoredPackageNames.size} app${if (desired.monitoredPackageNames.size == 1) "" else "s"} selected",
      style = MaterialTheme.typography.labelSmall,
    )
    CereveilSecondaryButton(
      text = if (choosingApps) "Done choosing apps" else "Choose monitored apps",
      onClick = { choosingApps = !choosingApps },
      leadingIcon = Icons.Default.Apps,
    )
    if (choosingApps) {
      OutlinedTextField(
        value = search,
        onValueChange = { search = it },
        placeholder = { Text("Search apps") },
        leadingIcon = { androidx.compose.material3.Icon(Icons.Default.Search, contentDescription = null) },
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
      )
      LazyColumn(
        modifier = Modifier.fillMaxWidth().heightIn(max = 420.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(visibleApps, key = { it.packageName }) { app ->
          val selected = app.packageName in desired.monitoredPackageNames
          Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            GuardianAppIcon(app.packageName, app.label)
            Spacer(Modifier.width(12.dp))
            Text(app.label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
          }
        }
      }
      desired.monitoredPackageNames.filter { selected -> apps.none { it.packageName == selected } }.forEach {
        Text("Previously selected app • not currently installed", style = MaterialTheme.typography.labelSmall)
      }
    }
  }
}

@Composable
private fun PolicyToggle(
  control: PolicyControl,
  state: GuardianPolicyUiState.Ready,
  onChange: (PolicyFeature, Boolean, Boolean) -> Unit,
  label: String? = null,
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
      Text(label ?: control.label, modifier = Modifier.weight(1f))
      if (pending) CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
      Switch(
        checked = appliedValue,
        onCheckedChange = { onChange(feature, it, false) },
        enabled = !pending,
      )
    }
    if (state.policy.status == PolicyApplicationStatus.Failed && pending) Text("Couldn’t apply")
    else if (pending && longWait) Text("Waiting for Child Device")
  }
}

private enum class PolicyControl(val label: String, val feature: PolicyFeature) {
  AppBlocking("App Blocking", PolicyFeature.AppBlocking),
  LocationSharing("Location sharing", PolicyFeature.LocationSharing),
  ScreenTime("Screen Time", PolicyFeature.ScreenTime);

  fun value(policy: GuardianPolicy) = when (this) {
    AppBlocking -> policy.appBlockingEnabled
    LocationSharing -> policy.locationSharingEnabled
    ScreenTime -> policy.screenTimeEnabled
  }
}
