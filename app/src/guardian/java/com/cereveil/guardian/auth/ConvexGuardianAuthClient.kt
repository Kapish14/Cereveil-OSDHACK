package com.cereveil.guardian.auth

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal const val BOOTSTRAP_GUARDIAN_FUNCTION_PATH = "modules/guardianAuth/public:bootstrapGuardian"
internal const val RETIRE_GUARDIAN_DEVICE_FUNCTION_PATH = "modules/guardianAuth/public:retireGuardianDevice"

class ConvexGuardianAuthClient(
  convexUrl: String,
  private val accessToken: suspend () -> String?,
) : GuardianAuthClient {
  private val deploymentUrl = convexUrl.removeSuffix("/")

  override suspend fun bootstrapGuardian(request: GuardianBootstrapRequest): GuardianBootstrapResult =
    withContext(Dispatchers.IO) {
      val token = accessToken()
        ?: return@withContext GuardianBootstrapResult.Failure(GuardianBootstrapError.Unauthenticated)
      try {
        val connection = URL("$deploymentUrl/api/mutation").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.doOutput = true
        connection.setRequestProperty("accept", "application/json")
        connection.setRequestProperty("content-type", "application/json")
        connection.setRequestProperty("authorization", "Bearer $token")
        val body = buildJsonObject {
          put("path", BOOTSTRAP_GUARDIAN_FUNCTION_PATH)
          put("format", "json")
          put("args", buildJsonObject {
            put("guardianInstallationId", request.guardianInstallationId)
            request.deviceLabel?.let { put("deviceLabel", it) }
            put("appBuild", request.appBuild)
            request.timezone?.let { put("timezone", it) }
          })
        }
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val envelope = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val response = Json.parseToJsonElement(envelope).jsonObject
        if (connection.responseCode !in 200..299 || response.string("status") != "success") {
          val errorData = response["errorData"]?.toString().orEmpty()
          return@withContext GuardianBootstrapResult.Failure(mapConvexErrorDataToGuardianBootstrapError(errorData))
        }
        GuardianBootstrapResult.Success(response["value"]!!.jsonObject.toGuardianBootstrapState())
      } catch (_: Exception) {
        GuardianBootstrapResult.Failure(GuardianBootstrapError.Retryable)
      }
    }

  override suspend fun requestGuardianDeviceRetirement(
    guardianInstallationId: String,
  ): GuardianDeviceRetirementResult =
    withContext(Dispatchers.IO) {
      val token = accessToken() ?: return@withContext GuardianDeviceRetirementResult.RetryableFailure
      try {
        val connection = URL("$deploymentUrl/api/mutation").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.doOutput = true
        connection.setRequestProperty("accept", "application/json")
        connection.setRequestProperty("content-type", "application/json")
        connection.setRequestProperty("authorization", "Bearer $token")
        val body = buildJsonObject {
          put("path", RETIRE_GUARDIAN_DEVICE_FUNCTION_PATH)
          put("format", "json")
          put("args", buildJsonObject { put("guardianInstallationId", guardianInstallationId) })
        }
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val envelope = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val response = Json.parseToJsonElement(envelope).jsonObject
        if (
          connection.responseCode in 200..299 &&
          response.string("status") == "success" &&
          response["value"]?.jsonObject?.get("retired")?.jsonPrimitive?.content == "true"
        ) {
          GuardianDeviceRetirementResult.Completed
        } else {
          GuardianDeviceRetirementResult.RetryableFailure
        }
      } catch (error: CancellationException) {
        throw error
      } catch (_: Exception) {
        GuardianDeviceRetirementResult.RetryableFailure
      }
    }

  private fun JsonObject.toGuardianBootstrapState() = GuardianBootstrapState(
    guardianAccountId = string("guardianAccountId"),
    householdId = string("householdId"),
    guardianDeviceId = string("guardianDeviceId"),
    guardianDeviceStatus = string("guardianDeviceStatus"),
    hasChildProfiles = this["hasChildProfiles"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
    serverNow = string("serverNow").toDoubleOrNull()?.toLong() ?: 0L,
  )

  private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.content.orEmpty()
}

internal fun mapConvexErrorDataToGuardianBootstrapError(data: String): GuardianBootstrapError {
  val code = runCatching {
    Json.parseToJsonElement(data).jsonObject["code"]?.jsonPrimitive?.content
  }.getOrNull()

  return when (code) {
    "UNAUTHENTICATED" -> GuardianBootstrapError.Unauthenticated
    "DEVICE_REVOKED" -> GuardianBootstrapError.DeviceRevoked
    "DEVICE_LIMIT_REACHED" -> GuardianBootstrapError.DeviceLimitReached
    else -> GuardianBootstrapError.Retryable
  }
}
