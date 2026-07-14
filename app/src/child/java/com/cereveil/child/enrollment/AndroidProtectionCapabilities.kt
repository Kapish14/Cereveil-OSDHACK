package com.cereveil.child.enrollment

import android.content.Context
import android.Manifest
import android.app.AppOpsManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.cereveil.child.protection.CereveilAccessibilityService

data class ProtectionSetupStatus(
  val accessibilityService: Boolean,
  val usageAccess: Boolean,
  val foregroundLocation: Boolean,
  val backgroundLocation: Boolean,
  val locationServices: Boolean,
  val microphone: Boolean,
  val notifications: Boolean,
  val batteryOptimizationExempt: Boolean,
  val trustedDeviceTime: Boolean,
) {
  val locationAndMicrophoneReady: Boolean
    get() = foregroundLocation && locationServices && microphone

  val completedSettings: Int
    get() = listOf(
      accessibilityService,
      usageAccess,
      locationAndMicrophoneReady,
      backgroundLocation,
      notifications,
      batteryOptimizationExempt,
      trustedDeviceTime,
    ).count { it }

  val complete: Boolean
    get() = completedSettings == 7

  fun missingSettings(): List<String> = buildList {
    if (!accessibilityService) add("Accessibility")
    if (!usageAccess) add("App usage")
    if (!foregroundLocation) add("Location permission")
    if (!locationServices) add("Location Services")
    if (!microphone) add("Microphone permission")
    if (!backgroundLocation) add("All-the-time location")
    if (!notifications) add("Notifications")
    if (!batteryOptimizationExempt) add("Battery access")
    if (!trustedDeviceTime) add("Automatic date and time")
  }
}

class AndroidProtectionCapabilities(private val context: Context) {
  fun currentSetupStatus(): ProtectionSetupStatus {
    val packageName = context.packageName
    val accessibilityServices =
      Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        .orEmpty()
    val powerManager = context.getSystemService(PowerManager::class.java)
    val appOps = context.getSystemService(AppOpsManager::class.java)
    val usageMode = appOps.unsafeCheckOpNoThrow(
      AppOpsManager.OPSTR_GET_USAGE_STATS,
      android.os.Process.myUid(),
      packageName,
    )
    val foregroundLocation = granted(Manifest.permission.ACCESS_FINE_LOCATION)
    val backgroundLocation = Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    val locationManager = context.getSystemService(LocationManager::class.java)
    val trustedDeviceTime = Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME, 0) == 1 &&
      Settings.Global.getInt(context.contentResolver, Settings.Global.AUTO_TIME_ZONE, 0) == 1
    return ProtectionSetupStatus(
      accessibilityService = accessibilityServices.contains(packageName, ignoreCase = true) &&
        CereveilAccessibilityService.isConnected(),
      usageAccess = usageMode == AppOpsManager.MODE_ALLOWED,
      foregroundLocation = foregroundLocation,
      backgroundLocation = backgroundLocation,
      locationServices = locationManager.isLocationEnabled,
      microphone = granted(Manifest.permission.RECORD_AUDIO),
      notifications =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.POST_NOTIFICATIONS),
      batteryOptimizationExempt = powerManager.isIgnoringBatteryOptimizations(packageName),
      trustedDeviceTime = trustedDeviceTime,
    )
  }

  fun current(): ChildCapabilities = currentSetupStatus().let { status ->
    ChildCapabilities(
      accessibilityService = status.accessibilityService,
      usageAccess = status.usageAccess,
      location = status.foregroundLocation && status.backgroundLocation && status.locationServices,
      microphone = status.microphone,
      notificationAccess = status.notifications,
      batteryOptimizationExempt = status.batteryOptimizationExempt,
      trustedDeviceTime = status.trustedDeviceTime,
    )
  }

  private fun granted(permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
