package com.cereveil.guardian.childprofile

data class CreateChildProfileRequest(
  val displayName: String,
  val birthMonth: Int,
  val birthYear: Int,
)

data class ChildProfileSummary(
  val childProfileId: String,
  val displayName: String,
  val birthMonth: Int,
  val birthYear: Int,
  val status: ChildProfileStatus,
  val enrollmentStatus: ChildProfileEnrollmentStatus,
  val currentPolicyVersionId: String,
  val currentPolicyVersion: Int,
  val connectivityStatus: GuardianConnectivityStatus = GuardianConnectivityStatus.NotApplicable,
  val protectionStatus: GuardianProtectionStatus = GuardianProtectionStatus.NotApplicable,
  val unavailableCapabilities: List<String> = emptyList(),
  val lastHeartbeatAt: Long? = null,
  val serverNow: Long? = null,
)

enum class GuardianConnectivityStatus { NotApplicable, Pending, Online, Offline }
enum class GuardianProtectionStatus { NotApplicable, Pending, FullyProtected, ProtectionDegraded }

enum class ChildProfileStatus {
  Active,
}

enum class ChildProfileEnrollmentStatus {
  Unenrolled,
  Active,
}

enum class GuardianChildProfileError {
  BootstrapRequired,
  Unauthenticated,
  AccountUnavailable,
  ValidationFailed,
  AgeOutOfRange,
  Retryable,
}

sealed interface GuardianChildProfileResult {
  data class Success(val profile: ChildProfileSummary) : GuardianChildProfileResult

  data class Failure(val error: GuardianChildProfileError) : GuardianChildProfileResult
}

sealed interface GuardianChildProfileListResult {
  data class Success(val profiles: List<ChildProfileSummary>) : GuardianChildProfileListResult

  data class Failure(val error: GuardianChildProfileError) : GuardianChildProfileListResult
}

sealed interface GuardianChildProfileSetupState {
  data object Loading : GuardianChildProfileSetupState

  data object FirstChildForm : GuardianChildProfileSetupState

  data class ProfileSetup(val profiles: List<ChildProfileSummary>) : GuardianChildProfileSetupState

  data class FormError(val error: GuardianChildProfileError) : GuardianChildProfileSetupState

  data class LoadError(val error: GuardianChildProfileError) : GuardianChildProfileSetupState
}

interface GuardianChildProfileClient {
  suspend fun createChildProfile(request: CreateChildProfileRequest): GuardianChildProfileResult

  suspend fun listChildProfiles(): GuardianChildProfileListResult
  fun observeChildProfiles(): kotlinx.coroutines.flow.Flow<GuardianChildProfileListResult> =
    kotlinx.coroutines.flow.flow { emit(listChildProfiles()) }
}
