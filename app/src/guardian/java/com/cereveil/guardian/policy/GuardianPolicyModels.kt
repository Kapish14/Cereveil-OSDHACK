package com.cereveil.guardian.policy

data class GuardianPolicy(
  val version: Int,
  val schemaVersion: Int,
  val appBlockingEnabled: Boolean,
  val safeBrowsingEnabled: Boolean,
  val safeSearchEnabled: Boolean,
  val scamTextSafety: GuardianSafetyDetectorPolicy,
  val nsfwScreenSafety: GuardianSafetyDetectorPolicy,
  val locationSharingEnabled: Boolean,
  val screenTimeEnabled: Boolean,
)

enum class GuardianSafetySensitivity(val wireValue: String) {
  Lower("lower"), Standard("standard"), Higher("higher")
}
data class GuardianSafetyDetectorPolicy(
  val enabled: Boolean,
  val monitoredPackageNames: Set<String>,
  val sensitivity: GuardianSafetySensitivity,
)
data class GuardianSelectableApp(val packageName: String, val label: String)
enum class GuardianSafetyDetector { ScamText, NsfwScreen }

enum class PolicyApplicationStatus { Pending, Applied, Failed }
enum class PolicyFailureReason { UnsupportedSchema, InvalidPolicy, ActivationFailed }
enum class PolicyFeature { AppBlocking, SafeBrowsing, ScamTextSafety, NsfwScreenSafety, LocationSharing, ScreenTime }

data class GuardianPolicyState(
  val desired: GuardianPolicy,
  val applied: GuardianPolicy?,
  val status: PolicyApplicationStatus,
  val failureReason: PolicyFailureReason?,
  val supportsNsfwScreenDetection: Boolean = false,
)

enum class GuardianPolicyError { Unauthenticated, Conflict, Unsupported, Invalid, Retryable }

sealed interface GuardianPolicyResult<out T> {
  data class Success<T>(val value: T) : GuardianPolicyResult<T>
  data class Failure(val error: GuardianPolicyError) : GuardianPolicyResult<Nothing>
}

interface GuardianPolicyClient {
  fun observe(childProfileId: String): kotlinx.coroutines.flow.Flow<GuardianPolicyResult<GuardianPolicyState>>
  fun observeCatalog(childProfileId: String): kotlinx.coroutines.flow.Flow<GuardianPolicyResult<List<GuardianSelectableApp>>>
  suspend fun update(
    childProfileId: String,
    expectedVersion: Int,
    operationId: String,
    feature: PolicyFeature,
    enabled: Boolean,
    safeSearchEnabled: Boolean = false,
  ): GuardianPolicyResult<GuardianPolicyState>
  suspend fun updateSafety(
    childProfileId: String,
    expectedVersion: Int,
    operationId: String,
    scamText: GuardianSafetyDetectorPolicy,
    nsfwScreen: GuardianSafetyDetectorPolicy,
  ): GuardianPolicyResult<GuardianPolicyState>
}

sealed interface GuardianPolicyUiState {
  data object Loading : GuardianPolicyUiState
  data class Ready(
    val policy: GuardianPolicyState,
    val catalogApps: List<GuardianSelectableApp> = emptyList(),
    val savingFeature: PolicyFeature? = null,
    val updateError: GuardianPolicyError? = null,
  ) : GuardianPolicyUiState
  data class Error(val error: GuardianPolicyError) : GuardianPolicyUiState
}
