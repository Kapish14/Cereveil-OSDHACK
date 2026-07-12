package com.cereveil.child.enrollment

import android.content.Context
class AndroidPolicyControlledRuntime(context: Context) : PolicyControlledRuntime {
  private val preferences = context.getSharedPreferences("child_policy_runtime", Context.MODE_PRIVATE)

  override fun start(policy: ChildSupervisionPolicy): PolicyActivationResult =
    if (preferences.edit()
        .putInt("active_policy_version", policy.version)
        .putString("policy", policy.rawJson)
        .putBoolean("app_blocking_enabled", policy.appBlocking.enabled)
        .putBoolean("safe_browsing_enabled", policy.safeBrowsing.enabled)
        .putBoolean("safe_search_enabled", policy.safeBrowsing.safeSearchEnabled)
        .putBoolean("active_screen_safety_enabled", policy.activeScreenSafety.enabled)
        .putBoolean("screen_time_summaries_enabled", policy.screenTimeSummariesEnabled)
        .commit()
    ) PolicyActivationResult.Success else PolicyActivationResult.RetryableFailure
}
