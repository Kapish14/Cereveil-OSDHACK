package com.cereveil.guardian.policy

import com.cereveil.guardian.auth.GuardianInstallationIdProvider
import com.cereveil.guardian.auth.GuardianOperationBootstrapper
import dev.convex.android.ConvexClient
import dev.convex.android.ConvexClientWithAuth
import dev.convex.android.ConvexError
import java.nio.charset.StandardCharsets
import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

private const val GET_POLICY_STATE = "modules/policies/guardian:getPolicyState"

class ConvexGuardianPolicyClient(
  private val convex: ConvexClient,
  private val installationIdProvider: GuardianInstallationIdProvider,
  private val bootstrapper: GuardianOperationBootstrapper,
) : GuardianPolicyClient {
  override fun observe(childProfileId: String): Flow<GuardianPolicyResult<GuardianPolicyState>> = flow {
    val installationId = installationId() ?: run {
      emit(GuardianPolicyResult.Failure(GuardianPolicyError.Unauthenticated))
      return@flow
    }
    emitAll(convex.subscribe<Map<String, Any?>>(GET_POLICY_STATE, mapOf(
      "guardianInstallationId" to installationId,
      "childProfileId" to childProfileId,
    )).map { result -> result.fold(
      onSuccess = { GuardianPolicyResult.Success(it.policyState()) },
      onFailure = { GuardianPolicyResult.Failure(GuardianPolicyError.Retryable) },
    ) })
  }

  override fun observeCatalog(childProfileId: String): Flow<GuardianPolicyResult<List<GuardianSelectableApp>>> = flow {
    val installationId = installationId() ?: run {
      emit(GuardianPolicyResult.Failure(GuardianPolicyError.Unauthenticated)); return@flow
    }
    emitAll(convex.subscribe<Map<String, Any?>>("modules/appCatalog/guardian:getLatestAppCatalog", mapOf(
      "guardianInstallationId" to installationId,
      "childProfileId" to childProfileId,
    )).map { result -> result.fold(
      onSuccess = { value ->
        @Suppress("UNCHECKED_CAST") val apps = value["apps"] as? List<Map<String, Any?>> ?: emptyList()
        GuardianPolicyResult.Success(apps.map { GuardianSelectableApp(it["packageName"].toString(), it["label"].toString()) })
      },
      onFailure = { GuardianPolicyResult.Failure(GuardianPolicyError.Retryable) },
    ) })
  }

  override suspend fun update(
    childProfileId: String,
    expectedVersion: Int,
    operationId: String,
    feature: PolicyFeature,
    enabled: Boolean,
    safeSearchEnabled: Boolean,
  ): GuardianPolicyResult<GuardianPolicyState> = try {
    val installationId = installationId()
      ?: return GuardianPolicyResult.Failure(GuardianPolicyError.Unauthenticated)
    val function = when (feature) {
      PolicyFeature.AppBlocking -> "modules/policies/guardian:updateAppBlocking"
      PolicyFeature.SafeBrowsing -> "modules/policies/guardian:updateSafeBrowsing"
      PolicyFeature.ScamTextSafety, PolicyFeature.NsfwScreenSafety ->
        return GuardianPolicyResult.Failure(GuardianPolicyError.Invalid)
      PolicyFeature.LocationSharing -> "modules/policies/guardian:updateLocationSharing"
      PolicyFeature.ScreenTime -> "modules/policies/guardian:updateScreenTime"
    }
    val args = mutableMapOf<String, Any?>(
      "guardianInstallationId" to installationId,
      "childProfileId" to childProfileId,
      "expectedCurrentVersion" to expectedVersion.toLong(),
      "operationId" to operationId,
      "enabled" to enabled,
    )
    if (feature == PolicyFeature.SafeBrowsing) args["safeSearchEnabled"] = safeSearchEnabled
    GuardianPolicyResult.Success(convex.mutation<Map<String, Any?>>(function, args).policyState())
  } catch (error: ConvexError) {
    GuardianPolicyResult.Failure(policyError(error.data))
  } catch (_: Exception) {
    GuardianPolicyResult.Failure(GuardianPolicyError.Retryable)
  }

  override suspend fun updateSafety(
    childProfileId: String,
    expectedVersion: Int,
    operationId: String,
    scamText: GuardianSafetyDetectorPolicy,
    nsfwScreen: GuardianSafetyDetectorPolicy,
  ): GuardianPolicyResult<GuardianPolicyState> = try {
    val installationId = installationId()
      ?: return GuardianPolicyResult.Failure(GuardianPolicyError.Unauthenticated)
    fun GuardianSafetyDetectorPolicy.payload() = mapOf(
      "enabled" to enabled,
      "monitoredPackageNames" to monitoredPackageNames.sorted(),
      "sensitivity" to sensitivity.wireValue,
    )
    GuardianPolicyResult.Success(convex.mutation<Map<String, Any?>>(
      "modules/policies/guardian:updateActiveScreenSafety",
      mapOf(
        "guardianInstallationId" to installationId,
        "childProfileId" to childProfileId,
        "expectedCurrentVersion" to expectedVersion.toLong(),
        "operationId" to operationId,
        "scamText" to scamText.payload(),
        "nsfwScreen" to nsfwScreen.payload(),
      ),
    ).policyState())
  } catch (error: ConvexError) {
    GuardianPolicyResult.Failure(policyError(error.data))
  } catch (_: Exception) {
    GuardianPolicyResult.Failure(GuardianPolicyError.Retryable)
  }

  private suspend fun installationId(): String? {
    val authenticated = convex as? ConvexClientWithAuth<String>
    if (authenticated != null && authenticated.loginFromCache().isFailure) return null
    installationIdProvider.getInstallationId()?.let { return it }
    if (!bootstrapper.ensureBootstrapped()) return null
    return installationIdProvider.getInstallationId()
  }
}

fun policyOperationId(
  childProfileId: String,
  expectedVersion: Int,
  feature: PolicyFeature,
  enabled: Boolean,
  safeSearchEnabled: Boolean,
): String = UUID.nameUUIDFromBytes(
  "$childProfileId|$expectedVersion|${feature.name}|$enabled|$safeSearchEnabled"
    .toByteArray(StandardCharsets.UTF_8)
).toString()

fun safetyPolicyOperationId(
  childProfileId: String,
  expectedVersion: Int,
  scamText: GuardianSafetyDetectorPolicy,
  nsfwScreen: GuardianSafetyDetectorPolicy,
): String = UUID.nameUUIDFromBytes(
  "$childProfileId|$expectedVersion|$scamText|$nsfwScreen".toByteArray(StandardCharsets.UTF_8),
).toString()

private fun Map<String, Any?>.policyState() = GuardianPolicyState(
  desired = (this["desiredPolicy"] as Map<String, Any?>).policy(),
  applied = (this["appliedPolicy"] as? Map<String, Any?>)?.policy(),
  status = when (this["applicationStatus"].toString()) {
    "pending" -> PolicyApplicationStatus.Pending
    "applied" -> PolicyApplicationStatus.Applied
    "failed" -> PolicyApplicationStatus.Failed
    else -> error("Unknown policy status")
  },
  failureReason = when (this["failureReason"]?.toString()) {
    null, "null" -> null
    "unsupported_schema" -> PolicyFailureReason.UnsupportedSchema
    "invalid_policy" -> PolicyFailureReason.InvalidPolicy
    "activation_failed" -> PolicyFailureReason.ActivationFailed
    else -> error("Unknown policy failure")
  },
  supportsNsfwScreenDetection = this["supportsNsfwScreenDetection"] as? Boolean ?: false,
)

private fun Map<String, Any?>.policy(): GuardianPolicy {
  val appBlocking = this["appBlocking"] as Map<String, Any?>
  val safeBrowsing = this["safeBrowsing"] as Map<String, Any?>
  val activeScreenSafety = this["activeScreenSafety"] as Map<String, Any?>
  val locationSharing = this["locationSharing"] as Map<String, Any?>
  val screenTime = this["screenTime"] as Map<String, Any?>
  fun detector(name: String): GuardianSafetyDetectorPolicy {
    @Suppress("UNCHECKED_CAST") val value = activeScreenSafety[name] as Map<String, Any?>
    @Suppress("UNCHECKED_CAST") val packages = value["monitoredPackageNames"] as? List<String> ?: emptyList()
    return GuardianSafetyDetectorPolicy(
      enabled = value["enabled"] as Boolean,
      monitoredPackageNames = packages.toSet(),
      sensitivity = when (value["sensitivity"].toString()) {
        "lower" -> GuardianSafetySensitivity.Lower
        "higher" -> GuardianSafetySensitivity.Higher
        else -> GuardianSafetySensitivity.Standard
      },
    )
  }
  return GuardianPolicy(
    version = (this["version"] as Number).toInt(),
    schemaVersion = (this["schemaVersion"] as Number).toInt(),
    appBlockingEnabled = appBlocking["enabled"] as Boolean,
    safeBrowsingEnabled = safeBrowsing["enabled"] as Boolean,
    safeSearchEnabled = safeBrowsing["safeSearchEnabled"] as Boolean,
    scamTextSafety = detector("scamText"),
    nsfwScreenSafety = detector("nsfwScreen"),
    locationSharingEnabled = locationSharing["enabled"] as Boolean,
    screenTimeEnabled = screenTime["enabled"] as Boolean,
  )
}

private fun policyError(data: String): GuardianPolicyError {
  val code = runCatching { Json.parseToJsonElement(data).jsonObject["code"]?.jsonPrimitive?.content }.getOrNull()
  return when (code) {
    "UNAUTHENTICATED" -> GuardianPolicyError.Unauthenticated
    "POLICY_CONFLICT" -> GuardianPolicyError.Conflict
    "POLICY_UNSUPPORTED" -> GuardianPolicyError.Unsupported
    "VALIDATION_FAILED", "POLICY_OPERATION_REUSED" -> GuardianPolicyError.Invalid
    else -> GuardianPolicyError.Retryable
  }
}
