package com.cereveil.child.enrollment

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.cereveil.R
import com.cereveil.child.protection.ChildAccessGrantStore
import com.cereveil.child.protection.LocalAccessGrant
import com.cereveil.child.protection.ChildExemptApps
import com.cereveil.child.protection.CereveilAccessibilityService
import com.cereveil.child.remoteaudio.ChildRemoteAudioNotice
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

class AndroidChildFeatureCommandProcessor(
  context: Context,
  private val client: ChildDeviceIdentityClient,
) : ChildFeatureCommandProcessor {
  private val context = context.applicationContext
  private val grantStore = ChildAccessGrantStore(context)

  override suspend fun process(accessJwt: String, command: ChildDeviceCommand): ChildEnrollmentResult<Unit> {
    val performed = when (command.type) {
      "reconcile_access_grants" -> reconcileAccess(accessJwt, command.referenceId)
      "refresh_location" -> refreshLocation(accessJwt, command.referenceId)
      "refresh_screen_time" -> refreshScreenTime(accessJwt, command.referenceId)
      "request_remote_audio" -> presentRemoteAudio(command.referenceId, command.expiresAt)
      else -> client.rejectCommand(accessJwt, command.commandId, "unsupported_command")
    }
    if (performed is ChildEnrollmentResult.Failure || command.type !in SUPPORTED) return performed
    return client.acknowledgeCommand(accessJwt, command.commandId)
  }

  private fun presentRemoteAudio(requestId: String?, expiresAt: Long): ChildEnrollmentResult<Unit> {
    if (requestId == null) return ChildEnrollmentResult.Failure(ChildEnrollmentError.ValidationFailed)
    return if (ChildRemoteAudioNotice.present(context, requestId, expiresAt)) {
      ChildEnrollmentResult.Success(Unit)
    } else {
      ChildEnrollmentResult.Failure(ChildEnrollmentError.ValidationFailed)
    }
  }

  override suspend fun maintain(
    accessJwt: String,
    policy: ChildSupervisionPolicy?,
  ): ChildEnrollmentResult<Unit> {
    ChildLocationMovementRegistration.configure(context, policy?.locationSharing?.enabled == true)
    val catalog = launcherApps()
    val synced = client.syncAppCatalog(accessJwt, catalog)
    if (synced is ChildEnrollmentResult.Failure) return synced
    if (policy?.locationSharing?.enabled == true) {
      currentLocation(showDisclosure = false)?.let { return client.uploadLocation(accessJwt, it) }
    }
    return ChildEnrollmentResult.Success(Unit)
  }

  private suspend fun reconcileAccess(accessJwt: String, requestId: String?): ChildEnrollmentResult<Unit> {
    if (requestId != null) {
      when (val outcome = client.fetchAccessRequestOutcome(accessJwt, requestId)) {
        is ChildEnrollmentResult.Failure -> return outcome
        is ChildEnrollmentResult.Success -> if (outcome.value.first == "denied") {
          CereveilAccessibilityService.showDenied(outcome.value.second ?: System.currentTimeMillis() + 5 * 60 * 1000)
          return ChildEnrollmentResult.Success(Unit)
        }
      }
    }
    return when (val result = client.fetchAccessGrants(accessJwt)) {
      is ChildEnrollmentResult.Failure -> result
      is ChildEnrollmentResult.Success -> {
        grantStore.replace(result.value.map {
          LocalAccessGrant(it.packageName, Instant.ofEpochMilli(it.startsAt), Instant.ofEpochMilli(it.expiresAt))
        })
        CereveilAccessibilityService.requestReevaluation(resetRequestPresentation = true)
        ChildEnrollmentResult.Success(Unit)
      }
    }
  }

  private suspend fun refreshLocation(accessJwt: String, requestId: String?): ChildEnrollmentResult<Unit> {
    if (requestId == null) return ChildEnrollmentResult.Failure(ChildEnrollmentError.ValidationFailed)
    val requestedAfter = System.currentTimeMillis() - 1_000
    val measurement = currentLocation(showDisclosure = true, minimumCapturedAt = requestedAfter)
      ?: return client.failLocationRefresh(accessJwt, requestId, "capability_unavailable")
    if (measurement.capturedAt < requestedAfter) {
      return client.failLocationRefresh(accessJwt, requestId, "measurement_failed")
    }
    return client.uploadLocation(accessJwt, measurement, requestId)
  }

  private suspend fun refreshScreenTime(accessJwt: String, requestId: String?): ChildEnrollmentResult<Unit> {
    if (requestId == null) return ChildEnrollmentResult.Failure(ChildEnrollmentError.ValidationFailed)
    val now = System.currentTimeMillis()
    val zone = ZoneId.systemDefault()
    val dayStart = LocalDate.now(zone).atStartOfDay(zone).toInstant().toEpochMilli()
    val validUntil = LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
    val allowedPackages = launcherApps().mapTo(mutableSetOf()) { it.packageName }
    val usage = context.getSystemService(UsageStatsManager::class.java)
      .queryUsageStats(UsageStatsManager.INTERVAL_DAILY, dayStart, now).orEmpty()
      .filter { it.packageName in allowedPackages && it.totalTimeInForeground > 0 }
      .groupBy { it.packageName }
      .map { (packageName, rows) -> ChildScreenTimeRow(packageName, rows.maxOf { it.totalTimeInForeground }) }
      .sortedByDescending { it.totalTimeInForegroundMs }
      .take(500)
    return client.uploadScreenTime(accessJwt, requestId, now, dayStart, validUntil, usage)
  }

  @Suppress("DEPRECATION")
  private fun launcherApps(): List<ChildAppCatalogEntry> {
    val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return context.packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
      .asSequence()
      .map { ChildAppCatalogEntry(it.activityInfo.packageName, it.loadLabel(context.packageManager).toString().take(100)) }
      .filter { !ChildExemptApps.isExempt(context, it.packageName) && it.label.isNotBlank() }
      .distinctBy { it.packageName }
      .sortedBy { it.label.lowercase() }
      .take(500)
      .toList()
  }

  private suspend fun currentLocation(
    showDisclosure: Boolean,
    minimumCapturedAt: Long = 0,
  ): ChildLocationMeasurement? {
    val hasFine = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED
    val hasCoarse = ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
      PackageManager.PERMISSION_GRANTED
    if (!hasFine && !hasCoarse) return null
    if (showDisclosure) showLocationDisclosure()
    val manager = context.getSystemService(LocationManager::class.java)
    val providers = buildList {
      if (hasFine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
      if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
    }
    if (providers.isEmpty()) return null
    val location = withTimeoutOrNull(45_000) {
      suspendCancellableCoroutine<Location?> { continuation ->
        val listener = object : LocationListener {
          override fun onLocationChanged(location: Location) {
            if (location.time < minimumCapturedAt) return
            manager.removeUpdates(this)
            if (continuation.isActive) continuation.resume(location)
          }
          override fun onProviderDisabled(provider: String) = Unit
          override fun onProviderEnabled(provider: String) = Unit
          @Deprecated("Deprecated Android location callback")
          override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        var registered = false
        providers.forEach { provider ->
          if (runCatching {
            manager.requestLocationUpdates(provider, 0, 0f, listener, Looper.getMainLooper())
          }.isSuccess) registered = true
        }
        if (!registered && continuation.isActive) continuation.resume(null)
        continuation.invokeOnCancellation { manager.removeUpdates(listener) }
      }
    } ?: providers.asSequence()
      .mapNotNull { manager.getLastKnownLocation(it) }
      .filter { it.time >= minimumCapturedAt }
      .maxByOrNull(Location::getTime)
    return location?.let { ChildLocationMeasurement(it.latitude, it.longitude, it.accuracy.toDouble(), it.time) }
  }

  private fun showLocationDisclosure() {
    val manager = context.getSystemService(NotificationManager::class.java)
    val channelId = "location_refresh"
    manager.createNotificationChannel(NotificationChannel(
      channelId,
      "Location refreshes",
      NotificationManager.IMPORTANCE_DEFAULT,
    ))
    manager.notify(2401, NotificationCompat.Builder(context, channelId)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setContentTitle("Location requested")
      .setContentText("Your guardian requested your current location.")
      .setAutoCancel(true)
      .build())
  }

  private companion object {
    val SUPPORTED = setOf("reconcile_access_grants", "refresh_location", "refresh_screen_time", "request_remote_audio")
  }
}
