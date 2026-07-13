package com.cereveil.child.enrollment

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray

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
  val schemaVersion: Int,
  val appBlocking: AppBlockingPolicy,
  val safeBrowsing: SafeBrowsingPolicy,
  val activeScreenSafety: ActiveScreenSafetyPolicy,
  val locationSharing: LocationSharingPolicy,
  val screenTime: ScreenTimePolicy,
  val rawJson: String,
) {
  companion object {
    fun parse(raw: String): ChildSupervisionPolicy {
      val value = Json.parseToJsonElement(raw).jsonObject
      val schemaVersion = value["schemaVersion"]?.jsonPrimitive?.content?.toIntOrNull()
        ?: error("Missing policy schema version")
      require(schemaVersion == 2) { "Unsupported policy schema" }
      val safeBrowsing = value["safeBrowsing"]!!.jsonObject
      val safeBrowsingEnabled = safeBrowsing["enabled"]!!.jsonPrimitive.boolean
      val safeSearchEnabled = safeBrowsing["safeSearchEnabled"]!!.jsonPrimitive.boolean
      require(safeBrowsingEnabled || !safeSearchEnabled) { "Safe Search requires Safe Browsing" }
      return ChildSupervisionPolicy(
        version = value["version"]!!.jsonPrimitive.content.toInt(),
        schemaVersion = schemaVersion,
        appBlocking = value["appBlocking"]!!.jsonObject.let { section ->
          AppBlockingPolicy(
            enabled = section["enabled"]!!.jsonPrimitive.boolean,
            rules = section["rules"]!!.jsonArray.map { item ->
              val rule = item.jsonObject
              AppBlockRule(
                packageName = rule["packageName"]!!.jsonPrimitive.content,
                manualBlocked = rule["manualBlocked"]!!.jsonPrimitive.boolean,
                schedules = rule["schedules"]!!.jsonArray.map { scheduleItem ->
                  val schedule = scheduleItem.jsonObject
                  AppBlockSchedule(
                    scheduleId = schedule["scheduleId"]!!.jsonPrimitive.content,
                    weekdays = schedule["weekdays"]!!.jsonArray
                      .map { it.jsonPrimitive.content.toInt() }.toSet(),
                    startMinute = schedule["startMinute"]!!.jsonPrimitive.content.toInt(),
                    endMinute = schedule["endMinute"]!!.jsonPrimitive.content.toInt(),
                  )
                },
              )
            },
          )
        },
        safeBrowsing = SafeBrowsingPolicy(safeBrowsingEnabled, safeSearchEnabled),
        activeScreenSafety = ActiveScreenSafetyPolicy(value["activeScreenSafety"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean),
        locationSharing = LocationSharingPolicy(value["locationSharing"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean),
        screenTime = ScreenTimePolicy(value["screenTime"]!!.jsonObject["enabled"]!!.jsonPrimitive.boolean),
        rawJson = raw,
      )
    }
  }
}

data class AppBlockingPolicy(val enabled: Boolean, val rules: List<AppBlockRule> = emptyList())
data class AppBlockRule(
  val packageName: String,
  val manualBlocked: Boolean,
  val schedules: List<AppBlockSchedule>,
)
data class AppBlockSchedule(
  val scheduleId: String,
  val weekdays: Set<Int>,
  val startMinute: Int,
  val endMinute: Int,
)
data class SafeBrowsingPolicy(val enabled: Boolean, val safeSearchEnabled: Boolean)
data class ActiveScreenSafetyPolicy(val enabled: Boolean)
data class LocationSharingPolicy(val enabled: Boolean)
data class ScreenTimePolicy(val enabled: Boolean)

data class ChildDeviceCommand(
  val commandId: String,
  val type: String,
  val policyVersion: Int?,
  val expiresAt: Long,
  val referenceId: String? = null,
)

data class ChildAppCatalogEntry(val packageName: String, val label: String)
data class ChildAccessGrant(val grantId: String, val packageName: String, val startsAt: Long, val expiresAt: Long)
data class ChildLocationMeasurement(
  val latitude: Double,
  val longitude: Double,
  val accuracyMeters: Double,
  val capturedAt: Long,
)
data class ChildScreenTimeRow(val packageName: String, val totalTimeInForegroundMs: Long)

data class ChildCapabilities(
  val accessibilityService: Boolean,
  val usageAccess: Boolean,
  val location: Boolean,
  val microphone: Boolean,
  val notificationAccess: Boolean,
  val batteryOptimizationExempt: Boolean,
  val trustedDeviceTime: Boolean = true,
) {
  val protectionSetupComplete: Boolean
    get() = accessibilityService && usageAccess && location && microphone && notificationAccess &&
      batteryOptimizationExempt && trustedDeviceTime
}

enum class ChildEnrollmentError {
  InvalidCode,
  AlreadyEnrolled,
  EnrollmentFailed,
  ValidationFailed,
  Unauthorized,
  PolicyVersionMismatch,
  InvalidPolicy,
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
  suspend fun acknowledgeCommand(accessJwt: String, commandId: String): ChildEnrollmentResult<Unit> =
    ChildEnrollmentResult.Failure(ChildEnrollmentError.NetworkUnavailable)
  suspend fun syncAppCatalog(accessJwt: String, apps: List<ChildAppCatalogEntry>): ChildEnrollmentResult<Unit> =
    ChildEnrollmentResult.Failure(ChildEnrollmentError.NetworkUnavailable)
  suspend fun fetchAccessGrants(accessJwt: String): ChildEnrollmentResult<List<ChildAccessGrant>> =
    ChildEnrollmentResult.Failure(ChildEnrollmentError.NetworkUnavailable)
  suspend fun uploadLocation(
    accessJwt: String,
    measurement: ChildLocationMeasurement,
    refreshRequestId: String? = null,
  ): ChildEnrollmentResult<Unit> = ChildEnrollmentResult.Failure(ChildEnrollmentError.NetworkUnavailable)
  suspend fun failLocationRefresh(accessJwt: String, refreshRequestId: String, reason: String): ChildEnrollmentResult<Unit> =
    ChildEnrollmentResult.Failure(ChildEnrollmentError.NetworkUnavailable)
  suspend fun uploadScreenTime(
    accessJwt: String,
    refreshRequestId: String,
    measuredAt: Long,
    localDayStart: Long,
    validUntil: Long,
    rows: List<ChildScreenTimeRow>,
  ): ChildEnrollmentResult<Unit> = ChildEnrollmentResult.Failure(ChildEnrollmentError.NetworkUnavailable)
  suspend fun createAccessRequest(
    accessJwt: String,
    packageName: String,
    appliedPolicyVersion: Int,
    blockKind: String,
    scheduledCoverageEnd: Long?,
  ): ChildEnrollmentResult<Unit> = ChildEnrollmentResult.Failure(ChildEnrollmentError.NetworkUnavailable)
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

sealed interface PolicyActivationResult {
  data object Success : PolicyActivationResult
  data object RetryableFailure : PolicyActivationResult
  data class PermanentFailure(val reason: PolicyPermanentFailureReason) : PolicyActivationResult
}

enum class PolicyPermanentFailureReason(val wireValue: String) {
  UnsupportedSchema("unsupported_schema"),
  InvalidPolicy("invalid_command"),
  UnableToApply("unable_to_apply"),
}

fun interface PolicyControlledRuntime {
  fun start(policy: ChildSupervisionPolicy): PolicyActivationResult
}
