package com.cereveil.child.enrollment

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

data class EnrollmentQrPayload(val code: String) {
  companion object {
    fun parse(raw: String): EnrollmentQrPayload? = runCatching {
      val value = Json.parseToJsonElement(raw).jsonObject
      if (value["type"]?.jsonPrimitive?.content != "cereveil.child-enrollment") return null
      if (value["version"]?.jsonPrimitive?.content?.toIntOrNull() != 1) return null
      val code = value["code"]?.jsonPrimitive?.content ?: return null
      if (!code.matches(Regex("^[A-Za-z0-9_-]{22}$"))) return null
      EnrollmentQrPayload(code)
    }.getOrNull()
  }
}

data class ChildEnrollmentPreview(
  val childDisplayName: String,
  val codeExpiresAt: Long,
  val serverNow: Long,
)

data class ChildEnrollmentCompletion(
  val childDeviceId: String,
  val activeEnrollmentId: String,
  val credentialId: String,
  val childDisplayName: String,
  val desiredPolicyVersion: Int,
  val accessJwt: String,
  val accessJwtExpiresAt: Long,
  val enrolledAt: Long,
  val environment: String,
  val serverNow: Long,
)

data class LocalChildEnrollmentState(
  val childDeviceId: String,
  val activeEnrollmentId: String,
  val credentialId: String,
  val childDisplayName: String,
  val desiredPolicyVersion: Int,
  val accessJwt: String,
  val accessJwtExpiresAt: Long,
  val enrolledAt: Long,
  val environment: String,
  val keyAlias: String,
  val roleLockActive: Boolean = true,
  val acknowledgedPolicyVersion: Int? = null,
)

data class ChildSupervisionPolicy(
  val version: Int,
  val rawJson: String,
)

data class ChildDeviceCommand(
  val commandId: String,
  val type: String,
  val policyVersion: Int,
  val expiresAt: Long,
)

data class ChildCapabilities(
  val accessibilityService: Boolean,
  val usageAccess: Boolean,
  val location: Boolean,
  val microphone: Boolean,
  val notificationAccess: Boolean,
  val batteryOptimizationExempt: Boolean,
) {
  val protectionSetupComplete: Boolean
    get() = accessibilityService && usageAccess && location && microphone && notificationAccess &&
      batteryOptimizationExempt
}

enum class ChildEnrollmentError {
  InvalidCode,
  AlreadyEnrolled,
  EnrollmentFailed,
  ValidationFailed,
  Unauthorized,
  PolicyVersionMismatch,
  InternalError,
  NetworkUnavailable,
}

sealed interface ChildEnrollmentResult<out T> {
  data class Success<T>(val value: T) : ChildEnrollmentResult<T>
  data class Failure(val error: ChildEnrollmentError) : ChildEnrollmentResult<Nothing>
}

sealed interface ChildEnrollmentUiState {
  data object ProtectionSetup : ChildEnrollmentUiState
  data object ReadyToScan : ChildEnrollmentUiState
  data object PreviewLoading : ChildEnrollmentUiState
  data class Preview(val payload: EnrollmentQrPayload, val details: ChildEnrollmentPreview) : ChildEnrollmentUiState
  data object Enrolling : ChildEnrollmentUiState
  data class Enrolled(val state: LocalChildEnrollmentState, val policyApplied: Boolean) : ChildEnrollmentUiState
  data class Failure(val error: ChildEnrollmentError) : ChildEnrollmentUiState
}

interface ChildDeviceIdentityClient {
  suspend fun preview(code: String): ChildEnrollmentResult<ChildEnrollmentPreview>
  suspend fun complete(
    code: String,
    publicKeySpki: String,
    proof: String,
    installationId: String,
    deviceLabel: String?,
    appBuild: String,
  ): ChildEnrollmentResult<ChildEnrollmentCompletion>
  suspend fun fetchPolicy(accessJwt: String): ChildEnrollmentResult<ChildSupervisionPolicy>
  suspend fun acknowledgePolicy(accessJwt: String, version: Int): ChildEnrollmentResult<Unit>
  suspend fun heartbeat(accessJwt: String, capabilities: ChildCapabilities): ChildEnrollmentResult<Unit>
  suspend fun registerPushToken(accessJwt: String, token: String): ChildEnrollmentResult<Unit>
  suspend fun reconcileCommands(accessJwt: String): ChildEnrollmentResult<List<ChildDeviceCommand>>
  suspend fun rejectCommand(accessJwt: String, commandId: String, reason: String): ChildEnrollmentResult<Unit>
  suspend fun createTokenChallenge(credentialId: String): ChildEnrollmentResult<String>
  suspend fun exchangeTokenChallenge(
    credentialId: String,
    challenge: String,
    proof: String,
  ): ChildEnrollmentResult<Pair<String, Long>>
}

interface ChildDeviceKeyStore {
  fun createKeyAlias(): String
  fun publicKeySpki(alias: String): String
  fun sign(alias: String, message: String): String
}

interface ChildEnrollmentStateStore {
  fun load(): LocalChildEnrollmentState?
  fun save(state: LocalChildEnrollmentState)
  fun updateAccessToken(accessJwt: String, expiresAt: Long)
  fun markPolicyAcknowledged(version: Int)
  fun savePolicy(policy: ChildSupervisionPolicy)
  fun loadPolicy(): ChildSupervisionPolicy?
}

fun interface PolicyControlledRuntime {
  fun start(policy: ChildSupervisionPolicy)
}
