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
    put("supportedPolicySchemaVersion", 1)
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
      put("supportedPolicySchemaVersion", 1)
      put("capabilities", buildJsonObject {
        put("accessibilityService", capabilities.accessibilityService)
        put("usageAccess", capabilities.usageAccess)
        put("location", capabilities.location)
        put("microphone", capabilities.microphone)
        put("notificationAccess", capabilities.notificationAccess)
        put("batteryOptimizationExempt", capabilities.batteryOptimizationExempt)
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
        if (cursor == null) put("cursor", JsonNull) else put("cursor", cursor!!)
      })
      when (result) {
        is ChildEnrollmentResult.Failure -> return result
        is ChildEnrollmentResult.Success -> {
          commands += result.value["commands"]!!.jsonArray.map { element ->
            val command = element.jsonObject
            ChildDeviceCommand(
              commandId = command.string("commandId"),
              type = command.string("type"),
              policyVersion = command.int("policyVersion"),
              expiresAt = command.long("expiresAt"),
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
