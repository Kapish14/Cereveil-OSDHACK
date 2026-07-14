package com.cereveil.guardian.enrollment

import dev.convex.android.ConvexClient
import dev.convex.android.ConvexClientWithAuth
import dev.convex.android.ConvexError
import dev.convex.android.AuthState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.Serializable
import com.cereveil.guardian.auth.GuardianInstallationIdProvider
import com.cereveil.guardian.auth.GuardianOperationBootstrapper

private const val CREATE_CODE = "modules/deviceIdentity/guardian:createEnrollmentCode"
private const val CANCEL_CODE = "modules/deviceIdentity/guardian:cancelEnrollmentCode"
private const val ENROLLMENT_SUMMARY = "modules/deviceIdentity/guardian:getEnrollmentSummary"
private const val REPLACE_CHILD_DEVICE = "modules/deviceIdentity/guardian:replaceChildDevice"

class ConvexGuardianEnrollmentClient(
  private val convex: ConvexClient,
  private val installationIdProvider: GuardianInstallationIdProvider,
  private val bootstrapper: GuardianOperationBootstrapper,
) : GuardianEnrollmentClient {
  override suspend fun createCode(
    childProfileId: String
  ): GuardianEnrollmentResult<GuardianEnrollmentCode> =
    try {
      if (!prepareAuth()) return GuardianEnrollmentResult.Failure(GuardianEnrollmentError.Unauthenticated)
      val installationId = installationIdAfterBootstrap()
        ?: return GuardianEnrollmentResult.Failure(GuardianEnrollmentError.BootstrapRequired)
      val response = convex.mutation<EnrollmentCodeWire>(CREATE_CODE, mapOf(
        "guardianInstallationId" to installationId,
        "childProfileId" to childProfileId,
      ))
      GuardianEnrollmentResult.Success(
        GuardianEnrollmentCode(
          enrollmentCodeId = response.enrollmentCodeId,
          qrPayload = response.qrPayload,
          expiresAt = response.expiresAt.toLong(),
          serverNow = response.serverNow.toLong(),
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
      val installationId = installationIdAfterBootstrap()
        ?: return GuardianEnrollmentResult.Failure(GuardianEnrollmentError.BootstrapRequired)
      convex.mutation<String?>(CANCEL_CODE, mapOf(
        "guardianInstallationId" to installationId,
        "enrollmentCodeId" to enrollmentCodeId,
      ))
      GuardianEnrollmentResult.Success(Unit)
    } catch (error: ConvexError) {
      GuardianEnrollmentResult.Failure(mapError(error.data))
    } catch (_: Exception) {
      GuardianEnrollmentResult.Failure(GuardianEnrollmentError.Retryable)
    }

  override suspend fun replaceChildDevice(childProfileId: String): GuardianEnrollmentResult<Unit> =
    try {
      if (!prepareAuth()) return GuardianEnrollmentResult.Failure(GuardianEnrollmentError.Unauthenticated)
      val installationId = installationIdAfterBootstrap()
        ?: return GuardianEnrollmentResult.Failure(GuardianEnrollmentError.BootstrapRequired)
      convex.mutation<ReplaceChildDeviceWire>(REPLACE_CHILD_DEVICE, mapOf(
        "guardianInstallationId" to installationId,
        "childProfileId" to childProfileId,
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
      if (!prepareAuth()) {
        emit(GuardianEnrollmentResult.Failure(GuardianEnrollmentError.Unauthenticated))
        return@flow
      }
      val installationId = installationIdAfterBootstrap()
      if (installationId == null) {
        emit(GuardianEnrollmentResult.Failure(GuardianEnrollmentError.BootstrapRequired))
        return@flow
      }
      emitAll(convex.subscribe<EnrollmentSummaryWire>(ENROLLMENT_SUMMARY, mapOf(
        "guardianInstallationId" to installationId,
        "childProfileId" to childProfileId,
      ))
      .map { result ->
        result.fold(
          onSuccess = { value ->
            GuardianEnrollmentResult.Success(
              GuardianEnrollmentSummary(
                enrollmentActive = value.enrollmentStatus == "active",
                policyStatus = policyStatus(value.policyStatus),
                connectivityStatus = connectivityStatus(value.connectivityStatus),
                protectionHealthStatus = healthStatus(value.protectionHealthStatus),
                serverNow = value.serverNow.toLong(),
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
    if (authenticated.authState.value is AuthState.Authenticated) return true
    return withTimeoutOrNull(10_000) {
      if (authenticated.loginFromCache().isSuccess) {
        true
      } else {
        authenticated.authState.first { it is AuthState.Authenticated }
        true
      }
    } ?: false
  }

  private suspend fun installationIdAfterBootstrap(): String? {
    installationIdProvider.getInstallationId()?.let { return it }
    if (!bootstrapper.ensureBootstrapped()) return null
    return installationIdProvider.getInstallationId()
  }
}

private fun policyStatus(value: String) = when (value) {
  "not_applicable" -> GuardianPolicyStatus.NotApplicable
  "pending" -> GuardianPolicyStatus.Pending
  "applied" -> GuardianPolicyStatus.Applied
  else -> error("Unknown policy status")
}

private fun healthStatus(value: String) = when (value) {
  "not_applicable" -> GuardianProtectionHealthStatus.NotApplicable
  "pending" -> GuardianProtectionHealthStatus.Pending
  "fully_protected" -> GuardianProtectionHealthStatus.FullyProtected
  "protection_degraded" -> GuardianProtectionHealthStatus.ProtectionDegraded
  else -> error("Unknown health status")
}

private fun connectivityStatus(value: String) = when (value) {
  "not_applicable" -> GuardianConnectivityStatus.NotApplicable
  "pending" -> GuardianConnectivityStatus.Pending
  "online" -> GuardianConnectivityStatus.Online
  "offline" -> GuardianConnectivityStatus.Offline
  else -> error("Unknown connectivity status")
}

@Serializable
private data class EnrollmentCodeWire(
  val enrollmentCodeId: String,
  val qrPayload: String,
  val expiresAt: Double,
  val serverNow: Double,
)

@Serializable
private data class EnrollmentSummaryWire(
  val enrollmentStatus: String,
  val policyStatus: String,
  val connectivityStatus: String,
  val protectionHealthStatus: String,
  val serverNow: Double,
)

@Serializable
private data class ReplaceChildDeviceWire(val replaced: Boolean)

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
