package com.cereveil.guardian.auth

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

data class GuardianBootstrapRequest(
  val guardianInstallationId: String,
  val deviceLabel: String?,
  val appBuild: String,
  val timezone: String?,
)

data class GuardianBootstrapState(
  val guardianAccountId: String,
  val householdId: String,
  val guardianDeviceId: String,
  val guardianDeviceStatus: String,
  val hasChildProfiles: Boolean,
  val serverNow: Long,
)

sealed interface GuardianAuthState {
  data object Unauthenticated : GuardianAuthState

  data class Authenticated(val authSessionKey: String) : GuardianAuthState
}

enum class GuardianStartupRoute {
  Auth,
  Loading,
  Setup,
  Dashboard,
  DeviceRevoked,
  DeviceLimitReached,
  RetryableError,
}

enum class GuardianBootstrapError {
  Unauthenticated,
  DeviceRevoked,
  DeviceLimitReached,
  Retryable,
}

sealed interface GuardianBootstrapResult {
  data class Success(val state: GuardianBootstrapState) : GuardianBootstrapResult

  data class Failure(val error: GuardianBootstrapError) : GuardianBootstrapResult
}

interface GuardianAuthSessionProvider {
  suspend fun currentState(): GuardianAuthState

  fun states(): Flow<GuardianAuthState> = flow { emit(currentState()) }
}

interface GuardianInstallationMetadataProvider {
  fun buildRequest(guardianInstallationId: String): GuardianBootstrapRequest
}

interface GuardianInstallationIdProvider {
  suspend fun getInstallationId(): String?
}

interface GuardianOperationBootstrapper {
  suspend fun ensureBootstrapped(): Boolean
}

interface GuardianAuthClient {
  suspend fun bootstrapGuardian(request: GuardianBootstrapRequest): GuardianBootstrapResult
}

interface GuardianLocalStateRepository {
  suspend fun getOrCreateInstallationId(generate: () -> String): String

  suspend fun getBootstrapState(): StoredGuardianBootstrapState?

  suspend fun saveBootstrapState(state: GuardianBootstrapState, authSessionKey: String)

  suspend fun clearBootstrapState()
}

data class StoredGuardianBootstrapState(
  val state: GuardianBootstrapState,
  val authSessionKey: String,
)
