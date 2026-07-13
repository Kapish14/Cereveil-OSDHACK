package com.cereveil.child.protection

import android.content.Context
import com.cereveil.BuildConfig
import com.cereveil.child.enrollment.AndroidChildDeviceKeyStore
import com.cereveil.child.enrollment.ChildDeviceTokenProvider
import com.cereveil.child.enrollment.ChildEnrollmentResult
import com.cereveil.child.enrollment.ChildSafetyAlert
import com.cereveil.child.enrollment.HttpChildDeviceIdentityClient
import com.cereveil.child.enrollment.SharedPreferencesChildEnrollmentStateStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class ChildSafetyAlertReporter(context: Context) {
  private val appContext = context.applicationContext
  private val preferences = appContext.getSharedPreferences("pending_safety_alerts", Context.MODE_PRIVATE)
  private val stateStore = SharedPreferencesChildEnrollmentStateStore(appContext)
  private val client = HttpChildDeviceIdentityClient(BuildConfig.CONVEX_SITE_URL)

  suspend fun report(alert: ChildSafetyAlert) {
    val token = validAccessToken()
    if (token == null || client.uploadSafetyAlert(token, alert) !is ChildEnrollmentResult.Success) {
      enqueue(alert)
    }
  }

  suspend fun flush() {
    val token = validAccessToken() ?: return
    val now = System.currentTimeMillis()
    val remaining = mutableListOf<ChildSafetyAlert>()
    for (alert in load().filter { now - it.occurredAt <= RETENTION_MS }) {
      if (client.uploadSafetyAlert(token, alert) !is ChildEnrollmentResult.Success) remaining += alert
    }
    save(remaining)
  }

  fun clear() = preferences.edit().clear().apply()

  private suspend fun validAccessToken(): String? {
    val state = stateStore.load() ?: return null
    if (state.accessJwtExpiresAt > System.currentTimeMillis() + 30_000) return state.accessJwt
    return when (val refreshed = ChildDeviceTokenProvider(
      client, AndroidChildDeviceKeyStore(), stateStore,
    ).refresh()) {
      is ChildEnrollmentResult.Success -> refreshed.value
      is ChildEnrollmentResult.Failure -> null
    }
  }

  @Synchronized
  private fun enqueue(alert: ChildSafetyAlert) {
    val now = System.currentTimeMillis()
    val next = (load().filter { now - it.occurredAt <= RETENTION_MS } + alert)
      .distinctBy(ChildSafetyAlert::incidentId)
      .takeLast(MAX_PENDING)
    save(next)
  }

  private fun load(): List<ChildSafetyAlert> = runCatching {
    Json.parseToJsonElement(preferences.getString(KEY, "[]")!!).jsonArray.map { item ->
      val value = item.jsonObject
      ChildSafetyAlert(
        incidentId = value.string("incidentId"),
        type = value.string("type"),
        packageName = value.string("packageName"),
        confidenceBand = value.string("confidenceBand"),
        policyVersion = value.string("policyVersion").toInt(),
        occurredAt = value.string("occurredAt").toLong(),
      )
    }
  }.getOrDefault(emptyList())

  private fun save(alerts: List<ChildSafetyAlert>) {
    val encoded = buildJsonArray { alerts.forEach { alert -> add(buildJsonObject {
      put("incidentId", alert.incidentId)
      put("type", alert.type)
      put("packageName", alert.packageName)
      put("confidenceBand", alert.confidenceBand)
      put("policyVersion", alert.policyVersion)
      put("occurredAt", alert.occurredAt)
    }) } }.toString()
    preferences.edit().putString(KEY, encoded).commit()
  }

  private fun kotlinx.serialization.json.JsonObject.string(key: String) =
    this[key]?.jsonPrimitive?.content ?: error("Missing $key")

  private companion object {
    const val KEY = "alerts"
    const val MAX_PENDING = 200
    const val RETENTION_MS = 7 * 24 * 60 * 60 * 1000L
  }
}
