package com.cereveil.child.enrollment

import android.content.Context
import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.app.ActivityCompat
import com.cereveil.child.protection.ChildSafetyModels
import com.cereveil.child.protection.CereveilAccessibilityService
import com.cereveil.BuildConfig
class AndroidPolicyControlledRuntime(context: Context) : PolicyControlledRuntime {
  private val context = context.applicationContext
  private val preferences = context.getSharedPreferences("child_policy_runtime", Context.MODE_PRIVATE)

  override fun start(policy: ChildSupervisionPolicy): PolicyActivationResult {
    if (!BuildConfig.DEBUG && (policy.activeScreenSafety.scamText.enabled || policy.activeScreenSafety.nsfwScreen.enabled)) {
      return PolicyActivationResult.PermanentFailure(PolicyPermanentFailureReason.UnableToApply)
    }
    val previousPolicy = preferences.getString("policy", null)
      ?.let { runCatching { ChildSupervisionPolicy.parse(it) }.getOrNull() }
    if (!ChildSafetyModels.configure(context, policy.activeScreenSafety)) {
      return PolicyActivationResult.PermanentFailure(PolicyPermanentFailureReason.UnableToApply)
    }
    val saved = preferences.edit()
        .putInt("active_policy_version", policy.version)
        .putString("policy", policy.rawJson)
        .putBoolean("app_blocking_enabled", policy.appBlocking.enabled)
        .putBoolean("safe_browsing_enabled", policy.safeBrowsing.enabled)
        .putBoolean("safe_search_enabled", policy.safeBrowsing.safeSearchEnabled)
        .putBoolean("scam_text_enabled", policy.activeScreenSafety.scamText.enabled)
        .putStringSet("scam_text_packages", policy.activeScreenSafety.scamText.monitoredPackageNames)
        .putString("scam_text_sensitivity", policy.activeScreenSafety.scamText.sensitivity.wireValue)
        .putBoolean("nsfw_screen_enabled", policy.activeScreenSafety.nsfwScreen.enabled)
        .putStringSet("nsfw_screen_packages", policy.activeScreenSafety.nsfwScreen.monitoredPackageNames)
        .putString("nsfw_screen_sensitivity", policy.activeScreenSafety.nsfwScreen.sensitivity.wireValue)
        .putBoolean("location_sharing_enabled", policy.locationSharing.enabled)
        .putBoolean("screen_time_enabled", policy.screenTime.enabled)
        .commit()
    if (!saved) {
      if (previousPolicy == null) ChildSafetyModels.release()
      else ChildSafetyModels.configure(context, previousPolicy.activeScreenSafety)
      return PolicyActivationResult.RetryableFailure
    }
    CereveilAccessibilityService.requestReevaluation(resetRequestPresentation = false)
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
