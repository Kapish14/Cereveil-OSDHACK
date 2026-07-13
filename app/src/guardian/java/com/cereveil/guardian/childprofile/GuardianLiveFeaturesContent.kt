package com.cereveil.guardian.childprofile

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cereveil.CereveilApplication
import com.cereveil.R
import com.cereveil.guardian.auth.AndroidGuardianOperationBootstrapper
import com.cereveil.guardian.auth.SharedPreferencesGuardianInstallationIdProvider
import com.cereveil.ui.CereveilCard
import com.cereveil.ui.CereveilPrimaryButton
import com.cereveil.ui.CereveilSecondaryButton
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.MapView
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.MarkerOptions
import com.google.android.gms.maps.model.CircleOptions
import dev.convex.android.ConvexClient
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class GuardianCatalogApp(val packageName: String, val label: String, val blocked: Boolean, val scheduled: Boolean = false)
private data class GuardianBlockRule(val packageName: String, val manualBlocked: Boolean, val schedules: List<Map<String, Any?>>)
data class GuardianAccessRequest(val requestId: String, val packageName: String, val blockKind: String)
data class GuardianLocation(val latitude: Double, val longitude: Double, val accuracyMeters: Double, val capturedAt: Long)
data class GuardianScreenTimeApp(val packageName: String, val label: String, val totalMs: Long)
data class GuardianLiveFeaturesState(
  val loading: Boolean = true,
  val apps: List<GuardianCatalogApp> = emptyList(),
  val catalogSyncedAt: Long? = null,
  val accessRequests: List<GuardianAccessRequest> = emptyList(),
  val location: GuardianLocation? = null,
  val locationRefreshPending: Boolean = false,
  val screenTime: List<GuardianScreenTimeApp> = emptyList(),
  val screenMeasuredAt: Long? = null,
  val message: String? = null,
)

class GuardianLiveFeaturesViewModel(
  application: Application,
  private val childProfileId: String,
) : AndroidViewModel(application) {
  private val app = application as CereveilApplication
  private val convex: ConvexClient = app.convex
  private val installationProvider = SharedPreferencesGuardianInstallationIdProvider(app)
  private val bootstrapper = AndroidGuardianOperationBootstrapper(app)
  private val mutable = MutableStateFlow(GuardianLiveFeaturesState())
  val state = mutable.asStateFlow()
  private var installationId: String? = null
  private var policyVersion = 0
  private var manualBlocked = emptySet<String>()
  private var blockRules = emptyMap<String, GuardianBlockRule>()

  init { viewModelScope.launch { start() } }

  private suspend fun start() {
    installationId = installationProvider.getInstallationId().also { if (it == null) bootstrapper.ensureBootstrapped() }
      ?: installationProvider.getInstallationId()
    val id = installationId ?: run { mutable.value = mutable.value.copy(loading = false, message = "Guardian setup required") ; return }
    val args = mapOf("guardianInstallationId" to id, "childProfileId" to childProfileId)
    subscribeCatalog(args)
    subscribePolicy(args)
    subscribeAccess(args)
    subscribeLocation(args)
    viewModelScope.launch {
      while (true) {
        loadScreenTime(args)
        delay(30_000)
      }
    }
    mutable.value = mutable.value.copy(loading = false)
  }

  private fun subscribeCatalog(args: Map<String, Any?>) = viewModelScope.launch {
    convex.subscribe<Map<String, Any?>>("modules/appCatalog/guardian:getLatestAppCatalog", args).collect { result ->
      result.onSuccess { value ->
        @Suppress("UNCHECKED_CAST") val rows = value["apps"] as? List<Map<String, Any?>> ?: emptyList()
        val catalogApps = rows.map {
          val packageName = it["packageName"].toString()
          GuardianCatalogApp(packageName, it["label"].toString(), packageName in manualBlocked, blockRules[packageName]?.schedules?.isNotEmpty() == true)
        }
        val missing = blockRules.values.filter { rule -> catalogApps.none { it.packageName == rule.packageName } }
          .map { GuardianCatalogApp(it.packageName, "Not currently installed", it.manualBlocked, it.schedules.isNotEmpty()) }
        mutable.value = mutable.value.copy(
          apps = catalogApps + missing,
          catalogSyncedAt = (value["syncedAt"] as? Number)?.toLong(),
        )
      }
    }
  }

  private fun subscribePolicy(args: Map<String, Any?>) = viewModelScope.launch {
    convex.subscribe<Map<String, Any?>>("modules/policies/guardian:getPolicyState", args).collect { result ->
      result.onSuccess { value ->
        @Suppress("UNCHECKED_CAST") val desired = value["desiredPolicy"] as Map<String, Any?>
        policyVersion = (desired["version"] as Number).toInt()
        @Suppress("UNCHECKED_CAST") val block = desired["appBlocking"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val rules = block["rules"] as? List<Map<String, Any?>> ?: emptyList()
        blockRules = rules.associate { raw ->
          val packageName = raw["packageName"].toString()
          @Suppress("UNCHECKED_CAST") val schedules = raw["schedules"] as? List<Map<String, Any?>> ?: emptyList()
          packageName to GuardianBlockRule(packageName, raw["manualBlocked"] == true, schedules)
        }
        manualBlocked = blockRules.values.filter { it.manualBlocked }.map { it.packageName }.toSet()
        val updated = mutable.value.apps.map { it.copy(
          blocked = it.packageName in manualBlocked,
          scheduled = blockRules[it.packageName]?.schedules?.isNotEmpty() == true,
        ) }.toMutableList()
        blockRules.values.filter { rule -> updated.none { it.packageName == rule.packageName } }.forEach {
          updated += GuardianCatalogApp(it.packageName, "Not currently installed", it.manualBlocked, it.schedules.isNotEmpty())
        }
        mutable.value = mutable.value.copy(apps = updated)
      }
    }
  }

  private fun subscribeAccess(args: Map<String, Any?>) = viewModelScope.launch {
    convex.subscribe<List<Map<String, Any?>>>("modules/access/guardian:listPendingAccessRequests", args).collect { result ->
      result.onSuccess { rows -> mutable.value = mutable.value.copy(accessRequests = rows.map {
        GuardianAccessRequest(it["requestId"].toString(), it["packageName"].toString(), it["blockKind"].toString())
      }) }
    }
  }

  private fun subscribeLocation(args: Map<String, Any?>) = viewModelScope.launch {
    convex.subscribe<Map<String, Any?>>("modules/location/guardian:getLatestLocation", args).collect { result ->
      result.onSuccess { value ->
        @Suppress("UNCHECKED_CAST") val location = value["location"] as? Map<String, Any?>
        mutable.value = mutable.value.copy(
          location = location?.let { GuardianLocation(
            (it["latitude"] as Number).toDouble(), (it["longitude"] as Number).toDouble(),
            (it["accuracyMeters"] as Number).toDouble(), (it["capturedAt"] as Number).toLong(),
          ) },
          locationRefreshPending = value["refresh"] != null,
        )
      }
    }
  }

  private suspend fun loadScreenTime(args: Map<String, Any?>) {
    runCatching { convex.mutation<Map<String, Any?>>("modules/screenTime/guardian:getOrRequestScreenTime", args) }
      .onSuccess { value ->
        @Suppress("UNCHECKED_CAST") val snapshot = value["snapshot"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val rows = snapshot?.get("apps") as? List<Map<String, Any?>> ?: emptyList()
        mutable.value = mutable.value.copy(
          screenTime = rows.map { GuardianScreenTimeApp(
            it["packageName"].toString(), it["label"].toString(), (it["totalTimeInForegroundMs"] as Number).toLong(),
          ) },
          screenMeasuredAt = (snapshot?.get("measuredAt") as? Number)?.toLong(),
        )
      }
  }

  fun setManualBlock(packageName: String, blocked: Boolean) = mutate {
    val existing = blockRules[packageName] ?: GuardianBlockRule(packageName, false, emptyList())
    saveRules(if (!blocked && existing.schedules.isEmpty()) blockRules - packageName else blockRules +
      (packageName to existing.copy(manualBlocked = blocked)))
  }

  fun setNightSchedule(packageName: String, scheduled: Boolean) = mutate {
    val existing = blockRules[packageName] ?: GuardianBlockRule(packageName, false, emptyList())
    val schedules = if (scheduled) listOf(mapOf(
      "scheduleId" to "night",
      "weekdays" to (1L..7L).toList(),
      "startMinute" to 22L * 60,
      "endMinute" to 7L * 60,
    )) else emptyList()
    saveRules(if (!existing.manualBlocked && schedules.isEmpty()) blockRules - packageName else blockRules +
      (packageName to existing.copy(schedules = schedules)))
  }

  private suspend fun saveRules(next: Map<String, GuardianBlockRule>) {
    val rules = next.values.sortedBy { it.packageName }.map { mapOf(
      "packageName" to it.packageName,
      "manualBlocked" to it.manualBlocked,
      "schedules" to it.schedules,
    ) }
    convex.mutation<Map<String, Any?>>("modules/policies/guardian:updateAppBlockingRules", commonArgs() + mapOf(
      "expectedCurrentVersion" to policyVersion.toLong(), "operationId" to UUID.randomUUID().toString(), "rules" to rules,
    ))
  }

  fun requestLocation() = mutate {
    convex.mutation<Map<String, Any?>>("modules/location/guardian:requestLocationRefresh", commonArgs())
  }

  fun resolveAccess(requestId: String, approve: Boolean, minutes: Int = 15) = mutate {
    convex.mutation<Map<String, Any?>>("modules/access/guardian:resolveAccessRequest", mapOf(
      "guardianInstallationId" to installationId, "requestId" to requestId,
      "decision" to if (approve) "approve" else "deny",
      "durationMinutes" to if (approve) minutes.toLong() else null,
    ).filterValues { it != null })
  }

  private fun commonArgs() = mapOf("guardianInstallationId" to installationId, "childProfileId" to childProfileId)
  private fun mutate(block: suspend () -> Any) = viewModelScope.launch {
    runCatching { block() }.onFailure { mutable.value = mutable.value.copy(message = "Couldn’t complete that request") }
  }
}

@Composable
fun GuardianLiveFeaturesContent(childProfileId: String) {
  val application = LocalContext.current.applicationContext as Application
  val factory = remember(childProfileId) { viewModelFactory { initializer {
    GuardianLiveFeaturesViewModel(application, childProfileId)
  } } }
  val model: GuardianLiveFeaturesViewModel = viewModel(key = "live-$childProfileId", factory = factory)
  val state by model.state.collectAsStateWithLifecycle()
  if (state.loading) { CircularProgressIndicator(); return }
  state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

  Text("App blocking", style = MaterialTheme.typography.titleLarge)
  state.catalogSyncedAt?.let {
    Text("App list updated ${(System.currentTimeMillis() - it).coerceAtLeast(0) / 60_000} min ago")
  }
  if (state.apps.isEmpty()) Text("Waiting for the Child Device app list.")
  state.apps.forEach { item -> CereveilCard {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Column(Modifier.weight(1f)) { Text(item.label); Text(item.packageName, style = MaterialTheme.typography.labelSmall) }
      CereveilSecondaryButton(text = if (item.blocked) "Unblock" else "Block", onClick = {
        model.setManualBlock(item.packageName, !item.blocked)
      })
    }
    CereveilSecondaryButton(
      text = if (item.scheduled) "Remove 10pm–7am schedule" else "Schedule 10pm–7am daily",
      onClick = { model.setNightSchedule(item.packageName, !item.scheduled) },
    )
  } }

  if (state.accessRequests.isNotEmpty()) Text("Access requests", style = MaterialTheme.typography.titleLarge)
  state.accessRequests.forEach { request -> CereveilCard {
    Text("${request.packageName} requested access")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      CereveilPrimaryButton(text = "Allow 15 min", onClick = { model.resolveAccess(request.requestId, true) })
      CereveilSecondaryButton(text = "Deny", onClick = { model.resolveAccess(request.requestId, false) })
    }
  } }

  Text("Latest location", style = MaterialTheme.typography.titleLarge)
  state.location?.let { location ->
    if (LocalContext.current.getString(R.string.google_maps_key).isNotBlank()) GuardianLocationMap(location)
    val ageMinutes = ((System.currentTimeMillis() - location.capturedAt).coerceAtLeast(0) / 60_000)
    Text("${"%.5f".format(location.latitude)}, ${"%.5f".format(location.longitude)}")
    Text("Updated $ageMinutes min ago • ±${location.accuracyMeters.toInt()} m")
    if (ageMinutes >= 30) Text("Location may be outdated.", color = MaterialTheme.colorScheme.tertiary)
    val context = LocalContext.current
    CereveilSecondaryButton(text = "Open in maps", onClick = {
      val uri = Uri.parse("geo:${location.latitude},${location.longitude}?q=${location.latitude},${location.longitude}")
      runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    })
  } ?: Text("No location received yet.")
  CereveilSecondaryButton(text = if (state.locationRefreshPending) "Refreshing…" else "Refresh location now", onClick = {
    if (!state.locationRefreshPending) model.requestLocation()
  })

  Text("Today’s screen time", style = MaterialTheme.typography.titleLarge)
  if (state.screenTime.isEmpty()) Text("Fetching current Android usage…")
  state.screenTime.forEach { row -> CereveilCard {
    Text(row.label)
    Text(formatDuration(row.totalMs))
  } }
}

@Composable
private fun GuardianLocationMap(location: GuardianLocation) {
  val context = LocalContext.current
  val mapView = remember { MapView(context).apply { onCreate(Bundle()) } }
  DisposableEffect(mapView) {
    mapView.onStart(); mapView.onResume()
    onDispose { mapView.onPause(); mapView.onStop(); mapView.onDestroy() }
  }
  AndroidView(factory = { mapView }, modifier = Modifier.fillMaxWidth().height(220.dp)) { view ->
    view.getMapAsync { map ->
      val point = LatLng(location.latitude, location.longitude)
      map.clear(); map.addMarker(MarkerOptions().position(point).title("Latest known location"))
      map.addCircle(CircleOptions().center(point).radius(location.accuracyMeters).strokeWidth(2f).fillColor(0x223F51B5))
      map.moveCamera(CameraUpdateFactory.newLatLngZoom(point, 16f))
    }
  }
}

private fun formatDuration(ms: Long): String {
  val minutes = ms / 60_000
  return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "${minutes}m"
}
