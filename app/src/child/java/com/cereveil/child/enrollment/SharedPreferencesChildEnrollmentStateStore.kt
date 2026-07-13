package com.cereveil.child.enrollment

import android.content.Context
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class SharedPreferencesChildEnrollmentStateStore(context: Context) : ChildEnrollmentStateStore {
  private val preferences = context.getSharedPreferences("child_enrollment", Context.MODE_PRIVATE)
  private val policyPreferences = context.getSharedPreferences("child_policy_runtime", Context.MODE_PRIVATE)

  override fun load(): LocalChildEnrollmentState? = preferences.getString("state", null)?.let { raw ->
    runCatching {
      val value = Json.parseToJsonElement(raw).jsonObject
      LocalChildEnrollmentState(
        childDeviceId = value.string("childDeviceId"),
        activeEnrollmentId = value.string("activeEnrollmentId"),
        credentialId = value.string("credentialId"),
        childDisplayName = value.string("childDisplayName"),
        desiredPolicyVersion = value.string("desiredPolicyVersion").toInt(),
        accessJwt = value.string("accessJwt"),
        accessJwtExpiresAt = value.string("accessJwtExpiresAt").toLong(),
        enrolledAt = value.string("enrolledAt").toLong(),
        environment = value.string("environment"),
        keyAlias = value.string("keyAlias"),
        roleLockActive = true,
        acknowledgedPolicyVersion = value["acknowledgedPolicyVersion"]?.jsonPrimitive?.content?.toIntOrNull(),
      )
    }.getOrNull()
  }

  override fun save(state: LocalChildEnrollmentState) {
    preferences.edit().putString("state", buildJsonObject {
      put("childDeviceId", state.childDeviceId)
      put("activeEnrollmentId", state.activeEnrollmentId)
      put("credentialId", state.credentialId)
      put("childDisplayName", state.childDisplayName)
      put("desiredPolicyVersion", state.desiredPolicyVersion)
      put("accessJwt", state.accessJwt)
      put("accessJwtExpiresAt", state.accessJwtExpiresAt)
      put("enrolledAt", state.enrolledAt)
      put("environment", state.environment)
      put("keyAlias", state.keyAlias)
      put("roleLockActive", true)
      state.acknowledgedPolicyVersion?.let { put("acknowledgedPolicyVersion", it) }
    }.toString()).commit().also { check(it) { "Child enrollment state could not be persisted." } }
  }

  override fun updateAccessToken(accessJwt: String, expiresAt: Long) {
    load()?.let { save(it.copy(accessJwt = accessJwt, accessJwtExpiresAt = expiresAt)) }
  }

  override fun markPolicyAcknowledged(version: Int) {
    load()?.let { save(it.copy(acknowledgedPolicyVersion = version)) }
  }

  override fun savePolicy(policy: ChildSupervisionPolicy) {
    check(policyPreferences.edit().putString("policy", policy.rawJson).commit()) {
      "Child Supervision Policy could not be persisted."
    }
  }

  override fun loadPolicy(): ChildSupervisionPolicy? = policyPreferences.getString("policy", null)?.let { raw ->
    runCatching {
      ChildSupervisionPolicy.parse(raw)
    }.getOrNull()
  }

  override fun clear() {
    preferences.edit().clear().commit()
    policyPreferences.edit().clear().commit()
  }
}

private fun kotlinx.serialization.json.JsonObject.string(key: String) =
  this[key]?.jsonPrimitive?.content ?: error("Missing $key")
