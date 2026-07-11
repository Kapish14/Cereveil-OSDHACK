package com.cereveil.guardian.auth

import android.content.Context
import androidx.core.content.edit

class SharedPreferencesGuardianLocalStateRepository(context: Context) : GuardianLocalStateRepository {
  private val preferences =
    context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

  override suspend fun getOrCreateInstallationId(generate: () -> String): String {
    val existing = preferences.getString(KEY_INSTALLATION_ID, null)
    if (!existing.isNullOrBlank()) return existing

    val generated = generate()
    preferences.edit { putString(KEY_INSTALLATION_ID, generated) }
    return generated
  }

  override suspend fun getBootstrapState(): StoredGuardianBootstrapState? {
    val authSessionKey = preferences.getString(KEY_BOOTSTRAP_AUTH_SESSION_KEY, null) ?: return null
    val guardianAccountId = preferences.getString(KEY_GUARDIAN_ACCOUNT_ID, null) ?: return null
    val householdId = preferences.getString(KEY_HOUSEHOLD_ID, null) ?: return null
    val guardianDeviceId = preferences.getString(KEY_GUARDIAN_DEVICE_ID, null) ?: return null
    val guardianDeviceStatus = preferences.getString(KEY_GUARDIAN_DEVICE_STATUS, null) ?: return null

    return StoredGuardianBootstrapState(
      state =
        GuardianBootstrapState(
          guardianAccountId = guardianAccountId,
          householdId = householdId,
          guardianDeviceId = guardianDeviceId,
          guardianDeviceStatus = guardianDeviceStatus,
          hasChildProfiles = preferences.getBoolean(KEY_HAS_CHILD_PROFILES, false),
          serverNow = preferences.getLong(KEY_SERVER_NOW, 0L),
        ),
      authSessionKey = authSessionKey,
    )
  }

  override suspend fun saveBootstrapState(state: GuardianBootstrapState, authSessionKey: String) {
    preferences.edit {
      putString(KEY_BOOTSTRAP_AUTH_SESSION_KEY, authSessionKey)
      putString(KEY_GUARDIAN_ACCOUNT_ID, state.guardianAccountId)
      putString(KEY_HOUSEHOLD_ID, state.householdId)
      putString(KEY_GUARDIAN_DEVICE_ID, state.guardianDeviceId)
      putString(KEY_GUARDIAN_DEVICE_STATUS, state.guardianDeviceStatus)
      putBoolean(KEY_HAS_CHILD_PROFILES, state.hasChildProfiles)
      putLong(KEY_SERVER_NOW, state.serverNow)
    }
  }

  override suspend fun clearBootstrapState() {
    preferences.edit {
      remove(KEY_BOOTSTRAP_AUTH_SESSION_KEY)
      remove(KEY_GUARDIAN_ACCOUNT_ID)
      remove(KEY_HOUSEHOLD_ID)
      remove(KEY_GUARDIAN_DEVICE_ID)
      remove(KEY_GUARDIAN_DEVICE_STATUS)
      remove(KEY_HAS_CHILD_PROFILES)
      remove(KEY_SERVER_NOW)
    }
  }

  private companion object {
    const val PREFERENCES_NAME = "guardian_auth_bootstrap"
    const val KEY_INSTALLATION_ID = "guardianInstallationId"
    const val KEY_BOOTSTRAP_AUTH_SESSION_KEY = "bootstrapAuthSessionKey"
    const val KEY_GUARDIAN_ACCOUNT_ID = "guardianAccountId"
    const val KEY_HOUSEHOLD_ID = "householdId"
    const val KEY_GUARDIAN_DEVICE_ID = "guardianDeviceId"
    const val KEY_GUARDIAN_DEVICE_STATUS = "guardianDeviceStatus"
    const val KEY_HAS_CHILD_PROFILES = "hasChildProfiles"
    const val KEY_SERVER_NOW = "serverNow"
  }
}

class SharedPreferencesGuardianInstallationIdProvider(context: Context) : GuardianInstallationIdProvider {
  private val preferences =
    context.applicationContext.getSharedPreferences("guardian_auth_bootstrap", Context.MODE_PRIVATE)

  override suspend fun getInstallationId(): String? =
    preferences.getString("guardianInstallationId", null)?.takeIf(String::isNotBlank)
}
