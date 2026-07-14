package com.cereveil.guardian.childprofile

import dev.convex.android.ConvexClient
import dev.convex.android.ConvexClientWithAuth
import dev.convex.android.ConvexError
import dev.convex.android.AuthState
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.Serializable
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.emitAll
import com.cereveil.guardian.auth.GuardianInstallationIdProvider
import com.cereveil.guardian.auth.GuardianOperationBootstrapper

internal const val CREATE_CHILD_PROFILE_FUNCTION_PATH =
  "modules/childProfiles/public:createChildProfile"
internal const val LIST_CHILD_PROFILES_FUNCTION_PATH =
  "modules/childProfiles/public:listChildProfiles"

class ConvexGuardianChildProfileClient(
  private val convexClient: ConvexClient,
  private val installationIdProvider: GuardianInstallationIdProvider,
  private val bootstrapper: GuardianOperationBootstrapper,
) :
  GuardianChildProfileClient {
  override suspend fun createChildProfile(
    request: CreateChildProfileRequest
  ): GuardianChildProfileResult =
    try {
      if (!prepareAuth()) {
        return GuardianChildProfileResult.Failure(GuardianChildProfileError.Unauthenticated)
      }
      val installationId = installationIdAfterBootstrap()
        ?: return GuardianChildProfileResult.Failure(GuardianChildProfileError.BootstrapRequired)

      val response =
        convexClient.mutation<ChildProfileWire>(
          CREATE_CHILD_PROFILE_FUNCTION_PATH,
          args =
            mapOf(
              "guardianInstallationId" to installationId,
              "displayName" to request.displayName,
              "birthMonth" to request.birthMonth.toDouble(),
              "birthYear" to request.birthYear.toDouble(),
            ),
        )

      GuardianChildProfileResult.Success(response.toChildProfileSummary())
    } catch (error: ConvexError) {
      GuardianChildProfileResult.Failure(error.toGuardianChildProfileError())
    } catch (_: Exception) {
      GuardianChildProfileResult.Failure(GuardianChildProfileError.Retryable)
    }

  override suspend fun listChildProfiles(): GuardianChildProfileListResult =
    try {
      if (!prepareAuth()) {
        return GuardianChildProfileListResult.Failure(GuardianChildProfileError.Unauthenticated)
      }
      val installationId = installationIdAfterBootstrap()
        ?: return GuardianChildProfileListResult.Failure(GuardianChildProfileError.BootstrapRequired)

      val response =
        convexClient.subscribe<List<ChildProfileWire>>(
          LIST_CHILD_PROFILES_FUNCTION_PATH,
          args = mapOf("guardianInstallationId" to installationId),
        ).first().getOrThrow()

      GuardianChildProfileListResult.Success(response.map { it.toChildProfileSummary() })
    } catch (error: ConvexError) {
      GuardianChildProfileListResult.Failure(error.toGuardianChildProfileError())
    } catch (_: Exception) {
      GuardianChildProfileListResult.Failure(GuardianChildProfileError.Retryable)
    }

  override fun observeChildProfiles(): Flow<GuardianChildProfileListResult> =
    kotlinx.coroutines.flow.flow {
      if (!prepareAuth()) {
        emit(GuardianChildProfileListResult.Failure(GuardianChildProfileError.Unauthenticated))
        return@flow
      }
      val installationId = installationIdAfterBootstrap()
      if (installationId == null) {
        emit(GuardianChildProfileListResult.Failure(GuardianChildProfileError.BootstrapRequired))
        return@flow
      }
      emitAll(convexClient.subscribe<List<ChildProfileWire>>(
        LIST_CHILD_PROFILES_FUNCTION_PATH,
        mapOf("guardianInstallationId" to installationId),
      ).map { result ->
        result.fold(
          onSuccess = { rows -> GuardianChildProfileListResult.Success(rows.map { it.toChildProfileSummary() }) },
          onFailure = { GuardianChildProfileListResult.Failure(GuardianChildProfileError.Retryable) },
        )
      })
    }

  private suspend fun installationIdAfterBootstrap(): String? {
    installationIdProvider.getInstallationId()?.let { return it }
    if (!bootstrapper.ensureBootstrapped()) return null
    return installationIdProvider.getInstallationId()
  }

  @Suppress("UNCHECKED_CAST")
  private suspend fun prepareAuth(): Boolean {
    val authenticatedClient = convexClient as? ConvexClientWithAuth<String> ?: return true
    if (authenticatedClient.authState.value is AuthState.Authenticated) return true
    return withTimeoutOrNull(10_000) {
      if (authenticatedClient.loginFromCache().isSuccess) {
        true
      } else {
        authenticatedClient.authState.first { it is AuthState.Authenticated }
        true
      }
    } ?: false
  }

  private fun ChildProfileWire.toChildProfileSummary(): ChildProfileSummary =
    ChildProfileSummary(
      childProfileId = childProfileId,
      displayName = displayName,
      birthMonth = birthMonth.toInt(),
      birthYear = birthYear.toInt(),
      status = childProfileStatusValue(status),
      enrollmentStatus = enrollmentStatusValue(enrollmentStatus),
      currentPolicyVersionId = currentPolicyVersionId,
      currentPolicyVersion = currentPolicyVersion.toInt(),
      connectivityStatus = connectivityStatusValue(connectivityStatus),
      protectionStatus = protectionStatusValue(protectionHealthStatus),
      unavailableCapabilities = capabilities.unavailableCapabilities(),
      lastHeartbeatAt = lastHeartbeatAt?.toLong(),
      serverNow = serverNow?.toLong(),
    )

  private fun connectivityStatusValue(value: String) = when (value) {
    "not_applicable" -> GuardianConnectivityStatus.NotApplicable
    "pending" -> GuardianConnectivityStatus.Pending
    "online" -> GuardianConnectivityStatus.Online
    "offline" -> GuardianConnectivityStatus.Offline
    else -> throw IllegalArgumentException("Unknown connectivity status")
  }

  private fun protectionStatusValue(value: String) = when (value) {
    "not_applicable" -> GuardianProtectionStatus.NotApplicable
    "pending" -> GuardianProtectionStatus.Pending
    "fully_protected" -> GuardianProtectionStatus.FullyProtected
    "protection_degraded" -> GuardianProtectionStatus.ProtectionDegraded
    else -> throw IllegalArgumentException("Unknown protection status")
  }

  private fun ChildCapabilitiesWire?.unavailableCapabilities(): List<String> {
    val capabilities = this ?: return emptyList()
    return mapOf(
      "accessibilityService" to accessibilityService,
      "usageAccess" to usageAccess,
      "location" to location,
      "microphone" to microphone,
      "notificationAccess" to notificationAccess,
      "batteryOptimizationExempt" to batteryOptimizationExempt,
    ).filterValues { it == false }.keys.map { key ->
      when (key) {
        "accessibilityService" -> "Accessibility"
        "usageAccess" -> "Usage access"
        "location" -> "Location"
        "microphone" -> "Microphone"
        "notificationAccess" -> "Notifications"
        "batteryOptimizationExempt" -> "Battery access"
        else -> key
      }
    }
  }

  private fun childProfileStatusValue(value: String): ChildProfileStatus =
    when (value) {
      "active" -> ChildProfileStatus.Active
      else -> throw IllegalArgumentException("Unknown Child Profile status")
    }

  private fun enrollmentStatusValue(value: String): ChildProfileEnrollmentStatus =
    when (value) {
      "unenrolled" -> ChildProfileEnrollmentStatus.Unenrolled
      "active" -> ChildProfileEnrollmentStatus.Active
      else -> throw IllegalArgumentException("Unknown enrollment status")
    }

  private fun ConvexError.toGuardianChildProfileError(): GuardianChildProfileError {
    return mapConvexErrorDataToGuardianChildProfileError(data)
  }
}

@Serializable
private data class ChildProfileWire(
  val childProfileId: String,
  val displayName: String,
  val birthMonth: Double,
  val birthYear: Double,
  val status: String,
  val enrollmentStatus: String,
  val currentPolicyVersionId: String,
  val currentPolicyVersion: Double,
  val connectivityStatus: String,
  val protectionHealthStatus: String,
  val capabilities: ChildCapabilitiesWire? = null,
  val lastHeartbeatAt: Double? = null,
  val serverNow: Double? = null,
)

@Serializable
private data class ChildCapabilitiesWire(
  val accessibilityService: Boolean,
  val usageAccess: Boolean,
  val location: Boolean,
  val microphone: Boolean,
  val notificationAccess: Boolean,
  val batteryOptimizationExempt: Boolean,
)

internal fun mapConvexErrorDataToGuardianChildProfileError(data: String): GuardianChildProfileError {
  val code =
    runCatching {
        Json.parseToJsonElement(data).jsonObject["code"]?.jsonPrimitive?.content
      }
      .getOrNull()

  return when (code) {
    "UNAUTHENTICATED" -> GuardianChildProfileError.Unauthenticated
    "ACCOUNT_DISABLED", "ACCOUNT_DELETING", "HOUSEHOLD_DELETING" ->
      GuardianChildProfileError.AccountUnavailable
    "VALIDATION_FAILED" -> GuardianChildProfileError.ValidationFailed
    "CHILD_AGE_OUT_OF_RANGE" -> GuardianChildProfileError.AgeOutOfRange
    else -> GuardianChildProfileError.Retryable
  }
}
