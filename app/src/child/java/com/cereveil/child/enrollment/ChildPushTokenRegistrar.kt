package com.cereveil.child.enrollment

import android.content.Context
import android.util.Log
import com.cereveil.BuildConfig
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ChildPushTokenRegistrar(private val context: Context) {
  private val preferences = context.getSharedPreferences("child_push_delivery", Context.MODE_PRIVATE)
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  fun registerCurrent() {
    FirebaseMessaging.getInstance().token
      .addOnSuccessListener { token -> scope.launch { register(token) } }
      .addOnFailureListener { error ->
        Log.e(
          "CereveilFCM",
          "Current Child FCM token acquisition failed: ${error.javaClass.simpleName}: ${error.message}",
        )
      }
  }

  suspend fun register(token: String) {
    preferences.edit().putString("pending_fcm_token", token).apply()
    val stateStore = SharedPreferencesChildEnrollmentStateStore(context)
    val state = stateStore.load() ?: return
    val client = HttpChildDeviceIdentityClient(BuildConfig.CONVEX_SITE_URL)
    val accessJwt = if (state.accessJwtExpiresAt > System.currentTimeMillis() + 30_000) {
      state.accessJwt
    } else {
      when (
        val refreshed = ChildDeviceTokenProvider(client, AndroidChildDeviceKeyStore(), stateStore).refresh()
      ) {
        is ChildEnrollmentResult.Success -> refreshed.value
        is ChildEnrollmentResult.Failure -> return
      }
    }
    if (client.registerPushToken(accessJwt, token) is ChildEnrollmentResult.Success) {
      preferences.edit().remove("pending_fcm_token").apply()
    }
  }

  suspend fun registerPending() {
    preferences.getString("pending_fcm_token", null)?.let { register(it) }
  }
}

class ChildFirebaseMessagingService : FirebaseMessagingService() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun onNewToken(token: String) {
    ChildPushTokenRegistrar(this).also { registrar ->
      scope.launch { registrar.register(token) }
    }
  }

  override fun onMessageReceived(message: RemoteMessage) {
    if (message.data["category"] != "child_command") return
    scope.launch {
      val store = SharedPreferencesChildEnrollmentStateStore(this@ChildFirebaseMessagingService)
      val client = HttpChildDeviceIdentityClient(BuildConfig.CONVEX_SITE_URL)
      ChildSupervisionSyncCoordinator(
        client = client,
        store = store,
        capabilities = AndroidProtectionCapabilities(this@ChildFirebaseMessagingService)::current,
        refreshToken = {
          ChildDeviceTokenProvider(client, AndroidChildDeviceKeyStore(), store).refresh()
        },
        runtime = AndroidPolicyControlledRuntime(this@ChildFirebaseMessagingService),
        featureProcessor = AndroidChildFeatureCommandProcessor(this@ChildFirebaseMessagingService, client),
      ).sync()
    }
  }
}
