package com.cereveil.child.enrollment

import android.content.Context
import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.app.ActivityCompat
class AndroidPolicyControlledRuntime(context: Context) : PolicyControlledRuntime {
  private val context = context.applicationContext
  private val preferences = context.getSharedPreferences("child_policy_runtime", Context.MODE_PRIVATE)

  override fun start(policy: ChildSupervisionPolicy): PolicyActivationResult {
    val saved = preferences.edit()
        .putInt("active_policy_version", policy.version)
        .putString("policy", policy.rawJson)
        .putBoolean("app_blocking_enabled", policy.appBlocking.enabled)
        .putBoolean("safe_browsing_enabled", policy.safeBrowsing.enabled)
        .putBoolean("safe_search_enabled", policy.safeBrowsing.safeSearchEnabled)
        .putBoolean("active_screen_safety_enabled", policy.activeScreenSafety.enabled)
        .putBoolean("location_sharing_enabled", policy.locationSharing.enabled)
        .putBoolean("screen_time_enabled", policy.screenTime.enabled)
        .commit()
    if (!saved) return PolicyActivationResult.RetryableFailure
    ChildLocationMovementRegistration.configure(context, policy.locationSharing.enabled)
    return PolicyActivationResult.Success
  }
}

object ChildLocationMovementRegistration {
  fun configure(context: Context, enabled: Boolean) {
    val intent = Intent(context, ChildLocationMovementReceiver::class.java)
    val pending = PendingIntent.getBroadcast(
      context, 77, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val manager = context.getSystemService(LocationManager::class.java)
    manager.removeUpdates(pending)
    if (!enabled || ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
    val provider = when {
      manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
      manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
      else -> return
    }
    runCatching { manager.requestLocationUpdates(provider, 5 * 60 * 1000L, 250f, pending) }
  }
}
