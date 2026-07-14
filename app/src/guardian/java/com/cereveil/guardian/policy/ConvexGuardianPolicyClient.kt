package com.cereveil.guardian.policy

import com.cereveil.guardian.auth.GuardianInstallationIdProvider
import com.cereveil.guardian.auth.GuardianOperationBootstrapper
import com.cereveil.guardian.arrayOrEmpty
import com.cereveil.guardian.boolean
import com.cereveil.guardian.long
import com.cereveil.guardian.objectOrNull
import com.cereveil.guardian.string
import com.cereveil.guardian.stringOrNull
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
    emitAll(convex.subscribe<JsonElement>(GET_POLICY_STATE, mapOf(
      "guardianInstallationId" to installationId,
      "childProfileId" to childProfileId,
    )).map { result -> result.fold(
      onSuccess = { GuardianPolicyResult.Success(it.jsonObject.policyState()) },
      onFailure = { GuardianPolicyResult.Failure(GuardianPolicyError.Retryable) },
    ) })
  }

  override fun observeCatalog(childProfileId: String): Flow<GuardianPolicyResult<List<GuardianSelectableApp>>> = flow {
    val installationId = installationId() ?: run {
      emit(GuardianPolicyResult.Failure(GuardianPolicyError.Unauthenticated)); return@flow
    }
    emitAll(convex.subscribe<JsonElement>("modules/appCatalog/guardian:getLatestAppCatalog", mapOf(
      "guardianInstallationId" to installationId,
      "childProfileId" to childProfileId,
    )).map { result -> result.fold(
      onSuccess = { value ->
        val apps = value.jsonObject.arrayOrEmpty("apps")
        GuardianPolicyResult.Success(apps.map { it.jsonObject }.map {
          GuardianSelectableApp(it.string("packageName"), it.string("label"))
        })
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
      "expectedCurrentVersion" to expectedVersion.toDouble(),
      "operationId" to operationId,
      "enabled" to enabled,
    )
    if (feature == PolicyFeature.SafeBrowsing) args["safeSearchEnabled"] = safeSearchEnabled
    GuardianPolicyResult.Success(convex.mutation<JsonElement>(function, args).jsonObject.policyState())
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
    GuardianPolicyResult.Success(convex.mutation<JsonElement>(
      "modules/policies/guardian:updateActiveScreenSafety",
      mapOf(
        "guardianInstallationId" to installationId,
        "childProfileId" to childProfileId,
        "expectedCurrentVersion" to expectedVersion.toDouble(),
        "operationId" to operationId,
        "scamText" to scamText.payload(),
        "nsfwScreen" to nsfwScreen.payload(),
      ),
    ).jsonObject.policyState())
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

private fun JsonObject.policyState() = GuardianPolicyState(
  desired = requireNotNull(objectOrNull("desiredPolicy")).policy(),
  applied = objectOrNull("appliedPolicy")?.policy(),
  status = when (string("applicationStatus")) {
    "pending" -> PolicyApplicationStatus.Pending
    "applied" -> PolicyApplicationStatus.Applied
    "failed" -> PolicyApplicationStatus.Failed
    else -> error("Unknown policy status")
  },
  failureReason = when (stringOrNull("failureReason")) {
    null -> null
    "unsupported_schema" -> PolicyFailureReason.UnsupportedSchema
    "invalid_policy" -> PolicyFailureReason.InvalidPolicy
    "activation_failed" -> PolicyFailureReason.ActivationFailed
    else -> error("Unknown policy failure")
  },
  supportsNsfwScreenDetection = boolean("supportsNsfwScreenDetection"),
)

private fun JsonObject.policy(): GuardianPolicy {
  val appBlocking = requireNotNull(objectOrNull("appBlocking"))
  val safeBrowsing = requireNotNull(objectOrNull("safeBrowsing"))
  val activeScreenSafety = requireNotNull(objectOrNull("activeScreenSafety"))
  val locationSharing = requireNotNull(objectOrNull("locationSharing"))
  val screenTime = requireNotNull(objectOrNull("screenTime"))
  fun detector(name: String): GuardianSafetyDetectorPolicy {
    val value = requireNotNull(activeScreenSafety.objectOrNull(name))
    val packages = value.arrayOrEmpty("monitoredPackageNames").map { it.jsonPrimitive.content }
    return GuardianSafetyDetectorPolicy(
      enabled = value.boolean("enabled"),
      monitoredPackageNames = packages.toSet(),
      sensitivity = when (value.string("sensitivity")) {
        "lower" -> GuardianSafetySensitivity.Lower
        "higher" -> GuardianSafetySensitivity.Higher
        else -> GuardianSafetySensitivity.Standard
      },
    )
  }
  return GuardianPolicy(
    version = long("version").toInt(),
    schemaVersion = long("schemaVersion").toInt(),
    appBlockingEnabled = appBlocking.boolean("enabled"),
    safeBrowsingEnabled = safeBrowsing.boolean("enabled"),
    safeSearchEnabled = safeBrowsing.boolean("safeSearchEnabled"),
    scamTextSafety = detector("scamText"),
    nsfwScreenSafety = detector("nsfwScreen"),
    locationSharingEnabled = locationSharing.boolean("enabled"),
    screenTimeEnabled = screenTime.boolean("enabled"),
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
