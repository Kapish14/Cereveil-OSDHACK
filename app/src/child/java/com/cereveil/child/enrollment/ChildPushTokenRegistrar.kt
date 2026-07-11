package com.cereveil.child.enrollment

import android.content.Context
import com.cereveil.BuildConfig
import com.google.firebase.messaging.FirebaseMessagingService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ChildPushTokenRegistrar(private val context: Context) {
  private val preferences = context.getSharedPreferences("child_push_delivery", Context.MODE_PRIVATE)

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
}
