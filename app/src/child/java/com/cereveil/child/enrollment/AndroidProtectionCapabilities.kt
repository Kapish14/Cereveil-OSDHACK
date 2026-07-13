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

class AndroidProtectionCapabilities(private val context: Context) {
  fun current(): ChildCapabilities {
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
    return ChildCapabilities(
      accessibilityService = accessibilityServices.contains(packageName, ignoreCase = true),
      usageAccess = usageMode == AppOpsManager.MODE_ALLOWED,
      location = foregroundLocation && backgroundLocation && locationManager.isLocationEnabled,
      microphone = granted(Manifest.permission.RECORD_AUDIO),
      notificationAccess =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || granted(Manifest.permission.POST_NOTIFICATIONS),
      batteryOptimizationExempt = powerManager.isIgnoringBatteryOptimizations(packageName),
      trustedDeviceTime = trustedDeviceTime,
    )
  }

  private fun granted(permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
