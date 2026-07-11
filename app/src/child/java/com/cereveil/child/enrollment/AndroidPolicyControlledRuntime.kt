package com.cereveil.child.enrollment

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class AndroidPolicyControlledRuntime(context: Context) : PolicyControlledRuntime {
  private val preferences = context.getSharedPreferences("child_policy_runtime", Context.MODE_PRIVATE)

  override fun start(policy: ChildSupervisionPolicy) {
    val value = Json.parseToJsonElement(policy.rawJson).jsonObject
    val appBlocking = value["appBlocking"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean
    val safeBrowsing = value["safeBrowsing"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean
    val activeScreenSafety =
      value["activeScreenSafety"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean
    val screenTimeSummaries = value["screenTimeSummariesEnabled"]!!.jsonPrimitive.boolean
    check(
      preferences.edit()
        .putInt("active_policy_version", policy.version)
        .putBoolean("app_blocking_enabled", appBlocking)
        .putBoolean("safe_browsing_enabled", safeBrowsing)
        .putBoolean("active_screen_safety_enabled", activeScreenSafety)
        .putBoolean("screen_time_summaries_enabled", screenTimeSummaries)
        .commit()
    ) {
      "Policy-controlled runtime state could not be activated."
    }
  }
}
