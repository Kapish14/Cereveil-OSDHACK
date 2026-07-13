package com.cereveil.child.enrollment

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.add

class HttpChildDeviceIdentityClient(convexSiteUrl: String) : ChildDeviceIdentityClient {
  private val siteUrl = convexSiteUrl.removeSuffix("/")

  override suspend fun preview(code: String) = request("/device-identity/enrollment/preview", body = buildJsonObject {
    put("code", code)
  }).map { value ->
    ChildEnrollmentPreview(value.string("childDisplayName"), value.long("codeExpiresAt"), value.long("serverNow"))
  }

  override suspend fun complete(
    code: String,
    publicKeySpki: String,
    proof: String,
    installationId: String,
    deviceLabel: String?,
    appBuild: String,
  ) = request("/device-identity/enrollment/complete", body = buildJsonObject {
    put("code", code)
    put("publicKeySpki", publicKeySpki)
    put("proof", proof)
    put("installationId", installationId)
    deviceLabel?.let { put("deviceLabel", it) }
    put("appBuild", appBuild)
    put("supportedPolicySchemaVersion", 2)
  }).map { value ->
    ChildEnrollmentCompletion(
      childDeviceId = value.string("childDeviceId"),
      activeEnrollmentId = value.string("activeEnrollmentId"),
      credentialId = value.string("credentialId"),
      childDisplayName = value.string("childDisplayName"),
      desiredPolicyVersion = value.int("desiredPolicyVersion"),
      accessJwt = value.string("accessJwt"),
      accessJwtExpiresAt = value.long("accessJwtExpiresAt"),
      enrolledAt = value.long("enrolledAt"),
      environment = value.string("environment"),
      serverNow = value.long("serverNow"),
    )
  }

  override suspend fun fetchPolicy(accessJwt: String): ChildEnrollmentResult<ChildSupervisionPolicy> =
    when (val result = request("/child/policy", accessJwt = accessJwt)) {
      is ChildEnrollmentResult.Failure -> result
      is ChildEnrollmentResult.Success -> runCatching { ChildSupervisionPolicy.parse(result.value.toString()) }
        .fold(
          onSuccess = { ChildEnrollmentResult.Success(it) },
          onFailure = { ChildEnrollmentResult.Failure(ChildEnrollmentError.InvalidPolicy) },
        )
    }

  override suspend fun acknowledgePolicy(accessJwt: String, version: Int) =
    request("/child/policy/acknowledge", accessJwt, buildJsonObject { put("appliedPolicyVersion", version) }).unit()

  override suspend fun heartbeat(accessJwt: String, capabilities: ChildCapabilities) =
    request("/child/heartbeat", accessJwt, buildJsonObject {
      put("supportedPolicySchemaVersion", 2)
      put("capabilities", buildJsonObject {
        put("accessibilityService", capabilities.accessibilityService)
        put("usageAccess", capabilities.usageAccess)
        put("location", capabilities.location)
        put("microphone", capabilities.microphone)
        put("notificationAccess", capabilities.notificationAccess)
        put("batteryOptimizationExempt", capabilities.batteryOptimizationExempt)
        put("trustedDeviceTime", capabilities.trustedDeviceTime)
      })
    }).unit()

  override suspend fun registerPushToken(accessJwt: String, token: String) =
    request("/child/push-token", accessJwt, buildJsonObject { put("token", token) }).unit()

  override suspend fun reconcileCommands(accessJwt: String): ChildEnrollmentResult<List<ChildDeviceCommand>> {
    val commands = mutableListOf<ChildDeviceCommand>()
    var cursor: String? = null
    var done: Boolean
    do {
      val result = request("/child/commands", accessJwt, buildJsonObject {
        cursor?.let { put("cursor", it) } ?: put("cursor", JsonNull)
      })
      when (result) {
        is ChildEnrollmentResult.Failure -> return result
        is ChildEnrollmentResult.Success -> {
          commands += result.value["commands"]!!.jsonArray.map { element ->
            val command = element.jsonObject
            ChildDeviceCommand(
              commandId = command.string("commandId"),
              type = command.string("type"),
              policyVersion = command["policyVersion"]?.jsonPrimitive?.content?.toIntOrNull(),
              expiresAt = command.long("expiresAt"),
              referenceId = command["referenceId"]?.jsonPrimitive?.content,
            )
          }
          cursor = result.value.string("continueCursor")
          done = result.value["isDone"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
        }
      }
    } while (!done)
    return ChildEnrollmentResult.Success(commands)
  }

  override suspend fun rejectCommand(accessJwt: String, commandId: String, reason: String) =
    request("/child/commands/reject", accessJwt, buildJsonObject {
      put("commandId", commandId)
      put("reason", reason)
    }).unit()

  override suspend fun acknowledgeCommand(accessJwt: String, commandId: String) =
    request("/child/commands/acknowledge", accessJwt, buildJsonObject { put("commandId", commandId) }).unit()

  override suspend fun syncAppCatalog(accessJwt: String, apps: List<ChildAppCatalogEntry>): ChildEnrollmentResult<Unit> {
    val started = request("/child/app-catalog/generations/start", accessJwt, buildJsonObject {
      put("expectedCount", apps.size)
    })
    if (started is ChildEnrollmentResult.Failure) return started
    val generationId = (started as ChildEnrollmentResult.Success).value.string("generationId")
    for (batch in apps.chunked(50)) {
      val uploaded = request("/child/app-catalog/generations/batch", accessJwt, buildJsonObject {
        put("generationId", generationId)
        put("apps", buildJsonArray { batch.forEach { app -> add(buildJsonObject {
          put("packageName", app.packageName); put("label", app.label)
        }) } })
      })
      if (uploaded is ChildEnrollmentResult.Failure) return uploaded
    }
    return request("/child/app-catalog/generations/complete", accessJwt, buildJsonObject {
      put("generationId", generationId)
    }).unit()
  }

  override suspend fun fetchAccessGrants(accessJwt: String): ChildEnrollmentResult<List<ChildAccessGrant>> =
    request("/child/access-grants", accessJwt).map { value ->
      value["grants"]!!.jsonArray.map { item -> item.jsonObject.let {
        ChildAccessGrant(it.string("grantId"), it.string("packageName"), it.long("startsAt"), it.long("expiresAt"))
      } }
    }

  override suspend fun fetchAccessRequestOutcome(accessJwt: String, requestId: String) =
    request("/child/access-requests/outcome", accessJwt, buildJsonObject { put("requestId", requestId) }).map { value ->
      value.string("status") to value["retryAt"]?.let { element ->
        if (element is JsonNull) null else element.jsonPrimitive.content.toLongOrNull()
      }
    }

  override suspend fun uploadLocation(
    accessJwt: String,
    measurement: ChildLocationMeasurement,
    refreshRequestId: String?,
  ) = request("/child/location", accessJwt, buildJsonObject {
    put("latitude", measurement.latitude); put("longitude", measurement.longitude)
    put("accuracyMeters", measurement.accuracyMeters); put("capturedAt", measurement.capturedAt)
    refreshRequestId?.let { put("refreshRequestId", it) }
  }).unit()

  override suspend fun failLocationRefresh(accessJwt: String, refreshRequestId: String, reason: String) =
    request("/child/location/refresh/fail", accessJwt, buildJsonObject {
      put("refreshRequestId", refreshRequestId); put("reason", reason)
    }).unit()

  override suspend fun uploadScreenTime(
    accessJwt: String,
    refreshRequestId: String,
    measuredAt: Long,
    localDayStart: Long,
    validUntil: Long,
    rows: List<ChildScreenTimeRow>,
  ): ChildEnrollmentResult<Unit> {
    val started = request("/child/screen-time/snapshots/start", accessJwt, buildJsonObject {
      put("refreshRequestId", refreshRequestId); put("expectedCount", rows.size)
      put("measuredAt", measuredAt); put("localDayStart", localDayStart); put("validUntil", validUntil)
    })
    if (started is ChildEnrollmentResult.Failure) return started
    val snapshotId = (started as ChildEnrollmentResult.Success).value.string("snapshotId")
    for (batch in rows.chunked(50)) {
      val uploaded = request("/child/screen-time/snapshots/batch", accessJwt, buildJsonObject {
        put("snapshotId", snapshotId)
        put("rows", buildJsonArray { batch.forEach { row -> add(buildJsonObject {
          put("packageName", row.packageName); put("totalTimeInForegroundMs", row.totalTimeInForegroundMs)
        }) } })
      })
      if (uploaded is ChildEnrollmentResult.Failure) return uploaded
    }
    return request("/child/screen-time/snapshots/complete", accessJwt, buildJsonObject {
      put("snapshotId", snapshotId)
    }).unit()
  }

  override suspend fun createAccessRequest(
    accessJwt: String,
    packageName: String,
    appliedPolicyVersion: Int,
    blockKind: String,
    scheduledCoverageEnd: Long?,
  ) = request("/child/access-requests", accessJwt, buildJsonObject {
    put("packageName", packageName)
    put("appliedPolicyVersion", appliedPolicyVersion)
    put("blockKind", blockKind)
    scheduledCoverageEnd?.let { put("scheduledCoverageEnd", it) }
  }).unit()

  override suspend fun createTokenChallenge(credentialId: String) =
    request("/device-identity/token/challenge", body = buildJsonObject { put("credentialId", credentialId) })
      .map { it.string("challenge") }

  override suspend fun exchangeTokenChallenge(credentialId: String, challenge: String, proof: String) =
    request("/device-identity/token/exchange", body = buildJsonObject {
      put("credentialId", credentialId)
      put("challenge", challenge)
      put("proof", proof)
    }).map { it.string("accessJwt") to it.long("accessJwtExpiresAt") }

  private suspend fun request(
    path: String,
    accessJwt: String? = null,
    body: JsonObject? = null,
  ): ChildEnrollmentResult<JsonObject> = withContext(Dispatchers.IO) {
    try {
      val connection = URL(siteUrl + path).openConnection() as HttpURLConnection
      connection.requestMethod = if (body == null) "GET" else "POST"
      connection.connectTimeout = 10_000
      connection.readTimeout = 10_000
      connection.setRequestProperty("accept", "application/json")
      accessJwt?.let { connection.setRequestProperty("authorization", "Bearer $it") }
      if (body != null) {
        connection.doOutput = true
        connection.setRequestProperty("content-type", "application/json")
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
      }
      val status = connection.responseCode
      val stream = if (status in 200..299) connection.inputStream else connection.errorStream
      val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
      val value = runCatching { Json.parseToJsonElement(response).jsonObject }.getOrNull()
      if (status in 200..299 && value != null) ChildEnrollmentResult.Success(value)
      else ChildEnrollmentResult.Failure(errorFor(status, value?.get("code")?.jsonPrimitive?.content))
    } catch (_: Exception) {
      ChildEnrollmentResult.Failure(ChildEnrollmentError.NetworkUnavailable)
    }
  }
}

private fun errorFor(status: Int, code: String?) = when (code) {
  "ENROLLMENT_CODE_INVALID" -> ChildEnrollmentError.InvalidCode
  "CHILD_ALREADY_ENROLLED" -> ChildEnrollmentError.AlreadyEnrolled
  "ENROLLMENT_FAILED" -> ChildEnrollmentError.EnrollmentFailed
  "CHILD_DEVICE_UNAUTHORIZED" -> ChildEnrollmentError.Unauthorized
  "VALIDATION_FAILED" -> ChildEnrollmentError.ValidationFailed
  "POLICY_VERSION_MISMATCH" -> ChildEnrollmentError.PolicyVersionMismatch
  "INTERNAL_ERROR" -> ChildEnrollmentError.InternalError
  else -> if (status >= 500) ChildEnrollmentError.InternalError else ChildEnrollmentError.EnrollmentFailed
}

private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.content ?: error("Missing $key")
private fun JsonObject.long(key: String) = string(key).toLong()
private fun JsonObject.int(key: String) = string(key).toInt()

private fun <T> ChildEnrollmentResult<JsonObject>.map(transform: (JsonObject) -> T): ChildEnrollmentResult<T> = when (this) {
  is ChildEnrollmentResult.Success -> runCatching { ChildEnrollmentResult.Success(transform(value)) }
    .getOrElse { ChildEnrollmentResult.Failure(ChildEnrollmentError.EnrollmentFailed) }
  is ChildEnrollmentResult.Failure -> this
}

private fun ChildEnrollmentResult<JsonObject>.unit(): ChildEnrollmentResult<Unit> = map { Unit }
