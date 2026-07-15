package com.cereveil.guardian.enrollment

data class GuardianEnrollmentCode(
  val enrollmentCodeId: String,
  val qrPayload: String,
  val expiresAt: Long,
  val serverNow: Long,
)

enum class GuardianPolicyStatus { NotApplicable, Pending, Applied }

enum class GuardianProtectionHealthStatus { NotApplicable, Pending, FullyProtected, ProtectionDegraded }
enum class GuardianConnectivityStatus { NotApplicable, Pending, Online, Offline }

data class GuardianEnrollmentSummary(
  val enrollmentActive: Boolean,
  val policyStatus: GuardianPolicyStatus,
  val protectionHealthStatus: GuardianProtectionHealthStatus,
  val connectivityStatus: GuardianConnectivityStatus = GuardianConnectivityStatus.Pending,
  val serverNow: Long,
)

enum class GuardianEnrollmentError { BootstrapRequired, Unauthenticated, AccountUnavailable, AlreadyEnrolled, InvalidTarget, Retryable }

sealed interface GuardianEnrollmentResult<out T> {
  data class Success<T>(val value: T) : GuardianEnrollmentResult<T>
  data class Failure(val error: GuardianEnrollmentError) : GuardianEnrollmentResult<Nothing>
}

interface GuardianEnrollmentClient {
  suspend fun createCode(childProfileId: String): GuardianEnrollmentResult<GuardianEnrollmentCode>
  suspend fun cancelCode(enrollmentCodeId: String): GuardianEnrollmentResult<Unit>
  suspend fun replaceChildDevice(childProfileId: String): GuardianEnrollmentResult<Unit>
  suspend fun endSupervision(childProfileId: String): GuardianEnrollmentResult<Unit>
  fun observeSummary(childProfileId: String): kotlinx.coroutines.flow.Flow<GuardianEnrollmentResult<GuardianEnrollmentSummary>>
}

sealed interface GuardianEnrollmentUiState {
  data object Loading : GuardianEnrollmentUiState
  data class ShowingCode(val code: GuardianEnrollmentCode) : GuardianEnrollmentUiState
  data class Enrolled(
    val policyStatus: GuardianPolicyStatus,
    val protectionHealthStatus: GuardianProtectionHealthStatus,
    val connectivityStatus: GuardianConnectivityStatus = GuardianConnectivityStatus.Pending,
  ) : GuardianEnrollmentUiState
  data object Cancelled : GuardianEnrollmentUiState
  data class Failure(val error: GuardianEnrollmentError) : GuardianEnrollmentUiState
}

sealed interface GuardianDeviceReplacementUiState {
  data object Confirming : GuardianDeviceReplacementUiState
  data object Replacing : GuardianDeviceReplacementUiState
  data object Replaced : GuardianDeviceReplacementUiState
  data class Failure(val error: GuardianEnrollmentError) : GuardianDeviceReplacementUiState
}

sealed interface GuardianEndSupervisionUiState {
  data object Confirming : GuardianEndSupervisionUiState
  data object Ending : GuardianEndSupervisionUiState
  data object Ended : GuardianEndSupervisionUiState
  data class Failure(val error: GuardianEnrollmentError) : GuardianEndSupervisionUiState
}
