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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
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
import kotlinx.coroutines.Job

data class GuardianCatalogApp(
  val packageName: String,
  val label: String,
  val blocked: Boolean,
  val schedules: List<GuardianBlockSchedule> = emptyList(),
  val policyPending: Boolean = false,
)
data class GuardianBlockSchedule(
  val scheduleId: String,
  val weekdays: List<Int>,
  val startMinute: Int,
  val endMinute: Int,
)
private data class GuardianBlockRule(val packageName: String, val manualBlocked: Boolean, val schedules: List<GuardianBlockSchedule>)
data class GuardianAccessRequest(
  val requestId: String,
  val packageName: String,
  val blockKind: String,
  val scheduledCoverageEnd: Long? = null,
)
data class GuardianLocation(val latitude: Double, val longitude: Double, val accuracyMeters: Double, val capturedAt: Long)
data class GuardianScreenTimeApp(val packageName: String, val label: String, val totalMs: Long)
data class GuardianLiveFeaturesState(
  val loading: Boolean = true,
  val apps: List<GuardianCatalogApp> = emptyList(),
  val catalogSyncedAt: Long? = null,
  val accessRequests: List<GuardianAccessRequest> = emptyList(),
  val location: GuardianLocation? = null,
  val locationRefreshPending: Boolean = false,
  val locationRefreshStatus: String? = null,
  val screenTime: List<GuardianScreenTimeApp> = emptyList(),
  val screenMeasuredAt: Long? = null,
  val screenLoading: Boolean = true,
  val screenError: Boolean = false,
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
  private var appliedBlockRules = emptyMap<String, GuardianBlockRule>()
  private var visible = false
  private var screenRefreshJob: Job? = null

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
    if (visible) startScreenRefresh()
    mutable.value = mutable.value.copy(loading = false)
  }

  fun setVisible(isVisible: Boolean) {
    visible = isVisible
    if (isVisible) startScreenRefresh() else screenRefreshJob?.cancel()
  }

  private fun startScreenRefresh() {
    val id = installationId ?: return
    if (screenRefreshJob?.isActive == true) return
    val args = mapOf("guardianInstallationId" to id, "childProfileId" to childProfileId)
    screenRefreshJob = viewModelScope.launch {
      while (true) {
        loadScreenTime(args)
        delay(30_000)
      }
    }
  }

  private fun subscribeCatalog(args: Map<String, Any?>) = viewModelScope.launch {
    convex.subscribe<Map<String, Any?>>("modules/appCatalog/guardian:getLatestAppCatalog", args).collect { result ->
      result.onFailure { mutable.value = mutable.value.copy(message = "Couldn’t load the Child Device app list") }
      result.onSuccess { value ->
        @Suppress("UNCHECKED_CAST") val rows = value["apps"] as? List<Map<String, Any?>> ?: emptyList()
        val catalogApps = rows.map {
          val packageName = it["packageName"].toString()
          GuardianCatalogApp(
            packageName, it["label"].toString(), packageName in manualBlocked,
            blockRules[packageName]?.schedules.orEmpty(), blockRules[packageName] != appliedBlockRules[packageName],
          )
        }
        val missing = blockRules.values.filter { rule -> catalogApps.none { it.packageName == rule.packageName } }
          .map { GuardianCatalogApp(it.packageName, "Not currently installed", it.manualBlocked, it.schedules, it != appliedBlockRules[it.packageName]) }
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
        blockRules = parseRules(rules)
        @Suppress("UNCHECKED_CAST") val applied = value["appliedPolicy"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val appliedBlock = applied?.get("appBlocking") as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val appliedRulesRaw = appliedBlock?.get("rules") as? List<Map<String, Any?>> ?: emptyList()
        appliedBlockRules = parseRules(appliedRulesRaw)
        manualBlocked = blockRules.values.filter { it.manualBlocked }.map { it.packageName }.toSet()
        val updated = mutable.value.apps.map { it.copy(
          blocked = it.packageName in manualBlocked,
          schedules = blockRules[it.packageName]?.schedules.orEmpty(),
          policyPending = blockRules[it.packageName] != appliedBlockRules[it.packageName],
        ) }.toMutableList()
        blockRules.values.filter { rule -> updated.none { it.packageName == rule.packageName } }.forEach {
          updated += GuardianCatalogApp(
            it.packageName, "Not currently installed", it.manualBlocked, it.schedules,
            it != appliedBlockRules[it.packageName],
          )
        }
        mutable.value = mutable.value.copy(apps = updated)
      }
    }
  }

  private fun parseRules(rules: List<Map<String, Any?>>): Map<String, GuardianBlockRule> =
    rules.associate { raw ->
          val packageName = raw["packageName"].toString()
          @Suppress("UNCHECKED_CAST") val schedulesRaw = raw["schedules"] as? List<Map<String, Any?>> ?: emptyList()
          val schedules = schedulesRaw.map { schedule -> GuardianBlockSchedule(
            schedule["scheduleId"].toString(),
            ((schedule["weekdays"] as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }).orEmpty(),
            (schedule["startMinute"] as Number).toInt(),
            (schedule["endMinute"] as Number).toInt(),
          ) }
          packageName to GuardianBlockRule(packageName, raw["manualBlocked"] == true, schedules)
        }

  private fun subscribeAccess(args: Map<String, Any?>) = viewModelScope.launch {
    convex.subscribe<List<Map<String, Any?>>>("modules/access/guardian:listPendingAccessRequests", args).collect { result ->
      result.onSuccess { rows -> mutable.value = mutable.value.copy(accessRequests = rows.map {
        GuardianAccessRequest(
          it["requestId"].toString(), it["packageName"].toString(), it["blockKind"].toString(),
          (it["scheduledCoverageEnd"] as? Number)?.toLong(),
        )
      }) }
    }
  }

  private fun subscribeLocation(args: Map<String, Any?>) = viewModelScope.launch {
    convex.subscribe<Map<String, Any?>>("modules/location/guardian:getLatestLocation", args).collect { result ->
      result.onSuccess { value ->
        @Suppress("UNCHECKED_CAST") val location = value["location"] as? Map<String, Any?>
        @Suppress("UNCHECKED_CAST") val refresh = value["refresh"] as? Map<String, Any?>
        mutable.value = mutable.value.copy(
          location = location?.let { GuardianLocation(
            (it["latitude"] as Number).toDouble(), (it["longitude"] as Number).toDouble(),
            (it["accuracyMeters"] as Number).toDouble(), (it["capturedAt"] as Number).toLong(),
          ) },
          locationRefreshPending = refresh?.get("status")?.toString() == "pending",
          locationRefreshStatus = refresh?.get("status")?.toString(),
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
          screenLoading = false,
          screenError = false,
        )
      }
      .onFailure { mutable.value = mutable.value.copy(screenLoading = false, screenError = true) }
  }

  fun setManualBlock(packageName: String, blocked: Boolean) = mutate {
    val existing = blockRules[packageName] ?: GuardianBlockRule(packageName, false, emptyList())
    saveRules(if (!blocked && existing.schedules.isEmpty()) blockRules - packageName else blockRules +
      (packageName to existing.copy(manualBlocked = blocked)))
  }

  fun addSchedule(packageName: String, weekdaysText: String, startText: String, endText: String) = mutate {
    val existing = blockRules[packageName] ?: GuardianBlockRule(packageName, false, emptyList())
    val weekdays = weekdaysText.split(',').mapNotNull { it.trim().toIntOrNull() }.distinct()
    val start = parseMinute(startText)
    val end = parseMinute(endText)
    if (weekdays.isEmpty() || weekdays.any { it !in 1..7 } || start == null || end == null || start == end || existing.schedules.size >= 8) {
      error("Invalid schedule")
    }
    val schedule = GuardianBlockSchedule(UUID.randomUUID().toString(), weekdays, start, end)
    saveRules(blockRules + (packageName to existing.copy(schedules = existing.schedules + schedule)))
  }

  fun removeSchedule(packageName: String, scheduleId: String) = mutate {
    val existing = blockRules[packageName] ?: return@mutate
    val schedules = existing.schedules.filterNot { it.scheduleId == scheduleId }
    saveRules(if (!existing.manualBlocked && schedules.isEmpty()) blockRules - packageName else blockRules +
      (packageName to existing.copy(schedules = schedules)))
  }

  private suspend fun saveRules(next: Map<String, GuardianBlockRule>) {
    val rules = next.values.sortedBy { it.packageName }.map { mapOf(
      "packageName" to it.packageName,
      "manualBlocked" to it.manualBlocked,
      "schedules" to it.schedules.map { schedule -> mapOf(
        "scheduleId" to schedule.scheduleId,
        "weekdays" to schedule.weekdays.map(Int::toLong),
        "startMinute" to schedule.startMinute.toLong(),
        "endMinute" to schedule.endMinute.toLong(),
      ) },
    ) }
    convex.mutation<Map<String, Any?>>("modules/policies/guardian:updateAppBlockingRules", commonArgs() + mapOf(
      "expectedCurrentVersion" to policyVersion.toLong(), "operationId" to UUID.randomUUID().toString(), "rules" to rules,
    ))
  }

  fun requestLocation() = mutate {
    convex.mutation<Map<String, Any?>>("modules/location/guardian:requestLocationRefresh", commonArgs())
  }

  fun resolveAccess(requestId: String, approve: Boolean, minutes: Int = 15, untilBlockEnds: Boolean = false) = mutate {
    convex.mutation<Map<String, Any?>>("modules/access/guardian:resolveAccessRequest", mapOf(
      "guardianInstallationId" to installationId, "requestId" to requestId,
      "decision" to if (approve) "approve" else "deny",
      "durationMinutes" to if (approve) minutes.toLong() else null,
      "untilBlockEnds" to if (approve && untilBlockEnds) true else null,
    ).filterValues { it != null })
  }

  private fun commonArgs() = mapOf("guardianInstallationId" to installationId, "childProfileId" to childProfileId)
  private fun mutate(block: suspend () -> Unit) = viewModelScope.launch {
    runCatching { block() }.onFailure { mutable.value = mutable.value.copy(message = "Couldn’t complete that request") }
  }

  private fun parseMinute(value: String): Int? {
    val parts = value.split(':')
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
  }
}

@Composable
fun GuardianLiveFeaturesContent(childProfileId: String) {
  val application = LocalContext.current.applicationContext as Application
  val factory = remember(childProfileId) { viewModelFactory { initializer {
    GuardianLiveFeaturesViewModel(application, childProfileId)
  } } }
  val model: GuardianLiveFeaturesViewModel = viewModel(key = "live-$childProfileId", factory = factory)
  LifecycleStartEffect(model) {
    model.setVisible(true)
    onStopOrDispose { model.setVisible(false) }
  }
  val state by model.state.collectAsStateWithLifecycle()
  if (state.loading) { CircularProgressIndicator(); return }
  state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }

  Text("App blocking", style = MaterialTheme.typography.titleLarge)
  state.catalogSyncedAt?.let {
    Text("App list updated ${(System.currentTimeMillis() - it).coerceAtLeast(0) / 60_000} min ago")
  }
  if (state.apps.isEmpty()) Text("Waiting for the Child Device app list.")
  state.apps.forEach { item -> CereveilCard {
    var editingSchedule by rememberSaveable(item.packageName) { mutableStateOf(false) }
    var weekdays by rememberSaveable(item.packageName) { mutableStateOf("1,2,3,4,5,6,7") }
    var startTime by rememberSaveable(item.packageName) { mutableStateOf("22:00") }
    var endTime by rememberSaveable(item.packageName) { mutableStateOf("07:00") }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      Column(Modifier.weight(1f)) { Text(item.label); Text(item.packageName, style = MaterialTheme.typography.labelSmall) }
      CereveilSecondaryButton(text = if (item.blocked) "Unblock" else "Block", onClick = {
        model.setManualBlock(item.packageName, !item.blocked)
      })
    }
    if (item.policyPending) {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(strokeWidth = 2.dp)
        Text("Waiting for Child Device")
      }
    } else if (item.blocked || item.schedules.isNotEmpty()) Text("Applied on Child Device")
    item.schedules.forEach { schedule ->
      Text("Days ${schedule.weekdays.joinToString()} • ${minuteLabel(schedule.startMinute)}–${minuteLabel(schedule.endMinute)}")
      CereveilSecondaryButton(text = "Remove schedule", onClick = {
        model.removeSchedule(item.packageName, schedule.scheduleId)
      })
    }
    if (editingSchedule) {
      OutlinedTextField(
        value = weekdays,
        onValueChange = { weekdays = it },
        label = { Text("Weekdays (1=Mon … 7=Sun)") },
        modifier = Modifier.fillMaxWidth(),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(value = startTime, onValueChange = { startTime = it }, label = { Text("Start HH:mm") }, modifier = Modifier.weight(1f))
        OutlinedTextField(value = endTime, onValueChange = { endTime = it }, label = { Text("End HH:mm") }, modifier = Modifier.weight(1f))
      }
      CereveilPrimaryButton(text = "Add schedule", onClick = {
        model.addSchedule(item.packageName, weekdays, startTime, endTime)
        editingSchedule = false
      })
    } else if (item.schedules.size < 8) {
      CereveilSecondaryButton(text = "Add schedule", onClick = { editingSchedule = true })
    }
  } }

  if (state.accessRequests.isNotEmpty()) Text("Access requests", style = MaterialTheme.typography.titleLarge)
  state.accessRequests.forEach { request -> CereveilCard {
    Text("${request.packageName} requested access")
    val remainingMinutes = request.scheduledCoverageEnd?.let {
      ((it - System.currentTimeMillis()).coerceAtLeast(0) / 60_000).toInt()
    }
    listOf(15, 30, 45, 60).filter { remainingMinutes == null || it <= remainingMinutes }.forEach { minutes ->
      CereveilSecondaryButton(text = "$minutes min", onClick = {
        model.resolveAccess(request.requestId, true, minutes)
      })
    }
    if (request.blockKind == "scheduled") {
      CereveilSecondaryButton(text = "Until block ends", onClick = {
        model.resolveAccess(request.requestId, true, 60, untilBlockEnds = true)
      })
    }
    Text("Once allowed, access stays active until its shown expiry and cannot be revoked early.", style = MaterialTheme.typography.labelSmall)
    CereveilSecondaryButton(text = "Deny", onClick = { model.resolveAccess(request.requestId, false) })
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
  if (state.locationRefreshStatus == "failed") Text("The latest refresh failed; the previous location is preserved.")
  if (state.locationRefreshStatus == "expired") Text("The latest refresh timed out; the previous location is preserved.")

  Text("Today’s screen time", style = MaterialTheme.typography.titleLarge)
  if (state.screenError) Text("Couldn’t refresh Screen Time.", color = MaterialTheme.colorScheme.error)
  else if (state.screenLoading) Text("Fetching current Android usage…")
  else if (state.screenTime.isEmpty()) Text("No app usage reported for today yet.")
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

private fun minuteLabel(value: Int) = "%02d:%02d".format(value / 60, value % 60)
