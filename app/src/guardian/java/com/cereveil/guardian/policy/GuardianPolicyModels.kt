package com.cereveil.guardian.policy

data class GuardianPolicy(
  val version: Int,
  val schemaVersion: Int,
  val appBlockingEnabled: Boolean,
  val safeBrowsingEnabled: Boolean,
  val safeSearchEnabled: Boolean,
  val activeScreenSafetyEnabled: Boolean,
  val screenTimeSummariesEnabled: Boolean,
)

enum class PolicyApplicationStatus { Pending, Applied, Failed }
enum class PolicyFailureReason { UnsupportedSchema, InvalidPolicy, ActivationFailed }
enum class PolicyFeature { AppBlocking, SafeBrowsing, ActiveScreenSafety, ScreenTimeSummaries }

data class GuardianPolicyState(
  val desired: GuardianPolicy,
  val applied: GuardianPolicy?,
  val status: PolicyApplicationStatus,
  val failureReason: PolicyFailureReason?,
)

enum class GuardianPolicyError { Unauthenticated, Conflict, Unsupported, Invalid, Retryable }

sealed interface GuardianPolicyResult<out T> {
  data class Success<T>(val value: T) : GuardianPolicyResult<T>
  data class Failure(val error: GuardianPolicyError) : GuardianPolicyResult<Nothing>
}

interface GuardianPolicyClient {
  fun observe(childProfileId: String): kotlinx.coroutines.flow.Flow<GuardianPolicyResult<GuardianPolicyState>>
  suspend fun update(
    childProfileId: String,
    expectedVersion: Int,
    operationId: String,
    feature: PolicyFeature,
    enabled: Boolean,
    safeSearchEnabled: Boolean = false,
  ): GuardianPolicyResult<GuardianPolicyState>
}

sealed interface GuardianPolicyUiState {
  data object Loading : GuardianPolicyUiState
  data class Ready(
    val policy: GuardianPolicyState,
    val savingFeature: PolicyFeature? = null,
    val updateError: GuardianPolicyError? = null,
  ) : GuardianPolicyUiState
  data class Error(val error: GuardianPolicyError) : GuardianPolicyUiState
}
