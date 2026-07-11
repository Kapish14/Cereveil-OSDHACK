package com.cereveil.guardian.enrollment

import dev.convex.android.ConvexClient
import dev.convex.android.ConvexClientWithAuth
import dev.convex.android.ConvexError
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.emitAll
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.cereveil.guardian.auth.GuardianInstallationIdProvider

private const val CREATE_CODE = "modules/deviceIdentity/guardian:createEnrollmentCode"
private const val CANCEL_CODE = "modules/deviceIdentity/guardian:cancelEnrollmentCode"
private const val ENROLLMENT_SUMMARY = "modules/deviceIdentity/guardian:getEnrollmentSummary"

class ConvexGuardianEnrollmentClient(
  private val convex: ConvexClient,
  private val installationIdProvider: GuardianInstallationIdProvider,
) : GuardianEnrollmentClient {
  override suspend fun createCode(
    childProfileId: String
  ): GuardianEnrollmentResult<GuardianEnrollmentCode> =
    try {
      if (!prepareAuth()) return GuardianEnrollmentResult.Failure(GuardianEnrollmentError.Unauthenticated)
      val installationId = installationIdProvider.getInstallationId()
        ?: return GuardianEnrollmentResult.Failure(GuardianEnrollmentError.BootstrapRequired)
      val response = convex.mutation<Map<String, Any?>>(CREATE_CODE, mapOf(
        "guardianInstallationId" to installationId,
        "childProfileId" to childProfileId,
      ))
      GuardianEnrollmentResult.Success(
        GuardianEnrollmentCode(
          enrollmentCodeId = response.requiredString("enrollmentCodeId"),
          qrPayload = response.requiredString("qrPayload"),
          expiresAt = response.requiredLong("expiresAt"),
          serverNow = response.requiredLong("serverNow"),
        )
      )
    } catch (error: ConvexError) {
      GuardianEnrollmentResult.Failure(mapError(error.data))
    } catch (_: Exception) {
      GuardianEnrollmentResult.Failure(GuardianEnrollmentError.Retryable)
    }

  override suspend fun cancelCode(enrollmentCodeId: String): GuardianEnrollmentResult<Unit> =
    try {
      if (!prepareAuth()) return GuardianEnrollmentResult.Failure(GuardianEnrollmentError.Unauthenticated)
      val installationId = installationIdProvider.getInstallationId()
        ?: return GuardianEnrollmentResult.Failure(GuardianEnrollmentError.BootstrapRequired)
      convex.mutation<Any?>(CANCEL_CODE, mapOf(
        "guardianInstallationId" to installationId,
        "enrollmentCodeId" to enrollmentCodeId,
      ))
      GuardianEnrollmentResult.Success(Unit)
    } catch (error: ConvexError) {
      GuardianEnrollmentResult.Failure(mapError(error.data))
    } catch (_: Exception) {
      GuardianEnrollmentResult.Failure(GuardianEnrollmentError.Retryable)
    }

  override fun observeSummary(
    childProfileId: String
  ): Flow<GuardianEnrollmentResult<GuardianEnrollmentSummary>> =
    kotlinx.coroutines.flow.flow {
      val installationId = installationIdProvider.getInstallationId()
      if (installationId == null) {
        emit(GuardianEnrollmentResult.Failure(GuardianEnrollmentError.BootstrapRequired))
        return@flow
      }
      emitAll(convex.subscribe<Map<String, Any?>>(ENROLLMENT_SUMMARY, mapOf(
        "guardianInstallationId" to installationId,
        "childProfileId" to childProfileId,
      ))
      .map { result ->
        result.fold(
          onSuccess = { value ->
            GuardianEnrollmentResult.Success(
              GuardianEnrollmentSummary(
                enrollmentActive = value.requiredString("enrollmentStatus") == "active",
                policyStatus = value.policyStatus("policyStatus"),
                connectivityStatus = value.connectivityStatus("connectivityStatus"),
                protectionHealthStatus = value.healthStatus("protectionHealthStatus"),
                serverNow = value.requiredLong("serverNow"),
              )
            )
          },
          onFailure = { GuardianEnrollmentResult.Failure(GuardianEnrollmentError.Retryable) },
        )
      })
    }

  @Suppress("UNCHECKED_CAST")
  private suspend fun prepareAuth(): Boolean {
    val authenticated = convex as? ConvexClientWithAuth<String> ?: return true
    return authenticated.loginFromCache().isSuccess
  }
}

private fun Map<String, Any?>.requiredString(key: String) =
  this[key]?.toString()?.takeIf(String::isNotBlank) ?: error("Missing $key")

private fun Map<String, Any?>.requiredLong(key: String) =
  (this[key] as? Number)?.toLong() ?: this[key]?.toString()?.toLongOrNull() ?: error("Missing $key")

private fun Map<String, Any?>.policyStatus(key: String) = when (requiredString(key)) {
  "not_applicable" -> GuardianPolicyStatus.NotApplicable
  "pending" -> GuardianPolicyStatus.Pending
  "applied" -> GuardianPolicyStatus.Applied
  else -> error("Unknown policy status")
}

private fun Map<String, Any?>.healthStatus(key: String) = when (requiredString(key)) {
  "not_applicable" -> GuardianProtectionHealthStatus.NotApplicable
  "pending" -> GuardianProtectionHealthStatus.Pending
  "fully_protected" -> GuardianProtectionHealthStatus.FullyProtected
  "protection_degraded" -> GuardianProtectionHealthStatus.ProtectionDegraded
  else -> error("Unknown health status")
}

private fun Map<String, Any?>.connectivityStatus(key: String) = when (requiredString(key)) {
  "not_applicable" -> GuardianConnectivityStatus.NotApplicable
  "pending" -> GuardianConnectivityStatus.Pending
  "online" -> GuardianConnectivityStatus.Online
  "offline" -> GuardianConnectivityStatus.Offline
  else -> error("Unknown connectivity status")
}

private fun mapError(data: String): GuardianEnrollmentError {
  val code = runCatching { Json.parseToJsonElement(data).jsonObject["code"]?.jsonPrimitive?.content }.getOrNull()
  return when (code) {
    "UNAUTHENTICATED" -> GuardianEnrollmentError.Unauthenticated
    "ACCOUNT_DISABLED", "ACCOUNT_DELETING", "HOUSEHOLD_DELETING" -> GuardianEnrollmentError.AccountUnavailable
    "CHILD_ALREADY_ENROLLED" -> GuardianEnrollmentError.AlreadyEnrolled
    "VALIDATION_FAILED" -> GuardianEnrollmentError.InvalidTarget
    else -> GuardianEnrollmentError.Retryable
  }
}
