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
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
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
import com.cereveil.BuildConfig
import com.cereveil.R
import com.cereveil.guardian.arrayOrEmpty
import com.cereveil.guardian.boolean
import com.cereveil.guardian.double
import com.cereveil.guardian.long
import com.cereveil.guardian.objectOrNull
import com.cereveil.guardian.string
import com.cereveil.guardian.stringOrNull
import com.cereveil.guardian.auth.AndroidGuardianOperationBootstrapper
import com.cereveil.guardian.auth.SharedPreferencesGuardianInstallationIdProvider
import com.cereveil.guardian.remoteaudio.GuardianRemoteAudioCard
import com.cereveil.guardian.ui.GuardianCard as CereveilCard
import com.cereveil.guardian.ui.GuardianPrimaryButton as CereveilPrimaryButton
import com.cereveil.guardian.ui.GuardianSecondaryButton as CereveilSecondaryButton
import com.cereveil.guardian.ui.GuardianFeatureCard
import com.cereveil.guardian.ui.GuardianGreen
import com.cereveil.guardian.ui.GuardianOrange
import com.cereveil.guardian.ui.GuardianPrimary
import com.cereveil.guardian.ui.GuardianSectionHeader
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
data class GuardianSafetyAlert(
  val incidentId: String,
  val type: String,
  val appLabel: String,
  val confidenceBand: String,
  val occurredAt: Long,
)
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
  val screenRefreshPending: Boolean = false,
  val screenError: Boolean = false,
  val safetyAlerts: List<GuardianSafetyAlert> = emptyList(),
  val message: String? = null,
)

enum class GuardianFeatureSection { Home, Location, Activity, Settings }

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
    subscribeSafetyAlerts(args)
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
        delay(if (mutable.value.screenRefreshPending) 2_000 else 30_000)
      }
    }
  }

  private fun subscribeCatalog(args: Map<String, Any?>) = viewModelScope.launch {
    convex.subscribe<JsonElement>("modules/appCatalog/guardian:getLatestAppCatalog", args).collect { result ->
      result.onFailure { mutable.value = mutable.value.copy(message = "Couldn’t load the Child Device app list") }
      result.onSuccess { value ->
        val root = value.jsonObject
        val catalogApps = root.arrayOrEmpty("apps").map { it.jsonObject }.map {
          val packageName = it.string("packageName")
          GuardianCatalogApp(
            packageName, it.string("label"), packageName in manualBlocked,
            blockRules[packageName]?.schedules.orEmpty(), blockRules[packageName] != appliedBlockRules[packageName],
          )
        }
        val missing = blockRules.values.filter { rule -> catalogApps.none { it.packageName == rule.packageName } }
          .map { GuardianCatalogApp(it.packageName, "Not currently installed", it.manualBlocked, it.schedules, it != appliedBlockRules[it.packageName]) }
        mutable.value = mutable.value.copy(
          apps = catalogApps + missing,
          catalogSyncedAt = root.stringOrNull("syncedAt")?.toDoubleOrNull()?.toLong(),
          message = null,
        )
      }
    }
  }

  private fun subscribePolicy(args: Map<String, Any?>) = viewModelScope.launch {
    convex.subscribe<JsonElement>("modules/policies/guardian:getPolicyState", args).collect { result ->
      result.onSuccess { value ->
        val root = value.jsonObject
        val desired = requireNotNull(root.objectOrNull("desiredPolicy"))
        policyVersion = desired.long("version").toInt()
        val block = requireNotNull(desired.objectOrNull("appBlocking"))
        blockRules = parseRules(block.arrayOrEmpty("rules").map { it.jsonObject })
        val appliedBlock = root.objectOrNull("appliedPolicy")?.objectOrNull("appBlocking")
        appliedBlockRules = parseRules(appliedBlock?.arrayOrEmpty("rules")?.map { it.jsonObject }.orEmpty())
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

  private fun parseRules(rules: List<JsonObject>): Map<String, GuardianBlockRule> =
    rules.associate { raw ->
          val packageName = raw.string("packageName")
          val schedules = raw.arrayOrEmpty("schedules").map { it.jsonObject }.map { schedule -> GuardianBlockSchedule(
            schedule.string("scheduleId"),
            schedule.arrayOrEmpty("weekdays").mapNotNull { it.jsonPrimitive.content.toDoubleOrNull()?.toInt() },
            schedule.long("startMinute").toInt(),
            schedule.long("endMinute").toInt(),
          ) }
          packageName to GuardianBlockRule(packageName, raw.boolean("manualBlocked"), schedules)
        }

  private fun subscribeAccess(args: Map<String, Any?>) = viewModelScope.launch {
    convex.subscribe<JsonElement>("modules/access/guardian:listPendingAccessRequests", args).collect { result ->
      result.onSuccess { value -> mutable.value = mutable.value.copy(accessRequests = value.arrayOrEmpty().map { it.jsonObject }.map {
        GuardianAccessRequest(
          it.string("requestId"), it.string("packageName"), it.string("blockKind"),
          it.stringOrNull("scheduledCoverageEnd")?.toDoubleOrNull()?.toLong(),
        )
      }) }
    }
  }

  private fun subscribeLocation(args: Map<String, Any?>) = viewModelScope.launch {
    convex.subscribe<JsonElement>("modules/location/guardian:getLatestLocation", args).collect { result ->
      result.onSuccess { value ->
        val root = value.jsonObject
        val location = root.objectOrNull("location")
        val refresh = root.objectOrNull("refresh")
        mutable.value = mutable.value.copy(
          location = location?.let { GuardianLocation(
            it.double("latitude"), it.double("longitude"),
            it.double("accuracyMeters"), it.long("capturedAt"),
          ) },
          locationRefreshPending = refresh?.string("status") == "pending",
          locationRefreshStatus = refresh?.string("status"),
        )
      }
    }
  }

  private fun subscribeSafetyAlerts(args: Map<String, Any?>) = viewModelScope.launch {
    convex.subscribe<JsonElement>("modules/safetyAlerts/guardian:listSafetyAlerts", args).collect { result ->
      result.onSuccess { value -> mutable.value = mutable.value.copy(safetyAlerts = value.arrayOrEmpty().map { it.jsonObject }.map { row ->
        GuardianSafetyAlert(
          incidentId = row.string("incidentId"),
          type = row.string("type"),
          appLabel = row.string("appLabel"),
          confidenceBand = row.string("confidenceBand"),
          occurredAt = row.long("occurredAt"),
        )
      }) }
    }
  }

  private suspend fun loadScreenTime(args: Map<String, Any?>, force: Boolean = false) {
    val requestArgs = if (force) args + ("force" to true) else args
    runCatching { convex.mutation<JsonElement>("modules/screenTime/guardian:getOrRequestScreenTime", requestArgs) }
      .onSuccess { value ->
        val root = value.jsonObject
        val snapshot = root.objectOrNull("snapshot")
        val rows = snapshot?.arrayOrEmpty("apps")?.map { it.jsonObject }.orEmpty()
        mutable.value = mutable.value.copy(
          screenTime = rows.map { GuardianScreenTimeApp(
            it.string("packageName"), it.string("label"), it.long("totalTimeInForegroundMs"),
          ) },
          screenMeasuredAt = snapshot?.long("measuredAt"),
          screenLoading = false,
          screenRefreshPending = root.objectOrNull("refresh") != null,
          screenError = false,
        )
      }
      .onFailure { mutable.value = mutable.value.copy(
        screenLoading = false,
        screenRefreshPending = false,
        screenError = true,
      ) }
  }

  fun refreshScreenTime() {
    val id = installationId ?: return
    if (mutable.value.screenRefreshPending) return
    mutable.value = mutable.value.copy(screenRefreshPending = true, screenError = false)
    viewModelScope.launch {
      loadScreenTime(
        mapOf("guardianInstallationId" to id, "childProfileId" to childProfileId),
        force = true,
      )
    }
  }

  fun clearSafetyAlerts() = mutate {
    convex.mutation<JsonElement>("modules/safetyAlerts/guardian:clearSafetyAlerts", commonArgs())
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
        "weekdays" to schedule.weekdays.map(Int::toDouble),
        "startMinute" to schedule.startMinute.toDouble(),
        "endMinute" to schedule.endMinute.toDouble(),
      ) },
    ) }
    convex.mutation<JsonElement>("modules/policies/guardian:updateAppBlockingRules", commonArgs() + mapOf(
      "expectedCurrentVersion" to policyVersion.toDouble(), "operationId" to UUID.randomUUID().toString(), "rules" to rules,
    ))
  }

  fun requestLocation() = mutate {
    convex.mutation<JsonElement>("modules/location/guardian:requestLocationRefresh", commonArgs())
  }

  fun resolveAccess(requestId: String, approve: Boolean, minutes: Int = 15, untilBlockEnds: Boolean = false) = mutate {
    convex.mutation<JsonElement>("modules/access/guardian:resolveAccessRequest", mapOf(
      "guardianInstallationId" to installationId, "requestId" to requestId,
      "decision" to if (approve) "approve" else "deny",
      "durationMinutes" to if (approve) minutes.toDouble() else null,
      "untilBlockEnds" to if (approve && untilBlockEnds) true else null,
    ).filterValues { it != null })
  }

  private fun commonArgs() = mapOf("guardianInstallationId" to installationId, "childProfileId" to childProfileId)
  private fun mutate(block: suspend () -> Unit) = viewModelScope.launch {
    runCatching { block() }
      .onSuccess { mutable.value = mutable.value.copy(message = null) }
      .onFailure { mutable.value = mutable.value.copy(message = "Couldn’t complete that request") }
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
fun GuardianLiveFeaturesContent(
  childProfileId: String,
  section: GuardianFeatureSection = GuardianFeatureSection.Home,
  openSafetyAlerts: Boolean = false,
  onOpenSettings: () -> Unit = {},
) {
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
  val safetyAlertsRequester = remember { BringIntoViewRequester() }
  var showingApps by rememberSaveable(childProfileId) { mutableStateOf(false) }
  var appSearch by rememberSaveable(childProfileId) { mutableStateOf("") }
  if (state.loading) { CircularProgressIndicator(); return }
  state.message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
  LaunchedEffect(openSafetyAlerts) {
    if (openSafetyAlerts) safetyAlertsRequester.bringIntoView()
  }

  if (section == GuardianFeatureSection.Home) {
    GuardianSectionHeader("Today’s Overview")
    CereveilCard {
      Text(
        if (state.screenLoading) "Checking today’s activity…" else formatDuration(state.screenTime.sumOf(GuardianScreenTimeApp::totalMs)),
        style = MaterialTheme.typography.headlineMedium,
      )
      Text("Total screen time today", color = MaterialTheme.colorScheme.onSurfaceVariant)
      state.screenTime.take(3).forEach { app ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Text(app.label, modifier = Modifier.weight(1f))
          Text(formatDuration(app.totalMs), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
      }
    }
    if (BuildConfig.DEBUG) {
      GuardianSectionHeader("AI Safety Hub")
      GuardianFeatureCard(
        icon = Icons.Default.Security,
        iconTint = GuardianGreen,
        title = "Scam Text Detection",
        subtitle = "Warn about likely scam messages in selected apps",
        status = "Manage",
        onClick = onOpenSettings,
      )
      GuardianFeatureCard(
        icon = Icons.Default.VisibilityOff,
        iconTint = GuardianOrange,
        title = "NSFW Screen Detection",
        subtitle = "Blur likely explicit content in selected apps",
        status = "Manage",
        onClick = onOpenSettings,
      )
    }
    GuardianSectionHeader("Device Supervision")
    GuardianFeatureCard(
      icon = Icons.Default.Block,
      iconTint = GuardianPrimary,
      title = "App Blocking",
      subtitle = "Block apps now or on a recurring schedule",
      status = "Manage",
      onClick = onOpenSettings,
    )
    GuardianSectionHeader("Remote Operations")
    GuardianRemoteAudioCard(childProfileId)
    SafetyAlertFeed(
      state.safetyAlerts,
      model::clearSafetyAlerts,
      modifier = Modifier.bringIntoViewRequester(safetyAlertsRequester),
    )
  }

  if (section == GuardianFeatureSection.Settings) {
  Text("App blocking", style = MaterialTheme.typography.titleLarge)
  state.catalogSyncedAt?.let {
    Text("App list updated ${(System.currentTimeMillis() - it).coerceAtLeast(0) / 60_000} min ago")
  }
  if (state.apps.isEmpty()) Text("Waiting for the Child Device app list.")
  else {
    val configuredCount = state.apps.count { it.blocked || it.schedules.isNotEmpty() }
    Text("${state.apps.size} apps available • $configuredCount with blocking rules", style = MaterialTheme.typography.labelSmall)
    CereveilSecondaryButton(
      text = if (showingApps) "Done managing apps" else "Manage blocked apps",
      onClick = { showingApps = !showingApps },
    )
  }
  if (showingApps) OutlinedTextField(
    value = appSearch,
    onValueChange = { appSearch = it },
    label = { Text("Search apps") },
    modifier = Modifier.fillMaxWidth(),
    singleLine = true,
  )
  state.apps.filter {
    showingApps && (appSearch.isBlank() || it.label.contains(appSearch, true) || it.packageName.contains(appSearch, true))
  }.forEach { item -> CereveilCard {
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
  }

  if (section == GuardianFeatureSection.Home) {
    if (state.accessRequests.isNotEmpty()) Text("Access requests", style = MaterialTheme.typography.titleLarge)
    state.accessRequests.forEach { request ->
      CereveilCard {
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
      }
    }
  }

  if (section == GuardianFeatureSection.Location) {
  Text("Latest location", style = MaterialTheme.typography.titleLarge)
  state.location?.let { location ->
    val hasEmbeddedMap = stringResource(R.string.google_maps_key).isNotBlank()
    if (hasEmbeddedMap) GuardianLocationMap(location)
    else Text("Embedded map preview is not configured in this build. Open the current location in your maps app below.")
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
  }

  if (section == GuardianFeatureSection.Activity) {
  Text("Today’s screen time", style = MaterialTheme.typography.titleLarge)
  if (state.screenError) Text("Couldn’t refresh Screen Time.", color = MaterialTheme.colorScheme.error)
  else if (state.screenLoading) Text("Fetching current Android usage…")
  else if (state.screenTime.isEmpty()) Text("No app usage reported for today yet.")
  else Text(
    "Total today: ${formatDuration(state.screenTime.sumOf(GuardianScreenTimeApp::totalMs))}",
    style = MaterialTheme.typography.titleMedium,
  )
  state.screenTime.forEach { row -> CereveilCard {
    Text(row.label)
    Text(formatDuration(row.totalMs))
  } }
  CereveilSecondaryButton(
    text = if (state.screenRefreshPending) "Refreshing screen time…" else "Refresh screen time now",
    onClick = model::refreshScreenTime,
    enabled = !state.screenRefreshPending,
  )
  }
}

@Composable
private fun SafetyAlertFeed(
  alerts: List<GuardianSafetyAlert>,
  onClear: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
    Text("Safety alerts", style = MaterialTheme.typography.titleLarge)
    if (alerts.isEmpty()) Text("No safety alerts from the last week.")
    else CereveilSecondaryButton(text = "Clear alerts", onClick = onClear)
    alerts.forEach { alert -> CereveilCard {
      Text(if (alert.type == "scam_text") "Possible scam message" else "NSFW screen content")
      Text(alert.appLabel)
      Text("${alert.confidenceBand.replaceFirstChar(Char::uppercase)} confidence")
      val ageMinutes = (System.currentTimeMillis() - alert.occurredAt).coerceAtLeast(0) / 60_000
      Text(if (ageMinutes < 1) "Just now" else "$ageMinutes min ago", style = MaterialTheme.typography.labelSmall)
    } }
  }
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
