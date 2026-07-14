package com.cereveil.guardian.messaging

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.cereveil.BuildConfig
import com.cereveil.MainActivity
import com.cereveil.RoleInitializer
import com.cereveil.guardian.auth.SharedPreferencesGuardianInstallationIdProvider
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class GuardianPushTokenRegistrar(private val context: Context) {
  private val preferences = context.getSharedPreferences("guardian_push_delivery", Context.MODE_PRIVATE)

  suspend fun register(token: String) {
    preferences.edit().putString("pending_fcm_token", token).apply()
    val installationId = SharedPreferencesGuardianInstallationIdProvider(context).getInstallationId() ?: return
    val accessToken = RoleInitializer.guardianConvexToken() ?: return
    val succeeded = withContext(Dispatchers.IO) {
      runCatching {
        val connection = URL(BuildConfig.CONVEX_URL.removeSuffix("/") + "/api/action").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.doOutput = true
        connection.setRequestProperty("authorization", "Bearer $accessToken")
        connection.setRequestProperty("content-type", "application/json")
        val body = buildJsonObject {
          put("path", "modules/notifications/public:registerGuardianPushToken")
          put("format", "json")
          put("args", buildJsonObject {
            put("guardianInstallationId", installationId)
            put("token", token)
          })
        }
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val envelope = runCatching { Json.parseToJsonElement(response).jsonObject }.getOrNull()
        val succeeded = connection.responseCode in 200..299 && envelope?.get("status")?.jsonPrimitive?.content == "success"
        if (!succeeded) {
          val envelopeStatus = envelope?.get("status")?.jsonPrimitive?.content.orEmpty()
          val safeError = envelope?.get("errorData")?.toString()?.take(240).orEmpty()
          val safeMessage = envelope?.get("errorMessage")?.jsonPrimitive?.content?.take(240).orEmpty()
          Log.e("CereveilFCM", "Guardian token registration rejected: http=${connection.responseCode}, status=$envelopeStatus, error=$safeError, message=$safeMessage")
        }
        succeeded
      }.getOrDefault(false)
    }
    if (succeeded) preferences.edit().remove("pending_fcm_token").apply()
  }

  suspend fun registerPending() {
    preferences.getString("pending_fcm_token", null)?.let { register(it) }
  }
}

class GuardianFirebaseMessagingService : FirebaseMessagingService() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onNewToken(token: String) {
    scope.launch { GuardianPushTokenRegistrar(this@GuardianFirebaseMessagingService).register(token) }
  }

  override fun onMessageReceived(message: RemoteMessage) {
    if (message.data["category"] == "guardian_notice") {
      scope.launch { GuardianNoticeReconciler(this@GuardianFirebaseMessagingService).reconcile() }
    }
  }
}

class GuardianNoticeReconciler(private val context: Context) {
  private val preferences = context.getSharedPreferences("guardian_push_delivery", Context.MODE_PRIVATE)

  suspend fun reconcile() {
    val installationId = SharedPreferencesGuardianInstallationIdProvider(context).getInstallationId() ?: return
    val token = RoleInitializer.guardianConvexToken() ?: return
    var cursor: String? = null
    do {
      val page = convexCall("query", "modules/notifications/public:reconcileGuardianNotices", token, buildJsonObject {
        put("guardianInstallationId", installationId)
        put("paginationOpts", buildJsonObject {
          put("numItems", 50)
          cursor?.let { put("cursor", it) } ?: put("cursor", kotlinx.serialization.json.JsonNull)
        })
      }) ?: return
      val value = page["value"]?.jsonObject ?: return
      for (item in value["page"]?.jsonArray.orEmpty()) {
        val record = item.jsonObject
        val receiptId = record.string("receiptId")
        val notice = record["notice"]!!.jsonObject
        val shown = presentOnce(receiptId, notice)
        convexCall("mutation", "modules/notifications/public:acknowledgeGuardianNotice", token, buildJsonObject {
          put("guardianInstallationId", installationId)
          put("receiptId", receiptId)
          put("presentation", if (shown) "shown" else "suppressed")
        }) ?: return
      }
      cursor = value["continueCursor"]?.jsonPrimitive?.content
      val done = value["isDone"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
    } while (!done)
  }

  private fun presentOnce(receiptId: String, notice: JsonObject): Boolean {
    val decisionKey = "presentation_$receiptId"
    when (preferences.getString(decisionKey, null)) {
      "shown" -> return true
      "suppressed" -> return false
      "planned_shown" -> {
        showNotification(receiptId, notice)
        preferences.edit().putString(decisionKey, "shown").commit()
        return true
      }
    }
    val shown = !(
      android.os.Build.VERSION.SDK_INT >= 33 &&
      context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    )
    preferences.edit()
      .putString("notice_$receiptId", notice.toString())
      .putString(decisionKey, if (shown) "planned_shown" else "suppressed")
      .commit()
    if (!shown) return false
    showNotification(receiptId, notice)
    preferences.edit().putString(decisionKey, "shown").commit()
    return true
  }

  private fun showNotification(receiptId: String, notice: JsonObject) {
    val manager = context.getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Supervision notices", NotificationManager.IMPORTANCE_HIGH))
    val text = when (notice.string("type")) {
      "offline" -> "A Child Device is offline. Open Cereveil for current details."
      "recovery" -> "A Child Device is online again. Open Cereveil for current details."
      "tamper" -> "Protection needs attention. Open Cereveil for current details."
      "safety" -> "A new safety alert is available. Open Cereveil to review it."
      else -> "New supervision information is available."
    }
    val destination = Intent(context, MainActivity::class.java)
    if (notice.string("type") == "safety") {
      destination
        .putExtra("open_safety_feed", true)
        .putExtra("safety_child_profile_id", notice.string("childProfileId"))
    }
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
      .setSmallIcon(com.cereveil.R.mipmap.ic_launcher)
      .setContentTitle("Cereveil")
      .setContentText(text)
      .setAutoCancel(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(PendingIntent.getActivity(
        context,
        receiptId.hashCode(),
        destination,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      ))
      .build()
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
      NotificationManagerCompat.from(context).notify(receiptId.hashCode(), notification)
    }
  }

  private suspend fun convexCall(kind: String, path: String, token: String, args: JsonObject): JsonObject? =
    withContext(Dispatchers.IO) {
      runCatching {
        val connection = URL(BuildConfig.CONVEX_URL.removeSuffix("/") + "/api/$kind").openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 10_000
        connection.readTimeout = 15_000
        connection.doOutput = true
        connection.setRequestProperty("authorization", "Bearer $token")
        connection.setRequestProperty("content-type", "application/json")
        val body = buildJsonObject {
          put("path", path)
          put("format", "json")
          put("args", args)
        }
        connection.outputStream.use { it.write(body.toString().toByteArray()) }
        val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        Json.parseToJsonElement(response).jsonObject.takeIf { connection.responseCode in 200..299 }
      }.getOrNull()
    }

  private fun JsonObject.string(key: String) = this[key]?.jsonPrimitive?.content.orEmpty()

  private companion object { const val CHANNEL_ID = "guardian_notices" }
}
