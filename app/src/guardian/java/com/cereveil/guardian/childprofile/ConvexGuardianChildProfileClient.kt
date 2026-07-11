package com.cereveil.guardian.childprofile

import dev.convex.android.ConvexClient
import dev.convex.android.ConvexClientWithAuth
import dev.convex.android.ConvexError
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.emitAll
import com.cereveil.guardian.auth.GuardianInstallationIdProvider

internal const val CREATE_CHILD_PROFILE_FUNCTION_PATH =
  "modules/childProfiles/public:createChildProfile"
internal const val LIST_CHILD_PROFILES_FUNCTION_PATH =
  "modules/childProfiles/public:listChildProfiles"

class ConvexGuardianChildProfileClient(
  private val convexClient: ConvexClient,
  private val installationIdProvider: GuardianInstallationIdProvider,
) :
  GuardianChildProfileClient {
  override suspend fun createChildProfile(
    request: CreateChildProfileRequest
  ): GuardianChildProfileResult =
    try {
      if (!prepareAuth()) {
        return GuardianChildProfileResult.Failure(GuardianChildProfileError.Unauthenticated)
      }
      val installationId = installationIdProvider.getInstallationId()
        ?: return GuardianChildProfileResult.Failure(GuardianChildProfileError.BootstrapRequired)

      val response =
        convexClient.mutation<Map<String, Any?>>(
          CREATE_CHILD_PROFILE_FUNCTION_PATH,
          args =
            mapOf(
              "guardianInstallationId" to installationId,
              "displayName" to request.displayName,
              "birthMonth" to request.birthMonth,
              "birthYear" to request.birthYear,
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
      val installationId = installationIdProvider.getInstallationId()
        ?: return GuardianChildProfileListResult.Failure(GuardianChildProfileError.BootstrapRequired)

      val response =
        convexClient.subscribe<List<Map<String, Any?>>>(
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
      val installationId = installationIdProvider.getInstallationId()
      if (installationId == null) {
        emit(GuardianChildProfileListResult.Failure(GuardianChildProfileError.BootstrapRequired))
        return@flow
      }
      emitAll(convexClient.subscribe<List<Map<String, Any?>>>(
        LIST_CHILD_PROFILES_FUNCTION_PATH,
        mapOf("guardianInstallationId" to installationId),
      ).map { result ->
        result.fold(
          onSuccess = { rows -> GuardianChildProfileListResult.Success(rows.map { it.toChildProfileSummary() }) },
          onFailure = { GuardianChildProfileListResult.Failure(GuardianChildProfileError.Retryable) },
        )
      })
    }

  @Suppress("UNCHECKED_CAST")
  private suspend fun prepareAuth(): Boolean {
    val authenticatedClient = convexClient as? ConvexClientWithAuth<String> ?: return true
    return withTimeoutOrNull(10_000) { authenticatedClient.loginFromCache().isSuccess } ?: false
  }

  private fun Map<String, Any?>.toChildProfileSummary(): ChildProfileSummary =
    ChildProfileSummary(
      childProfileId = requiredStringValue("childProfileId"),
      displayName = requiredStringValue("displayName"),
      birthMonth = numberValue("birthMonth").toInt(),
      birthYear = numberValue("birthYear").toInt(),
      status = childProfileStatusValue("status"),
      enrollmentStatus = enrollmentStatusValue("enrollmentStatus"),
      currentPolicyVersionId = requiredStringValue("currentPolicyVersionId"),
      currentPolicyVersion = numberValue("currentPolicyVersion").toInt(),
      connectivityStatus = connectivityStatusValue("connectivityStatus"),
      protectionStatus = protectionStatusValue("protectionHealthStatus"),
      unavailableCapabilities = unavailableCapabilities(),
      lastHeartbeatAt = optionalLong("lastHeartbeatAt"),
      serverNow = optionalLong("serverNow"),
    )

  private fun Map<String, Any?>.requiredStringValue(key: String): String =
    this[key]?.toString()?.takeIf { it.isNotBlank() }
      ?: throw IllegalArgumentException("Missing $key")

  private fun Map<String, Any?>.numberValue(key: String): Long =
    (when (val value = this[key]) {
      is Number -> value.toLong()
      is String -> value.toLongOrNull()
      else -> null
    }) ?: throw IllegalArgumentException("Missing $key")

  private fun Map<String, Any?>.optionalLong(key: String): Long? = when (val value = this[key]) {
    is Number -> value.toLong()
    is String -> value.toLongOrNull()
    else -> null
  }

  private fun Map<String, Any?>.connectivityStatusValue(key: String) = when (requiredStringValue(key)) {
    "not_applicable" -> GuardianConnectivityStatus.NotApplicable
    "pending" -> GuardianConnectivityStatus.Pending
    "online" -> GuardianConnectivityStatus.Online
    "offline" -> GuardianConnectivityStatus.Offline
    else -> throw IllegalArgumentException("Unknown connectivity status")
  }

  private fun Map<String, Any?>.protectionStatusValue(key: String) = when (requiredStringValue(key)) {
    "not_applicable" -> GuardianProtectionStatus.NotApplicable
    "pending" -> GuardianProtectionStatus.Pending
    "fully_protected" -> GuardianProtectionStatus.FullyProtected
    "protection_degraded" -> GuardianProtectionStatus.ProtectionDegraded
    else -> throw IllegalArgumentException("Unknown protection status")
  }

  @Suppress("UNCHECKED_CAST")
  private fun Map<String, Any?>.unavailableCapabilities(): List<String> {
    val capabilities = this["capabilities"] as? Map<String, Any?> ?: return emptyList()
    return capabilities.filterValues { it == false }.keys.map { key ->
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

  private fun Map<String, Any?>.childProfileStatusValue(key: String): ChildProfileStatus =
    when (requiredStringValue(key)) {
      "active" -> ChildProfileStatus.Active
      else -> throw IllegalArgumentException("Unknown Child Profile status")
    }

  private fun Map<String, Any?>.enrollmentStatusValue(key: String): ChildProfileEnrollmentStatus =
    when (requiredStringValue(key)) {
      "unenrolled" -> ChildProfileEnrollmentStatus.Unenrolled
      "active" -> ChildProfileEnrollmentStatus.Active
      else -> throw IllegalArgumentException("Unknown enrollment status")
    }

  private fun ConvexError.toGuardianChildProfileError(): GuardianChildProfileError {
    return mapConvexErrorDataToGuardianChildProfileError(data)
  }
}

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
